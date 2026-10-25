package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.CurrentAllocator
import de.puetsch.easeeocpp.domain.EligibilityPolicy
import de.puetsch.easeeocpp.domain.MutableClock
import de.puetsch.easeeocpp.domain.PauseHysteresis
import de.puetsch.easeeocpp.domain.PhaseVoltageEvaluator
import de.puetsch.easeeocpp.domain.SurplusCalculator
import de.puetsch.easeeocpp.domain.model.ChargeSettings
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import de.puetsch.easeeocpp.domain.model.PhaseMode
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Epic("Regelung")
@Feature("Regelzyklus")
class ControlCycleTest {

    private val control = ControlProperties()
    private val properties = AppProperties(control = control)
    private val clock = MutableClock(CONTROL_START)
    private val cycle = ControlCycle(
        surplusCalculator = SurplusCalculator(control),
        currentAllocator = CurrentAllocator(control),
        eligibilityPolicy = EligibilityPolicy(control),
        phaseVoltageEvaluator = PhaseVoltageEvaluator(),
        pauseHysteresis = PauseHysteresis(clock, control),
        properties = properties,
    )

    @Test
    @Story("Überschussmodus")
    fun `surplus mode derives the current from charger power and target phases`() {
        val evaluation = cycle.evaluate(
            readings(surplusCharge = true, pvPower = 5000.0),
            listOf(chargerSnapshot(MULTONNEN_ID, power = 5000.0), chargerSnapshot(EINGANGSSEITE_ID)),
            CONTROL_START,
            emptyMap(),
        )

        assertEquals(PhaseMode.THREE, evaluation.decision.phaseMode)
        assertEquals(7, evaluation.decision.allocation.totalCurrent)
        assertEquals(7, evaluation.decision.allocation.limits.first { it.chargerId == MULTONNEN_ID }.current)
        assertEquals(0, evaluation.decision.allocation.limits.first { it.chargerId == EINGANGSSEITE_ID }.current)
        assertTrue(evaluation.decision.surplus!!.enoughPvPower)
        assertEquals(5000.0, evaluation.inputs.evPower, 0.0)
    }

    @Test
    @Story("Mindeststrom")
    fun `surplus below the pv threshold is raised to the minimum current`() {
        val evaluation = cycle.evaluate(
            readings(surplusCharge = true, pvPower = 100.0),
            listOf(chargerSnapshot(MULTONNEN_ID), chargerSnapshot(EINGANGSSEITE_ID)),
            CONTROL_START,
            emptyMap(),
        )

        assertFalse(evaluation.decision.surplus!!.enoughPvPower)
        assertEquals(6, evaluation.decision.allocation.totalCurrent)
        assertTrue(evaluation.decision.reason.contains("auf Mindeststrom angehoben"))
    }

    @Test
    @Story("Festmodus")
    fun `fixed mode uses the target current and target phases without surplus result`() {
        val evaluation = cycle.evaluate(
            readings(surplusCharge = false, fixedCurrent = 10, fixedPhases = PhaseMode.ONE),
            listOf(chargerSnapshot(MULTONNEN_ID), chargerSnapshot(EINGANGSSEITE_ID)),
            CONTROL_START,
            emptyMap(),
        )

        assertNull(evaluation.decision.surplus)
        assertEquals(PhaseMode.ONE, evaluation.decision.phaseMode)
        assertEquals(10, evaluation.decision.allocation.limits.first { it.chargerId == MULTONNEN_ID }.current)
    }

    @Test
    @Story("Mindeststrom")
    fun `minimum current also applies in fixed mode to the total current`() {
        val evaluation = cycle.evaluate(
            readings(surplusCharge = false, fixedCurrent = 3),
            listOf(chargerSnapshot(MULTONNEN_ID), chargerSnapshot(EINGANGSSEITE_ID)),
            CONTROL_START,
            emptyMap(),
        )

        assertEquals(6, evaluation.decision.allocation.totalCurrent)
        assertTrue(evaluation.decision.reason.contains("auf Mindeststrom angehoben"))
    }

    @Test
    @Story("Eligibility")
    fun `unplugged priority charger hands the whole current to the other charger`() {
        val evaluation = cycle.evaluate(
            readings(surplusCharge = false, fixedCurrent = 10, fixedPhases = PhaseMode.ONE),
            listOf(
                chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.AVAILABLE),
                chargerSnapshot(EINGANGSSEITE_ID),
            ),
            CONTROL_START,
            emptyMap(),
        )

        assertEquals(0, evaluation.decision.allocation.limits.first { it.chargerId == MULTONNEN_ID }.current)
        assertEquals(10, evaluation.decision.allocation.limits.first { it.chargerId == EINGANGSSEITE_ID }.current)
    }

    @Test
    @Story("Prio-Wechsel")
    fun `priority charger switch takes effect only after the pause delay`() {
        val snapshots = listOf(chargerSnapshot(MULTONNEN_ID), chargerSnapshot(EINGANGSSEITE_ID))
        cycle.evaluate(
            readings(surplusCharge = false, fixedCurrent = 10, priority = MULTONNEN_ID),
            snapshots,
            CONTROL_START,
            emptyMap(),
        )
        val switchRequest = readings(surplusCharge = false, fixedCurrent = 10, priority = EINGANGSSEITE_ID)
        val requestTime = CONTROL_START.plusSeconds(10)

        val beforeDelay = cycle.evaluate(switchRequest, snapshots, requestTime, emptyMap())
        val afterDelay = cycle.evaluate(switchRequest, snapshots, requestTime.plus(control.pauseDelay), emptyMap())

        assertEquals(10, beforeDelay.decision.allocation.limits.first { it.chargerId == MULTONNEN_ID }.current)
        assertEquals(10, afterDelay.decision.allocation.limits.first { it.chargerId == EINGANGSSEITE_ID }.current)
    }

    @Test
    @Story("Phasenspannung")
    fun `phase with highest voltage is taken from the priority charger snapshot`() {
        val evaluation = cycle.evaluate(
            readings(surplusCharge = false, fixedCurrent = 10),
            listOf(
                chargerSnapshot(
                    MULTONNEN_ID,
                    voltages = Triple(230.0, 230.0, 230.0),
                    currents = Triple(10.0, 0.0, 0.0),
                ),
                chargerSnapshot(EINGANGSSEITE_ID),
            ),
            CONTROL_START,
            emptyMap(),
        )

        assertEquals(1, evaluation.decision.phaseWithHighestVoltage)
    }

    @Test
    @Story("Ein Auto")
    fun `only one plugged in charger gets the whole current whatever the priority says`() {
        val evaluation = cycle.evaluate(
            readings(surplusCharge = false, fixedCurrent = 20, fixedPhases = PhaseMode.ONE, priority = MULTONNEN_ID),
            listOf(
                chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.AVAILABLE),
                chargerSnapshot(EINGANGSSEITE_ID),
            ),
            CONTROL_START,
            emptyMap(),
        )

        assertEquals(SINGLE_CHARGER_MODE, evaluation.decision.allocationMode)
        assertEquals(20, evaluation.decision.allocation.limits.first { it.chargerId == EINGANGSSEITE_ID }.current)
        assertTrue(evaluation.decision.reason.contains("Nur $EINGANGSSEITE_ID angesteckt – bekommt allen Strom"))
    }

    @Test
    @Story("Ein Auto")
    fun `one plugged in charger is capped at 16 A in three phases`() {
        val evaluation = cycle.evaluate(
            readings(surplusCharge = false, fixedCurrent = 32, fixedPhases = PhaseMode.THREE, priority = MULTONNEN_ID),
            listOf(chargerSnapshot(MULTONNEN_ID), chargerSnapshot(EINGANGSSEITE_ID, status = ChargerStatus.AVAILABLE)),
            CONTROL_START,
            emptyMap(),
        )

        assertEquals(16, evaluation.decision.allocation.limits.first { it.chargerId == MULTONNEN_ID }.current)
        assertEquals(SINGLE_CHARGER_MODE, evaluation.decision.allocationMode)
    }

    @Test
    @Story("Verteilungsmodus")
    fun `two plugged in chargers start prioritized`() {
        val evaluation = cycle.evaluate(
            readings(surplusCharge = false, fixedCurrent = 20, fixedPhases = PhaseMode.ONE),
            listOf(chargerSnapshot(MULTONNEN_ID), chargerSnapshot(EINGANGSSEITE_ID)),
            CONTROL_START,
            emptyMap(),
        )

        assertEquals(AllocationMode.PRIORITIZED.name, evaluation.decision.allocationMode)
        assertEquals(20, evaluation.decision.allocation.limits.first { it.chargerId == MULTONNEN_ID }.current)
        assertEquals(0, evaluation.decision.allocation.limits.first { it.chargerId == EINGANGSSEITE_ID }.current)
        assertTrue(evaluation.decision.reason.contains("beide angesteckt, Prio-Charger zuerst"))
    }

    @Test
    @Story("Verteilungsmodus")
    fun `no plugged in charger yields no allocation mode and zero limits`() {
        val evaluation = cycle.evaluate(
            readings(surplusCharge = false, fixedCurrent = 10, fixedPhases = PhaseMode.ONE),
            listOf(
                chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.AVAILABLE),
                chargerSnapshot(EINGANGSSEITE_ID, status = ChargerStatus.AVAILABLE),
            ),
            CONTROL_START,
            emptyMap(),
        )

        assertNull(evaluation.decision.allocationMode)
        assertEquals(0, evaluation.decision.allocation.totalCurrent)
        assertTrue(evaluation.decision.reason.contains("kein Charger angesteckt"))
    }

    @Test
    @Story("Verteilungsmodus")
    fun `idle priority charger with a set limit switches to shared after two minutes`() {
        val snapshots = listOf(chargerSnapshot(MULTONNEN_ID, power = 0.0), chargerSnapshot(EINGANGSSEITE_ID))
        val lastLimits = mapOf(MULTONNEN_ID to 10)
        cycle.evaluate(fixedReadings(priority = MULTONNEN_ID), snapshots, CONTROL_START, lastLimits)

        val beforeTwoMinutes = cycle.evaluate(
            fixedReadings(priority = MULTONNEN_ID), snapshots, CONTROL_START.plusSeconds(119), lastLimits,
        )
        val afterTwoMinutes = cycle.evaluate(
            fixedReadings(priority = MULTONNEN_ID), snapshots, CONTROL_START.plusSeconds(120), lastLimits,
        )

        assertEquals(AllocationMode.PRIORITIZED.name, beforeTwoMinutes.decision.allocationMode)
        assertEquals(AllocationMode.SHARED.name, afterTwoMinutes.decision.allocationMode)
        assertEquals(10, afterTwoMinutes.decision.allocation.limits.first { it.chargerId == MULTONNEN_ID }.current)
        assertEquals(10, afterTwoMinutes.decision.allocation.limits.first { it.chargerId == EINGANGSSEITE_ID }.current)
        assertTrue(
            afterTwoMinutes.decision.reason.contains(
                "Prio-Charger zieht seit 2 min nichts – beide Charger bekommen Strom",
            ),
        )
    }

    @Test
    @Story("Verteilungsmodus")
    fun `shared mode caps each charger at the phase limit and keeps the circuit limit per charger`() {
        val snapshots = listOf(chargerSnapshot(MULTONNEN_ID, power = 0.0), chargerSnapshot(EINGANGSSEITE_ID))
        val lastLimits = mapOf(MULTONNEN_ID to 16)
        val settings = readings(surplusCharge = false, fixedCurrent = 32, fixedPhases = PhaseMode.THREE)
        cycle.evaluate(settings, snapshots, CONTROL_START, lastLimits)

        val shared = cycle.evaluate(settings, snapshots, CONTROL_START.plusSeconds(120), lastLimits)

        assertEquals(16, shared.decision.allocation.limits.first { it.chargerId == MULTONNEN_ID }.current)
        assertEquals(16, shared.decision.allocation.limits.first { it.chargerId == EINGANGSSEITE_ID }.current)
    }

    @Test
    @Story("Verteilungsmodus")
    fun `shared mode returns to prioritized once both chargers draw power`() {
        val lastLimits = mapOf(MULTONNEN_ID to 10)
        cycle.evaluate(fixedReadings(priority = MULTONNEN_ID), idleSnapshots(), CONTROL_START, lastLimits)
        cycle.evaluate(fixedReadings(priority = MULTONNEN_ID), idleSnapshots(), CONTROL_START.plusSeconds(120), lastLimits)

        val bothCharging = cycle.evaluate(
            fixedReadings(priority = MULTONNEN_ID),
            listOf(chargerSnapshot(MULTONNEN_ID, power = 500.0), chargerSnapshot(EINGANGSSEITE_ID, power = 500.0)),
            CONTROL_START.plusSeconds(130),
            lastLimits,
        )

        assertEquals(AllocationMode.PRIORITIZED.name, bothCharging.decision.allocationMode)
    }

    private fun idleSnapshots(): List<ChargerSnapshot> =
        listOf(chargerSnapshot(MULTONNEN_ID, power = 0.0), chargerSnapshot(EINGANGSSEITE_ID))

    private fun fixedReadings(priority: ChargerId): HaReadings =
        readings(surplusCharge = false, fixedCurrent = 10, fixedPhases = PhaseMode.ONE, priority = priority)

    private fun readings(
        surplusCharge: Boolean,
        pvPower: Double = 5000.0,
        fixedCurrent: Int = 16,
        fixedPhases: PhaseMode = PhaseMode.THREE,
        priority: String = MULTONNEN_ID,
    ): HaReadings = HaReadings(
        pvPower = pvPower,
        gridPower = 0.0,
        batteryPower = 0.0,
        securityBuffer = 0.0,
        preferCarOverBattery = false,
        settings = ChargeSettings(
            surplusChargeEnabled = surplusCharge,
            surplusPhaseMode = PhaseMode.THREE,
            fixedPhaseMode = fixedPhases,
            fixedCurrent = fixedCurrent,
            minimumCurrent = control.minChargeCurrent,
            priorityCharger = priority,
        ),
    )
}
