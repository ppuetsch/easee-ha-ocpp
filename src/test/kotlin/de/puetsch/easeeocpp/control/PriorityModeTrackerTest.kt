package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

@Epic("Regelung")
@Feature("Verteilungsmodus")
class PriorityModeTrackerTest {

    private val tracker = PriorityModeTracker(ControlProperties())
    private val prio: ChargerId = MULTONNEN_ID
    private val other: ChargerId = EINGANGSSEITE_ID
    private val bothEligible = setOf(prio, other)
    private val prioLimitSet: Map<ChargerId, Ampere> = mapOf(prio to 10)

    @Test
    @Story("Leerlauf-Zeit")
    fun `prio charger idle for 1 min 59 s keeps the prioritized mode`() {
        val start = CONTROL_START

        val mode = updateAt(start.plusSeconds(0), idle = true)
        val almostTwoMinutes = updateAt(start.plusSeconds(119), idle = true)

        assertEquals(AllocationMode.PRIORITIZED, mode)
        assertEquals(AllocationMode.PRIORITIZED, almostTwoMinutes)
    }

    @Test
    @Story("Leerlauf-Zeit")
    fun `prio charger idle for exactly 2 min switches to shared`() {
        updateAt(CONTROL_START, idle = true)

        val mode = updateAt(CONTROL_START.plusSeconds(120), idle = true)

        assertEquals(AllocationMode.SHARED, mode)
    }

    @Test
    @Story("Leerlauf-Zeit")
    fun `charging power of at least 100 W resets the idle timer`() {
        updateAt(CONTROL_START, idle = true)
        updateAt(CONTROL_START.plusSeconds(60), idle = false)

        val restartedIdle = updateAt(CONTROL_START.plusSeconds(61), idle = true)
        val stillBeforeTwoMinutesAfterRestart = updateAt(CONTROL_START.plusSeconds(61 + 119), idle = true)
        val twoMinutesAfterRestart = updateAt(CONTROL_START.plusSeconds(61 + 120), idle = true)

        assertEquals(AllocationMode.PRIORITIZED, restartedIdle)
        assertEquals(AllocationMode.PRIORITIZED, stillBeforeTwoMinutesAfterRestart)
        assertEquals(AllocationMode.SHARED, twoMinutesAfterRestart)
    }

    @Test
    @Story("Leerlauf-Zeit")
    fun `exactly 100 W counts as charging and does not start the idle timer`() {
        val mode = updateAt(CONTROL_START, idle = false, prioPower = 100.0)
        val later = updateAt(CONTROL_START.plusSeconds(600), idle = false, prioPower = 100.0)

        assertEquals(AllocationMode.PRIORITIZED, mode)
        assertEquals(AllocationMode.PRIORITIZED, later)
    }

    @Test
    @Story("Leerlauf-Zeit")
    fun `without a set limit the prio charger never counts as idle`() {
        val limitBelowMinimum = mapOf(prio to 5)
        val idleSnapshots = snapshots(prioPower = 0.0)
        tracker.update(CONTROL_START, prio, bothEligible, idleSnapshots, limitBelowMinimum)

        val mode = tracker.update(CONTROL_START.plusSeconds(600), prio, bothEligible, idleSnapshots, limitBelowMinimum)

        assertEquals(AllocationMode.PRIORITIZED, mode)
    }

    @Test
    @Story("Zurück zu priorisiert")
    fun `shared mode returns to prioritized only when both chargers draw at least 100 W`() {
        updateAt(CONTROL_START, idle = true)
        updateAt(CONTROL_START.plusSeconds(120), idle = true)

        val onlyOtherCharging = updateAt(CONTROL_START.plusSeconds(130), idle = true, otherPower = 500.0)
        val bothCharging = updateAt(CONTROL_START.plusSeconds(140), idle = false, prioPower = 500.0, otherPower = 500.0)

        assertEquals(AllocationMode.SHARED, onlyOtherCharging)
        assertEquals(AllocationMode.PRIORITIZED, bothCharging)
    }

    @Test
    @Story("Zurück zu priorisiert")
    fun `shared mode is left when only one charger is still plugged in`() {
        updateAt(CONTROL_START, idle = true)
        updateAt(CONTROL_START.plusSeconds(120), idle = true)

        val mode = tracker.update(
            CONTROL_START.plusSeconds(130),
            prio,
            setOf(other),
            snapshots(prioPower = 0.0),
            prioLimitSet,
        )

        assertEquals(AllocationMode.PRIORITIZED, mode)
    }

    @Test
    @Story("Wechsel der Anzahl")
    fun `a change of the eligible count restarts the idle timer from zero`() {
        updateAt(CONTROL_START, idle = true)
        tracker.update(CONTROL_START.plusSeconds(60), prio, setOf(prio), snapshots(prioPower = 0.0), prioLimitSet)

        val reconnected = updateAt(CONTROL_START.plusSeconds(90), idle = true)
        val beforeFreshTwoMinutes = updateAt(CONTROL_START.plusSeconds(90 + 119), idle = true)
        val afterFreshTwoMinutes = updateAt(CONTROL_START.plusSeconds(90 + 120), idle = true)

        assertEquals(AllocationMode.PRIORITIZED, reconnected)
        assertEquals(AllocationMode.PRIORITIZED, beforeFreshTwoMinutes)
        assertEquals(AllocationMode.SHARED, afterFreshTwoMinutes)
    }

    @Test
    @Story("Wechsel der Anzahl")
    fun `no eligible charger keeps the prioritized mode`() {
        updateAt(CONTROL_START, idle = true)

        val mode = tracker.update(CONTROL_START.plusSeconds(300), prio, emptySet(), snapshots(prioPower = 0.0), prioLimitSet)

        assertEquals(AllocationMode.PRIORITIZED, mode)
    }

    private fun updateAt(
        now: Instant,
        idle: Boolean,
        prioPower: Double = if (idle) 0.0 else 500.0,
        otherPower: Double = 0.0,
    ): AllocationMode = tracker.update(
        now = now,
        priorityCharger = prio,
        eligible = bothEligible,
        snapshots = snapshots(prioPower, otherPower),
        lastAppliedLimits = prioLimitSet,
    )

    private fun snapshots(prioPower: Double, otherPower: Double = 0.0): List<ChargerSnapshot> = listOf(
        chargerSnapshot(prio, power = prioPower),
        chargerSnapshot(other, power = otherPower),
    )
}
