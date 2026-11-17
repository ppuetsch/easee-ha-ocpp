package de.puetsch.easeeocpp.hacharger

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.config.ChargerProperties
import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import de.puetsch.easeeocpp.domain.model.EntityId
import de.puetsch.easeeocpp.domain.model.Watt
import de.puetsch.easeeocpp.ports.ActuationResult
import de.puetsch.easeeocpp.ports.ChargerFleet
import de.puetsch.easeeocpp.ports.HaEntityState
import de.puetsch.easeeocpp.ports.HomeAssistantActions
import de.puetsch.easeeocpp.ports.HomeAssistantStates
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val INTEGRATION_DOMAIN = "easee_ocpp"
private const val SERVICE_SET_CURRENT_LIMIT = "set_current_limit"
private const val SERVICE_TRIGGER_MESSAGE = "trigger_message"
private const val MESSAGE_METER_VALUES = "MeterValues"
private const val STATE_ON = "on"
private const val NO_POWER_WATT: Watt = 0.0

/**
 * Steuert die Charger über die Integration „Easee Local OCPP“ (ppuetsch/easee-ocpp) in Home Assistant, die ihr
 * OCPP-Server ist.
 *
 * Fail-safe wie beim eigenen OCPP-Server: Jedes Limit gilt nur [AppProperties.ha] `chargerLimitValidity` lang
 * (Stack-Ebene 1). Erneuert der Service es nicht mehr, gilt am Charger wieder der OCPP-Maximalstrom der
 * Integration – der steht deshalb auf 0 A, und die Ladung hört auf.
 */
@Component
@ConditionalOnProperty(prefix = "easee.ocpp", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class HaChargerFleet(
    private val states: HomeAssistantStates,
    private val actions: HomeAssistantActions,
    private val settings: AppProperties,
    private val clock: Clock,
) : ChargerFleet {
    private val readingMaxAge: Duration = settings.ocpp.meterValueMaxAge
    private val limitValidity: Duration = settings.ha.chargerLimitValidity

    init {
        require(limitValidity > settings.control.profileRefreshInterval) {
            "easee.ha.charger-limit-validity ($limitValidity) muss länger sein als " +
                "easee.control.profile-refresh-interval (${settings.control.profileRefreshInterval}), " +
                "sonst läuft das Limit zwischen zwei Erneuerungen ab."
        }
    }

    override fun snapshots(): List<ChargerSnapshot> = settings.chargers.map(::snapshotOf)

    override fun applyLimit(chargerId: ChargerId, current: Ampere): ActuationResult {
        val data = mapOf("serial" to chargerId, "current" to current, "duration" to limitValidity.seconds)
        val accepted = actions.callService(INTEGRATION_DOMAIN, SERVICE_SET_CURRENT_LIMIT, data)
        val message = if (accepted) "Limit $current A über HA gesetzt" else "HA hat das Limit $current A nicht gesetzt"
        return ActuationResult(success = accepted, message = message)
    }

    /**
     * Der Charger meldet von sich aus nur alle 30 s Messwerte. Die Regelung vergleicht die Ladeleistung aber mit der
     * sekundenaktuellen Netzmessung; ohne frische Werte würde sie nach einer Stromänderung doppelt zählen und pendeln.
     */
    override fun requestFreshMeasurements() {
        snapshots()
            .filter { snapshot -> snapshot.connected && snapshot.status.hasVehiclePluggedIn() }
            .forEach { snapshot -> requestMeterValues(snapshot.chargerId) }
    }

    private fun requestMeterValues(chargerId: ChargerId) {
        actions.callService(
            INTEGRATION_DOMAIN,
            SERVICE_TRIGGER_MESSAGE,
            mapOf("serial" to chargerId, "message" to MESSAGE_METER_VALUES),
        )
    }

    private fun snapshotOf(charger: ChargerProperties): ChargerSnapshot {
        val entities = HaChargerEntities.forPrefix(charger.haEntityPrefix)
        val connected = states.state(entities.connected)?.state == STATE_ON
        val statusState = states.state(entities.status)?.takeIf { it.isUsable() }
        val lastMeasurementAt = newestReport(entities.measurementEntities())
        val readingIsFresh = connected && isFresh(lastMeasurementAt)
        fun measured(entityId: EntityId): Double? = if (readingIsFresh) numberOf(entityId) else null
        return ChargerSnapshot(
            chargerId = charger.id,
            connected = connected,
            status = statusState?.let { chargerStatusOf(it.state) } ?: ChargerStatus.UNAVAILABLE,
            power = measured(entities.power) ?: NO_POWER_WATT,
            currentL1 = measured(entities.currents[0]),
            currentL2 = measured(entities.currents[1]),
            currentL3 = measured(entities.currents[2]),
            voltageL1 = measured(entities.voltages[0]),
            voltageL2 = measured(entities.voltages[1]),
            voltageL3 = measured(entities.voltages[2]),
            lastMeterValueAt = lastMeasurementAt,
            statusSince = statusState?.lastChanged,
        )
    }

    private fun numberOf(entityId: EntityId): Double? =
        states.state(entityId)?.takeIf(HaEntityState::isUsable)?.state?.toDoubleOrNull()

    /** HA meldet unveränderte Werte nicht als state_changed; die Spannungen schwanken aber praktisch immer. */
    private fun newestReport(entityIds: List<EntityId>): Instant? =
        entityIds.mapNotNull { entityId -> states.state(entityId)?.lastReported }.maxOrNull()

    private fun isFresh(measuredAt: Instant?): Boolean =
        measuredAt != null && Duration.between(measuredAt, clock.instant()) <= readingMaxAge
}
