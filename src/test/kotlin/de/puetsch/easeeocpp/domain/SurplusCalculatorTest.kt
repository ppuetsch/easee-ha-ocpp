package de.puetsch.easeeocpp.domain

import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.model.PhaseMode
import de.puetsch.easeeocpp.domain.model.SurplusInputs
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@Epic("Regelung")
@Feature("Überschuss-Berechnung")
class SurplusCalculatorTest {

    private val calculator = SurplusCalculator(ControlProperties())

    @ParameterizedTest(name = "{0} W bei 1 Phase ergibt {1} A")
    @CsvSource(
        "1200, 0",
        "1296, 0",
        "1320, 6",
        "1435, 6",
        "1440, 6",
        "1560, 6",
        "1680, 7",
        "1800, 8",
        "7440, 31",
        "7560, 32",
        "7680, 32",
        "7800, 32",
        "7920, 32",
    )
    fun `single phase current is rounded half to even and clamped to 6 to 32 A`(targetPower: Double, expectedCurrent: Int) {
        val result = calculator.calculate(inputs(evPower = targetPower, phaseMode = PhaseMode.ONE))

        assertEquals(expectedCurrent, result.targetCurrent)
    }

    @ParameterizedTest(name = "{0} W bei 3 Phasen ergibt {1} A")
    @CsvSource(
        "3600, 0",
        "4200, 6",
        "4320, 6",
        "11520, 16",
        "11880, 16",
        "12600, 18",
        "30000, 32",
    )
    fun `three phase current uses 720 W per ampere`(targetPower: Double, expectedCurrent: Int) {
        val result = calculator.calculate(inputs(evPower = targetPower, phaseMode = PhaseMode.THREE))

        assertEquals(expectedCurrent, result.targetCurrent)
    }

    @Test
    fun `same power yields a higher current on one phase than on three phases`() {
        val onePhase = calculator.calculate(inputs(evPower = 4000.0, phaseMode = PhaseMode.ONE))
        val threePhases = calculator.calculate(inputs(evPower = 4000.0, phaseMode = PhaseMode.THREE))

        assertEquals(17, onePhase.targetCurrent)
        assertEquals(6, threePhases.targetCurrent)
    }

    @Test
    fun `pv power below minimum required yields zero target and no enough pv flag`() {
        val result = calculator.calculate(inputs(pvPower = 1639.0, evPower = 5000.0, securityBuffer = 100.0))

        assertFalse(result.enoughPvPower)
        assertEquals(0.0, result.targetPower)
        assertEquals(0, result.targetCurrent)
    }

    @Test
    fun `pv power exactly at minimum required is sufficient`() {
        val result = calculator.calculate(inputs(pvPower = 1640.0, evPower = 5000.0, securityBuffer = 100.0))

        assertTrue(result.enoughPvPower)
        assertEquals(1640.0, result.minRequiredPvPower)
    }

    @Test
    fun `minimum required pv power is 6 A times grid voltage plus buffer and house power`() {
        val result = calculator.calculate(inputs(securityBuffer = 250.0))

        assertEquals(1440.0 + 250.0 + 100.0, result.minRequiredPvPower)
    }

    @Test
    fun `battery power counts fully when car preference is on`() {
        val result = calculator.calculate(inputs(evPower = 1000.0, batteryPower = 500.0, preferCarOverBattery = true))

        assertEquals(1500.0, result.rawTargetPower)
        assertEquals(6, result.targetCurrent)
    }

    @Test
    fun `positive battery power is ignored when car preference is off`() {
        val result = calculator.calculate(inputs(evPower = 1000.0, batteryPower = 500.0, preferCarOverBattery = false))

        assertEquals(1000.0, result.rawTargetPower)
        assertEquals(0, result.targetCurrent)
    }

    @Test
    fun `discharging battery reduces surplus regardless of car preference`() {
        val preferred = calculator.calculate(inputs(evPower = 1000.0, batteryPower = -300.0, preferCarOverBattery = true))
        val notPreferred = calculator.calculate(inputs(evPower = 1000.0, batteryPower = -300.0, preferCarOverBattery = false))

        assertEquals(700.0, preferred.rawTargetPower)
        assertEquals(700.0, notPreferred.rawTargetPower)
    }

    @Test
    fun `grid import reduces surplus`() {
        val result = calculator.calculate(inputs(evPower = 2000.0, gridPower = 500.0))

        assertEquals(1500.0, result.rawTargetPower)
    }

    @Test
    fun `security buffer is subtracted from raw target power`() {
        val result = calculator.calculate(inputs(pvPower = 10000.0, evPower = 3000.0, securityBuffer = 500.0))

        assertEquals(3000.0, result.rawTargetPower)
        assertEquals(2500.0, result.targetPower)
        assertEquals(10, result.targetCurrent)
    }

    @Test
    fun `raw target power is clamped to zero and negative target yields zero current`() {
        val result = calculator.calculate(inputs(pvPower = 5000.0, evPower = 0.0, gridPower = 1000.0))

        assertEquals(0.0, result.rawTargetPower)
        assertEquals(0, result.targetCurrent)
    }

    @Test
    fun `target power can be negative when buffer exceeds raw surplus`() {
        val result = calculator.calculate(inputs(pvPower = 5000.0, evPower = 1000.0, securityBuffer = 1500.0))

        assertEquals(-500.0, result.targetPower)
        assertEquals(0, result.targetCurrent)
    }

    private fun inputs(
        pvPower: Double = 10000.0,
        gridPower: Double = 0.0,
        batteryPower: Double = 0.0,
        evPower: Double = 0.0,
        securityBuffer: Double = 0.0,
        preferCarOverBattery: Boolean = true,
        phaseMode: PhaseMode = PhaseMode.ONE,
    ): SurplusInputs = SurplusInputs(
        pvPower = pvPower,
        gridPower = gridPower,
        batteryPower = batteryPower,
        evPower = evPower,
        securityBuffer = securityBuffer,
        preferCarOverBattery = preferCarOverBattery,
        phaseMode = phaseMode,
    )
}
