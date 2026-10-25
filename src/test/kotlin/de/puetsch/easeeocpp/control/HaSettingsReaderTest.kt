package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.PhaseMode
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@Epic("Regelung")
@Feature("HA-Eingänge")
class HaSettingsReaderTest {

    @TempDir
    lateinit var tempDir: Path

    private val properties = AppProperties()
    private val states = FakeHomeAssistantStates()
    private val reader = HaSettingsReader(states, properties)
    private val entities = properties.ha.entities

    @BeforeEach
    fun setUp() {
        states.set(entities.pvPower, "5000.0")
        states.set(entities.gridPower, "120.0")
        states.set(entities.batteryPower, "-50.0")
        states.set(entities.securityBuffer, "200.0")
        states.set(entities.preferCarOverBattery, "on")
        states.set(entities.pvSurplusCharge, "on")
        states.set(entities.targetPhases, "3")
        states.set(entities.targetCurrent, "16.0")
        states.set(entities.minimumEvCurrent, "6.0")
        states.set(entities.priorityCharger, "Eingangsseite")
    }

    @Test
    @Story("Zahlen")
    fun `numeric strings with decimals are parsed to watts and amperes`() {
        val readings = completeReadings()

        assertEquals(5000.0, readings.pvPower, 0.0)
        assertEquals(-50.0, readings.batteryPower, 0.0)
        assertEquals(16, readings.settings.fixedCurrent)
        assertEquals(6, readings.settings.minimumCurrent)
    }

    @Test
    @Story("Phasen")
    fun `phase count string is mapped to phase mode`() {
        assertEquals(PhaseMode.THREE, completeReadings().settings.surplusPhaseMode)
    }

    @Test
    @Story("Phasen")
    fun `phase count with decimal suffix is accepted`() {
        states.set(entities.targetPhases, "1.0")

        assertEquals(PhaseMode.ONE, completeReadings().settings.fixedPhaseMode)
    }

    @Test
    @Story("Phasen")
    fun `phase count other than one or three is rejected`() {
        states.set(entities.targetPhases, "2")

        val failure = assertThrows(IllegalArgumentException::class.java) { reader.read() }

        assertEquals("phase mode unknown 2", failure.message)
    }

    @Test
    @Story("Unvollständige Eingänge")
    fun `unavailable input is reported as missing with its entity id`() {
        states.set(entities.gridPower, "unavailable")

        val result = reader.read()

        assertEquals(HaReadResult.MissingInput(entities.gridPower), result)
    }

    @Test
    @Story("Unvollständige Eingänge")
    fun `unknown input is reported as missing`() {
        states.set(entities.targetCurrent, "unknown")

        assertEquals(HaReadResult.MissingInput(entities.targetCurrent), reader.read())
    }

    @Test
    @Story("Unvollständige Eingänge")
    fun `absent input entity is reported as missing`() {
        val absentEntity = entities.preferCarOverBattery
        states.clear(absentEntity)

        assertEquals(HaReadResult.MissingInput(absentEntity), reader.read())
    }

    @Test
    @Story("Schalter")
    fun `input boolean on is true and anything else is false`() {
        assertEquals(true, completeReadings().preferCarOverBattery)

        states.set(entities.pvSurplusCharge, "off")

        assertEquals(false, completeReadings().settings.surplusChargeEnabled)
    }

    @Test
    @Story("Prio-Charger")
    fun `priority charger option name is mapped to the charger id`() {
        assertEquals(EINGANGSSEITE_ID, completeReadings().settings.priorityCharger)
    }

    @Test
    @Story("Prio-Charger")
    fun `unknown priority charger option is rejected`() {
        states.set(entities.priorityCharger, "Garage")

        val failure = assertThrows(IllegalArgumentException::class.java) { reader.read() }

        assertEquals("Unbekannter Prio-Charger: Garage", failure.message)
    }

    private fun completeReadings(): HaReadings {
        val result = reader.read()
        assertInstanceOf(HaReadResult.Complete::class.java, result)
        return (result as HaReadResult.Complete).readings
    }
}
