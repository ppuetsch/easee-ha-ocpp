package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.EligibilityPolicy
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@Epic("Regelung")
@Feature("Berechtigung mit Haftung")
class StickyEligibilityTest {

    private val start: Instant = CONTROL_START
    private val ineligibleAfter: Duration = Duration.ofSeconds(120)
    private val eligibility = StickyEligibility(EligibilityPolicy(ControlProperties(ineligibleAfter = ineligibleAfter)))

    @Test
    @Story("Wartezustand")
    fun `charger waiting shortly after a status change stays eligible`() {
        val snapshot = chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.SUSPENDED_EV, statusSince = start)

        assertTrue(eligibility.isEligible(snapshot, start.plusSeconds(60)))
    }

    @Test
    @Story("Wartezustand")
    fun `charger waiting longer than the grace period becomes ineligible`() {
        val snapshot = chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.SUSPENDED_EV, statusSince = start)

        assertFalse(eligibility.isEligible(snapshot, start.plusSeconds(121)))
    }

    @Test
    @Story("Haftung")
    fun `ineligible charger stays ineligible when its waiting status flips to another waiting status`() {
        val waiting = chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.SUSPENDED_EV, statusSince = start)
        eligibility.isEligible(waiting, start.plusSeconds(121))

        val flipped = chargerSnapshot(
            MULTONNEN_ID,
            status = ChargerStatus.FINISHING,
            statusSince = start.plusSeconds(122),
        )

        assertFalse(eligibility.isEligible(flipped, start.plusSeconds(123)))
    }

    @Test
    @Story("Haftung")
    fun `ineligible charger becomes eligible again when it charges`() {
        val waiting = chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.SUSPENDED_EV, statusSince = start)
        eligibility.isEligible(waiting, start.plusSeconds(121))

        val charging = chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.CHARGING, statusSince = start.plusSeconds(130))

        assertTrue(eligibility.isEligible(charging, start.plusSeconds(131)))
    }

    @Test
    @Story("Haftung")
    fun `ineligible charger becomes eligible again when it is preparing`() {
        val waiting = chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.FINISHING, statusSince = start)
        eligibility.isEligible(waiting, start.plusSeconds(121))

        val preparing = chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.PREPARING, statusSince = start.plusSeconds(130))

        assertTrue(eligibility.isEligible(preparing, start.plusSeconds(131)))
    }

    @Test
    @Story("Haftung")
    fun `ineligible charger becomes eligible again when the wallbox suspends it itself`() {
        val waiting = chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.SUSPENDED_EV, statusSince = start)
        eligibility.isEligible(waiting, start.plusSeconds(121))

        val suspendedByWallbox = chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.SUSPENDED_EVSE, statusSince = start.plusSeconds(130))

        assertTrue(eligibility.isEligible(suspendedByWallbox, start.plusSeconds(131)))
    }

    @Test
    @Story("Haftung")
    fun `ineligible charger is released after being unplugged`() {
        val waiting = chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.SUSPENDED_EV, statusSince = start)
        eligibility.isEligible(waiting, start.plusSeconds(121))
        eligibility.isEligible(chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.AVAILABLE, statusSince = null), start.plusSeconds(122))

        val pluggedInAgain = chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.SUSPENDED_EV, statusSince = start.plusSeconds(200))

        assertTrue(eligibility.isEligible(pluggedInAgain, start.plusSeconds(201)))
    }

    @Test
    @Story("Haftung")
    fun `stickiness is tracked per charger`() {
        val waiting = chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.SUSPENDED_EV, statusSince = start)
        eligibility.isEligible(waiting, start.plusSeconds(121))

        val otherCharger = chargerSnapshot(EINGANGSSEITE_ID, status = ChargerStatus.CHARGING, statusSince = start)

        assertTrue(eligibility.isEligible(otherCharger, start.plusSeconds(121)))
    }
}
