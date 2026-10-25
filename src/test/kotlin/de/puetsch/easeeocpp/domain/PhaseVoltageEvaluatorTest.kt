package de.puetsch.easeeocpp.domain

import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@Epic("Regelung")
@Feature("Phase mit höchster Spannung")
class PhaseVoltageEvaluatorTest {

    private val evaluator = PhaseVoltageEvaluator()

    @Test
    fun `phase one wins only when strictly higher than both others`() {
        val snapshot = snapshot(voltages = listOf(235.0, 230.0, 230.0))

        assertEquals(1, evaluator.highestVoltagePhase(snapshot))
    }

    @Test
    fun `phase one tied with phase two falls back to phase two when it is higher than phase three`() {
        val snapshot = snapshot(voltages = listOf(230.0, 230.0, 225.0))

        assertEquals(2, evaluator.highestVoltagePhase(snapshot))
    }

    @Test
    fun `phase two wins over phase one when only phase two is higher`() {
        val snapshot = snapshot(voltages = listOf(230.0, 240.0, 230.0))

        assertEquals(2, evaluator.highestVoltagePhase(snapshot))
    }

    @Test
    fun `full tie resolves to phase three`() {
        val snapshot = snapshot(voltages = listOf(230.0, 230.0, 230.0))

        assertEquals(3, evaluator.highestVoltagePhase(snapshot))
    }

    @Test
    fun `half of the phase current is added to the voltage before comparing`() {
        val snapshot = snapshot(voltages = listOf(230.0, 228.0, 225.0), currents = listOf(0.0, 10.0, 0.0))

        assertEquals(2, evaluator.highestVoltagePhase(snapshot))
    }

    @Test
    fun `current can push a lower voltage phase above phase one`() {
        val snapshot = snapshot(voltages = listOf(230.0, 228.0, 228.0), currents = listOf(0.0, 8.0, 0.0))

        assertEquals(2, evaluator.highestVoltagePhase(snapshot))
    }

    @Test
    fun `missing voltage yields null`() {
        val snapshot = snapshot(voltages = listOf(230.0, null, 230.0))

        assertNull(evaluator.highestVoltagePhase(snapshot))
    }

    @Test
    fun `missing current yields null`() {
        val snapshot = snapshot(voltages = listOf(230.0, 230.0, 230.0), currents = listOf(null, 1.0, 1.0))

        assertNull(evaluator.highestVoltagePhase(snapshot))
    }

    private fun snapshot(
        voltages: List<Double?>,
        currents: List<Double?> = listOf(0.0, 0.0, 0.0),
    ): ChargerSnapshot = ChargerSnapshot(
        chargerId = "EHDFQLBX",
        connected = true,
        status = ChargerStatus.CHARGING,
        power = 0.0,
        currentL1 = currents[0],
        currentL2 = currents[1],
        currentL3 = currents[2],
        voltageL1 = voltages[0],
        voltageL2 = voltages[1],
        voltageL3 = voltages[2],
        lastMeterValueAt = null,
        statusSince = null,
    )
}
