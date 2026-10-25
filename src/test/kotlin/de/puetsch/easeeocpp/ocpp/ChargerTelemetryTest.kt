package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.domain.model.ChargerStatus
import de.puetsch.easeeocpp.ocpp.messages.MeterValue
import de.puetsch.easeeocpp.ocpp.messages.SampledValue
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration

private fun sampled(measurand: String, value: String, unit: String? = null, phase: String? = null) =
    SampledValue(value = value, measurand = measurand, phase = phase, unit = unit)

private fun meterValue(vararg samples: SampledValue) = MeterValue(TEST_START_INSTANT.toString(), samples.toList())

@Epic("OCPP")
@Feature("Telemetrie")
class ChargerTelemetryTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource("Available,AVAILABLE", "SuspendedEV,SUSPENDED_EV", "SuspendedEVSE,SUSPENDED_EVSE", "Faulted,FAULTED")
    @Story("Status-Mapping")
    fun mapsOcppStatusNames(ocppName: String, expected: ChargerStatus) {
        assertEquals(expected, ChargerStatusMapping.fromOcpp(ocppName))
    }

    @Test
    @Story("Status-Mapping")
    fun unknownStatusMapsToNull() {
        assertNull(ChargerStatusMapping.fromOcpp("Dancing"))
    }

    @Test
    @Story("Leistung")
    fun kilowattIsConvertedToWatt() {
        val reading = MeterReadingParser.parse(
            listOf(meterValue(sampled(MEASURAND_POWER_ACTIVE_IMPORT, "3.5", unit = UNIT_KILOWATT))),
        )

        assertEquals(3500.0, reading.powerWatt)
    }

    @Test
    @Story("Leistung")
    fun missingUnitMeansWatt() {
        val reading = MeterReadingParser.parse(
            listOf(meterValue(sampled(MEASURAND_POWER_ACTIVE_IMPORT, "1800"))),
        )

        assertEquals(1800.0, reading.powerWatt)
    }

    @Test
    @Story("Leistung")
    fun powerSumsPhasesWhenNoTotalIsReported() {
        val reading = MeterReadingParser.parse(
            listOf(
                meterValue(
                    sampled(MEASURAND_POWER_ACTIVE_IMPORT, "1000", unit = UNIT_WATT, phase = "L1"),
                    sampled(MEASURAND_POWER_ACTIVE_IMPORT, "0.5", unit = UNIT_KILOWATT, phase = "L2"),
                ),
            ),
        )

        assertEquals(1500.0, reading.powerWatt)
    }

    @Test
    @Story("Leistung")
    fun unknownUnitYieldsNoPower() {
        val reading = MeterReadingParser.parse(
            listOf(meterValue(sampled(MEASURAND_POWER_ACTIVE_IMPORT, "5", unit = "BTU"))),
        )

        assertNull(reading.powerWatt)
    }

    @Test
    @Story("Phasen")
    fun currentsAndVoltagesArePerPhaseIncludingNeutralSuffix() {
        val reading = MeterReadingParser.parse(
            listOf(
                meterValue(
                    sampled(MEASURAND_CURRENT_IMPORT, "16.1", phase = "L1"),
                    sampled(MEASURAND_CURRENT_IMPORT, "15.9", phase = "L2-N"),
                    sampled(MEASURAND_CURRENT_IMPORT, "0.2", phase = "L3"),
                    sampled(MEASURAND_VOLTAGE, "231.5", phase = "L1"),
                    sampled(MEASURAND_VOLTAGE, "230.0", phase = "L3"),
                ),
            ),
        )

        assertEquals(PhaseValues(l1 = 16.1, l2 = 15.9, l3 = 0.2), reading.currents)
        assertEquals(PhaseValues(l1 = 231.5, l2 = null, l3 = 230.0), reading.voltages)
    }

    @Test
    @Story("Phasen")
    fun samplesWithoutMeasurandOrWithGarbageValueAreIgnored() {
        val reading = MeterReadingParser.parse(
            listOf(
                meterValue(
                    SampledValue(value = "12", measurand = null),
                    sampled(MEASURAND_CURRENT_IMPORT, "kaputt", phase = "L1"),
                ),
            ),
        )

        assertEquals(PhaseValues.NONE, reading.currents)
    }

    @Test
    @Story("Frische")
    fun readingOlderThanMaxAgeIsNotFresh() {
        val telemetry = ChargerTelemetry()
        telemetry.recordMeterValues(MeterReadingParser.parse(emptyList()), TEST_START_INSTANT)

        val stillFresh = telemetry.freshReading(TEST_START_INSTANT.plusSeconds(30), Duration.ofSeconds(30))
        val stale = telemetry.freshReading(TEST_START_INSTANT.plusSeconds(31), Duration.ofSeconds(30))

        assertEquals(false, stillFresh == null)
        assertNull(stale)
    }

    @Test
    @Story("Status")
    fun statusSinceChangesOnlyWhenStatusChanges() {
        val telemetry = ChargerTelemetry()
        telemetry.changeStatus(ChargerStatus.CHARGING, TEST_START_INSTANT)
        telemetry.changeStatus(ChargerStatus.CHARGING, TEST_START_INSTANT.plusSeconds(60))

        assertEquals(TEST_START_INSTANT, telemetry.statusSince)
    }
}
