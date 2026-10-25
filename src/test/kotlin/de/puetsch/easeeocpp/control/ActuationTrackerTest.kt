package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.domain.model.ChargerLimit
import de.puetsch.easeeocpp.domain.model.PhaseMode
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

@Epic("Regelung")
@Feature("Änderungserkennung")
class ActuationTrackerTest {

    private val tracker = ActuationTracker(settleDelay = Duration.ofSeconds(30))

    @Test
    @Story("Phasenmodus")
    fun `phase mode counts as changed until it has been recorded`() {
        assertTrue(tracker.phaseModeChanged(PhaseMode.THREE))

        tracker.recordPhaseMode(PhaseMode.THREE)

        assertFalse(tracker.phaseModeChanged(PhaseMode.THREE))
        assertTrue(tracker.phaseModeChanged(PhaseMode.ONE))
    }

    @Test
    @Story("Strom")
    fun `limits count as changed when any charger differs from its recorded limit`() {
        val limits = listOf(ChargerLimit(MULTONNEN_ID, 7), ChargerLimit(EINGANGSSEITE_ID, 0))
        tracker.recordLimit(MULTONNEN_ID, 7)

        assertTrue(tracker.limitsChanged(limits))

        tracker.recordLimit(EINGANGSSEITE_ID, 0)

        assertFalse(tracker.limitsChanged(limits))
    }

    @Test
    @Story("Refresh")
    fun `refresh is due before the first attempt and after the interval`() {
        val interval = Duration.ofSeconds(60)
        assertTrue(tracker.refreshDue(CONTROL_START, interval))

        tracker.recordLimitAttempt(CONTROL_START)

        assertFalse(tracker.refreshDue(CONTROL_START.plusSeconds(60), interval))
        assertTrue(tracker.refreshDue(CONTROL_START.plusSeconds(61), interval))
    }

    @Test
    @Story("Settle-Pause")
    fun `settle window is open for thirty seconds after a start`() {
        assertNull(tracker.settleUntil(CONTROL_START))

        tracker.startSettle(CONTROL_START)

        assertTrue(tracker.isSettling(CONTROL_START.plusSeconds(29)))
        assertFalse(tracker.isSettling(CONTROL_START.plusSeconds(30)))
        assertEquals(CONTROL_START.plusSeconds(30), tracker.settleUntil(CONTROL_START))
    }
}
