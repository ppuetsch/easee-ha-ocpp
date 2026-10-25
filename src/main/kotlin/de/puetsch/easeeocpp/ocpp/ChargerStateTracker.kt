package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import de.puetsch.easeeocpp.ocpp.messages.MeterValue
import org.springframework.stereotype.Component
import java.time.Clock

/** Ohne frische Messwerte gilt die Ladeleistung als 0 W (kein Laden), nicht als unbekannt. */
private const val NO_POWER_WATT = 0.0

@Component
class ChargerStateTracker(
    private val settings: AppProperties,
    private val sessions: OcppSessionRegistry,
    private val clock: Clock,
) {
    private val telemetryByChargerId = mutableMapOf<ChargerId, ChargerTelemetry>()

    @Synchronized
    fun onStatusChanged(chargerId: ChargerId, status: ChargerStatus) {
        telemetryOf(chargerId).changeStatus(status, clock.instant())
    }

    @Synchronized
    fun onMeterValues(chargerId: ChargerId, meterValues: List<MeterValue>) {
        telemetryOf(chargerId).recordMeterValues(MeterReadingParser.parse(meterValues), clock.instant())
    }

    @Synchronized
    fun onDisconnected(chargerId: ChargerId) {
        val telemetry = telemetryOf(chargerId)
        telemetry.clearMeterValues()
        telemetry.changeStatus(ChargerStatus.UNAVAILABLE, clock.instant())
    }

    @Synchronized
    fun snapshot(chargerId: ChargerId): ChargerSnapshot {
        val connected = sessions.isConnected(chargerId)
        val telemetry = telemetryOf(chargerId)
        val reading = telemetry.freshReading(clock.instant(), settings.ocpp.meterValueMaxAge)
        return ChargerSnapshot(
            chargerId = chargerId,
            connected = connected,
            status = if (connected) telemetry.status ?: ChargerStatus.UNAVAILABLE else ChargerStatus.UNAVAILABLE,
            power = reading?.powerWatt ?: NO_POWER_WATT,
            currentL1 = reading?.currents?.l1,
            currentL2 = reading?.currents?.l2,
            currentL3 = reading?.currents?.l3,
            voltageL1 = reading?.voltages?.l1,
            voltageL2 = reading?.voltages?.l2,
            voltageL3 = reading?.voltages?.l3,
            lastMeterValueAt = telemetry.lastMeterValueAt,
            statusSince = telemetry.statusSince,
        )
    }

    fun snapshots(): List<ChargerSnapshot> = settings.chargers.map { snapshot(it.id) }

    private fun telemetryOf(chargerId: ChargerId): ChargerTelemetry =
        telemetryByChargerId.getOrPut(chargerId) { ChargerTelemetry() }
}
