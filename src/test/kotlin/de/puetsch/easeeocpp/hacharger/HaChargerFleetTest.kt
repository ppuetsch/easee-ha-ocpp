package de.puetsch.easeeocpp.hacharger

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.config.HaProperties
import de.puetsch.easeeocpp.domain.MutableClock
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import de.puetsch.easeeocpp.domain.model.EntityId
import de.puetsch.easeeocpp.ports.HaEntityState
import de.puetsch.easeeocpp.ports.HomeAssistantActions
import de.puetsch.easeeocpp.ports.HomeAssistantStates
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MULTONNEN = "EHDFQLBX"
private const val EINGANGSSEITE = "EH2NSSVK"
private val NOW: Instant = Instant.parse("2026-11-09T12:00:00Z")
private val STATUS_CHANGED_AT: Instant = NOW.minusSeconds(300)

private class RecordedStates : HomeAssistantStates {
    private val entries = mutableMapOf<EntityId, HaEntityState>()

    override fun isConnected(): Boolean = true

    override fun state(entityId: EntityId): HaEntityState? = entries[entityId]

    fun set(entityId: EntityId, value: String, reportedAt: Instant = NOW, changedAt: Instant = reportedAt) {
        entries[entityId] = HaEntityState(entityId, value, reportedAt, changedAt)
    }
}

private class RecordedActions(private val answer: Boolean = true) : HomeAssistantActions {
    val calls = mutableListOf<Triple<String, String, Map<String, Any?>>>()

    override fun callService(domain: String, service: String, data: Map<String, Any?>): Boolean {
        calls += Triple(domain, service, data)
        return answer
    }
}

@Epic("Charger über Home Assistant")
@Feature("HaChargerFleet")
class HaChargerFleetTest {
    private val states = RecordedStates()
    private val clock = MutableClock(NOW)

    private fun fleet(actions: RecordedActions = RecordedActions()) = HaChargerFleet(states, actions, AppProperties(), clock)

    private fun chargingMultonnen(reportedAt: Instant = NOW) {
        states.set("binary_sensor.mulltonnen_ocpp_verbunden", "on")
        states.set("sensor.mulltonnen_ocpp_status", "suspendedev", reportedAt, STATUS_CHANGED_AT)
        states.set("sensor.mulltonnen_ocpp_leistung", "11040.0", reportedAt)
        listOf("16.1", "16.0", "15.9").forEachIndexed { index, value ->
            states.set("sensor.mulltonnen_ocpp_strom_l${index + 1}", value, reportedAt)
        }
        listOf("233.5", "230.8", "224.4").forEachIndexed { index, value ->
            states.set("sensor.mulltonnen_ocpp_spannung_l${index + 1}", value, reportedAt)
        }
    }

    @Test
    fun `Snapshot kommt aus den OCPP-Entities der Integration`() {
        chargingMultonnen()

        val snapshot = fleet().snapshots().first { it.chargerId == MULTONNEN }

        assertTrue(snapshot.connected)
        assertEquals(ChargerStatus.SUSPENDED_EV, snapshot.status)
        assertEquals(STATUS_CHANGED_AT, snapshot.statusSince)
        assertEquals(11040.0, snapshot.power)
        assertEquals(16.1, snapshot.currentL1)
        assertEquals(224.4, snapshot.voltageL3)
        assertEquals(NOW, snapshot.lastMeterValueAt)
    }

    @Test
    fun `Veraltete Messwerte zählen nicht, die Leistung ist dann 0`() {
        chargingMultonnen(reportedAt = NOW.minusSeconds(91))

        val snapshot = fleet().snapshots().first { it.chargerId == MULTONNEN }

        assertEquals(0.0, snapshot.power)
        assertNull(snapshot.currentL1)
        assertTrue(snapshot.connected)
    }

    @Test
    fun `Ein Charger ohne Entities ist getrennt und nicht verfügbar`() {
        val snapshot = fleet().snapshots().first { it.chargerId == EINGANGSSEITE }

        assertFalse(snapshot.connected)
        assertEquals(ChargerStatus.UNAVAILABLE, snapshot.status)
        assertEquals(0.0, snapshot.power)
    }

    @Test
    fun `Limit geht als befristetes Limit an die Integration`() {
        val actions = RecordedActions()

        val result = fleet(actions).applyLimit(MULTONNEN, 10)

        assertTrue(result.success)
        assertEquals(
            Triple("easee", "ocpp_set_current_limit", mapOf("serial" to MULTONNEN, "current" to 10, "duration" to 120L)),
            actions.calls.single(),
        )
    }

    @Test
    fun `Ein abgelehnter Dienstaufruf ist ein Fehlschlag`() {
        val result = fleet(RecordedActions(answer = false)).applyLimit(MULTONNEN, 6)

        assertFalse(result.success)
    }

    @Test
    fun `Messwerte werden nur für verbundene Charger mit Fahrzeug angefordert`() {
        chargingMultonnen()
        val actions = RecordedActions()

        fleet(actions).requestFreshMeasurements()

        assertEquals(
            listOf(Triple("easee", "ocpp_trigger_message", mapOf<String, Any?>("serial" to MULTONNEN, "message" to "MeterValues"))),
            actions.calls,
        )
    }

    @Test
    fun `Limit muss länger gelten als das Erneuerungsintervall`() {
        val tooShort = AppProperties(
            ha = HaProperties(chargerLimitValidity = Duration.ofSeconds(60)),
            control = ControlProperties(profileRefreshInterval = Duration.ofSeconds(60)),
        )

        assertThrows<IllegalArgumentException> { HaChargerFleet(states, RecordedActions(), tooShort, clock) }
    }

    @Test
    fun `Status der Integration wird auf die OCPP-Status abgebildet`() {
        assertEquals(ChargerStatus.SUSPENDED_EVSE, chargerStatusOf("suspendedevse"))
        assertEquals(ChargerStatus.CHARGING, chargerStatusOf("charging"))
        assertNull(chargerStatusOf("unknown"))
    }
}
