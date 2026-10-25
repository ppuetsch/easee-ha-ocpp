package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.ocpp.messages.OcppRequestedMessages
import de.puetsch.easeeocpp.ocpp.messages.TriggerMessageRequest
import de.puetsch.easeeocpp.ports.ActuationResult
import de.puetsch.easeeocpp.ports.ChargerFleet
import org.springframework.stereotype.Component

@Component
class OcppChargerFleet(
    private val profiles: ChargingProfileService,
    private val tracker: ChargerStateTracker,
    private val sessions: OcppSessionRegistry,
) : ChargerFleet {
    override fun snapshots(): List<ChargerSnapshot> = tracker.snapshots()

    override fun applyLimit(chargerId: ChargerId, current: Ampere): ActuationResult =
        profiles.applyLimit(chargerId, current)

    /**
     * Der Charger meldet von sich aus nur alle 30 s Messwerte. Die Regelung vergleicht die Ladeleistung aber mit der
     * sekundenaktuellen Netzmessung; ohne frische Werte würde sie nach einer Stromänderung doppelt zählen und pendeln.
     */
    override fun requestFreshMeasurements() {
        tracker.snapshots()
            .filter { it.connected && it.status.hasVehiclePluggedIn() }
            .forEach { requestMeterValues(it.chargerId) }
    }

    private fun requestMeterValues(chargerId: ChargerId) {
        sessions.find(chargerId)?.call(
            OcppActions.TRIGGER_MESSAGE,
            TriggerMessageRequest(OcppRequestedMessages.METER_VALUES),
        )
    }
}
