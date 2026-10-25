package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

private const val CHARGING_ID = "EH-CHARGING"
private const val IDLE_ID = "EH-IDLE"

@Epic("OCPP")
@Feature("Frische Messwerte")
class OcppChargerFleetTest {
    private val charging = snapshot(CHARGING_ID, connected = true, status = ChargerStatus.CHARGING)
    private val idle = snapshot(IDLE_ID, connected = true, status = ChargerStatus.AVAILABLE)
    private val tracker: ChargerStateTracker = mockk { every { snapshots() } returns listOf(charging, idle) }
    private val chargingSession: OcppChargerSession = mockk(relaxed = true)
    private val idleSession: OcppChargerSession = mockk(relaxed = true)
    private val sessions: OcppSessionRegistry = mockk {
        every { find(CHARGING_ID) } returns chargingSession
        every { find(IDLE_ID) } returns idleSession
    }
    private val fleet = OcppChargerFleet(mockk(relaxed = true), tracker, sessions)

    @Test
    fun `fordert Messwerte nur fuer Charger mit angestecktem Auto an`() {
        every { chargingSession.call(any(), any()) } returns CompletableFuture.completedFuture(OcppResponse.Timeout)

        fleet.requestFreshMeasurements()

        verify(exactly = 1) { chargingSession.call(OcppActions.TRIGGER_MESSAGE, any()) }
        verify(exactly = 0) { idleSession.call(any(), any()) }
    }

    @Test
    fun `getrennter Charger bekommt keine Anfrage`() {
        every { tracker.snapshots() } returns listOf(snapshot(CHARGING_ID, connected = false, status = ChargerStatus.CHARGING))

        fleet.requestFreshMeasurements()

        verify(exactly = 0) { chargingSession.call(any(), any()) }
    }

    private fun snapshot(chargerId: String, connected: Boolean, status: ChargerStatus) = ChargerSnapshot(
        chargerId = chargerId, connected = connected, status = status, power = 0.0,
        currentL1 = null, currentL2 = null, currentL3 = null,
        voltageL1 = null, voltageL2 = null, voltageL3 = null,
        lastMeterValueAt = null, statusSince = null,
    )
}
