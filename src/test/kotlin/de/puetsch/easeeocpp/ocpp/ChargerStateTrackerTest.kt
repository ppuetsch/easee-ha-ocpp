package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import de.puetsch.easeeocpp.ocpp.messages.MeterValue
import de.puetsch.easeeocpp.ocpp.messages.SampledValue
import io.mockk.every
import io.mockk.mockk
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

private const val CHARGER_ID = "EHDFQLBX"

@Epic("OCPP")
@Feature("ChargerStateTracker")
class ChargerStateTrackerTest {
    private val clock = MutableTestClock()
    private val settings = AppProperties()
    private val sessions = OcppSessionRegistry()
    private lateinit var tracker: ChargerStateTracker

    @BeforeEach
    fun createTracker() {
        tracker = ChargerStateTracker(settings, sessions, clock)
    }

    @Test
    @Story("Nicht verbunden")
    fun unconnectedChargerIsUnavailableWithZeroPower() {
        val snapshot = tracker.snapshot(CHARGER_ID)

        assertFalse(snapshot.connected)
        assertEquals(ChargerStatus.UNAVAILABLE, snapshot.status)
        assertEquals(0.0, snapshot.power)
    }

    @Test
    @Story("Nicht verbunden")
    fun snapshotsCoverAllConfiguredChargers() {
        val ids = tracker.snapshots().map { it.chargerId }

        assertEquals(settings.chargers.map { it.id }, ids)
    }

    @Test
    @Story("Status")
    fun statusNotificationUpdatesStatusOfConnectedCharger() {
        connect(CHARGER_ID)
        tracker.onStatusChanged(CHARGER_ID, ChargerStatus.CHARGING)

        val snapshot = tracker.snapshot(CHARGER_ID)

        assertTrue(snapshot.connected)
        assertEquals(ChargerStatus.CHARGING, snapshot.status)
    }

    @Test
    @Story("Status")
    fun statusSinceIsTimeOfLastStatusChange() {
        connect(CHARGER_ID)
        tracker.onStatusChanged(CHARGER_ID, ChargerStatus.PREPARING)
        clock.advance(Duration.ofMinutes(5))
        tracker.onStatusChanged(CHARGER_ID, ChargerStatus.CHARGING)
        clock.advance(Duration.ofMinutes(1))
        tracker.onStatusChanged(CHARGER_ID, ChargerStatus.CHARGING)

        assertEquals(TEST_START_INSTANT.plusSeconds(300), tracker.snapshot(CHARGER_ID).statusSince)
    }

    @Test
    @Story("Messwerte")
    fun freshMeterValuesProvidePowerAndPhases() {
        connect(CHARGER_ID)
        tracker.onMeterValues(CHARGER_ID, meterValuesWithPowerKilowatt("3.5"))

        val snapshot = tracker.snapshot(CHARGER_ID)

        assertEquals(3500.0, snapshot.power)
        assertEquals(16.0, snapshot.currentL1)
        assertEquals(231.0, snapshot.voltageL1)
        assertEquals(TEST_START_INSTANT, snapshot.lastMeterValueAt)
    }

    @Test
    @Story("Messwerte")
    fun staleMeterValuesMeanZeroPowerAndNoPhaseData() {
        connect(CHARGER_ID)
        tracker.onMeterValues(CHARGER_ID, meterValuesWithPowerKilowatt("3.5"))
        clock.advance(settings.ocpp.meterValueMaxAge.plusSeconds(1))

        val snapshot = tracker.snapshot(CHARGER_ID)

        assertEquals(0.0, snapshot.power)
        assertEquals(null, snapshot.currentL1)
    }

    @Test
    @Story("Messwerte")
    fun chargerWithoutMeterValuesHasZeroPower() {
        connect(CHARGER_ID)

        assertEquals(0.0, tracker.snapshot(CHARGER_ID).power)
    }

    @Test
    @Story("Trennung")
    fun disconnectKeepsLastStatusAndReadingsButPowerIsZero() {
        val session = connect(CHARGER_ID)
        tracker.onStatusChanged(CHARGER_ID, ChargerStatus.CHARGING)
        tracker.onMeterValues(CHARGER_ID, meterValuesWithPowerKilowatt("3.5"))

        sessions.unregister(session)

        val snapshot = tracker.snapshot(CHARGER_ID)
        assertFalse(snapshot.connected)
        assertEquals(ChargerStatus.CHARGING, snapshot.status)
        assertEquals(0.0, snapshot.power)
        assertEquals(TEST_START_INSTANT, snapshot.lastMeterValueAt)
    }

    @Test
    @Story("Trennung")
    fun reconnectRestoresPowerFromFreshReading() {
        val session = connect(CHARGER_ID)
        tracker.onMeterValues(CHARGER_ID, meterValuesWithPowerKilowatt("3.5"))
        sessions.unregister(session)
        connect(CHARGER_ID)

        assertEquals(3500.0, tracker.snapshot(CHARGER_ID).power)
    }

    private fun connect(chargerId: String): OcppChargerSession {
        val session = mockk<OcppChargerSession>(relaxed = true)
        every { session.chargePointId } returns chargerId
        sessions.register(session)
        return session
    }

    private fun meterValuesWithPowerKilowatt(kilowatt: String): List<MeterValue> = listOf(
        MeterValue(
            timestamp = TEST_START_INSTANT.toString(),
            sampledValue = listOf(
                SampledValue(kilowatt, MEASURAND_POWER_ACTIVE_IMPORT, unit = UNIT_KILOWATT),
                SampledValue("16", MEASURAND_CURRENT_IMPORT, phase = "L1"),
                SampledValue("231", MEASURAND_VOLTAGE, phase = "L1"),
            ),
        ),
    )
}
