package de.puetsch.easeeocpp.domain

import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import java.time.Instant

@Epic("Regelung")
@Feature("Eligibility der Charger")
class EligibilityPolicyTest {

    private val policy = EligibilityPolicy(ControlProperties())
    private val now: Instant = Instant.parse("2026-10-25T10:00:00Z")

    @Test
    fun `disconnected charger is never eligible`() {
        val snapshot = snapshot(connected = false, status = ChargerStatus.CHARGING)

        assertFalse(policy.isEligible(snapshot, now))
    }

    @ParameterizedTest
    @EnumSource(value = ChargerStatus::class, names = ["AVAILABLE", "UNAVAILABLE", "FAULTED"])
    fun `charger without plugged in vehicle is not eligible`(status: ChargerStatus) {
        assertFalse(policy.isEligible(snapshot(status = status), now))
    }

    @ParameterizedTest
    @EnumSource(value = ChargerStatus::class, names = ["PREPARING", "CHARGING", "SUSPENDED_EVSE", "RESERVED"])
    fun `charger with vehicle plugged in is eligible`(status: ChargerStatus) {
        assertTrue(policy.isEligible(snapshot(status = status), now))
    }

    @Test
    fun `suspended by ev within grace period stays eligible`() {
        val snapshot = snapshot(status = ChargerStatus.SUSPENDED_EV, statusSince = now.minusSeconds(120))

        assertTrue(policy.isEligible(snapshot, now))
    }

    @Test
    fun `suspended by ev after grace period is not eligible`() {
        val snapshot = snapshot(status = ChargerStatus.SUSPENDED_EV, statusSince = now.minus(Duration.ofSeconds(121)))

        assertFalse(policy.isEligible(snapshot, now))
    }

    @Test
    fun `finishing after grace period is not eligible`() {
        val snapshot = snapshot(status = ChargerStatus.FINISHING, statusSince = now.minus(Duration.ofSeconds(121)))

        assertFalse(policy.isEligible(snapshot, now))
    }

    @Test
    fun `finishing within grace period stays eligible`() {
        val snapshot = snapshot(status = ChargerStatus.FINISHING, statusSince = now.minusSeconds(30))

        assertTrue(policy.isEligible(snapshot, now))
    }

    @Test
    fun `unknown status start keeps charger eligible`() {
        val snapshot = snapshot(status = ChargerStatus.SUSPENDED_EV, statusSince = null)

        assertTrue(policy.isEligible(snapshot, now))
    }

    private fun snapshot(
        connected: Boolean = true,
        status: ChargerStatus = ChargerStatus.CHARGING,
        statusSince: Instant? = null,
    ): ChargerSnapshot = ChargerSnapshot(
        chargerId = "EHDFQLBX",
        connected = connected,
        status = status,
        power = 0.0,
        currentL1 = null,
        currentL2 = null,
        currentL3 = null,
        voltageL1 = null,
        voltageL2 = null,
        voltageL3 = null,
        lastMeterValueAt = null,
        statusSince = statusSince,
    )
}
