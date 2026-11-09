package de.puetsch.easeeocpp.ports

import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.EntityId
import de.puetsch.easeeocpp.domain.model.PhaseMode
import java.time.Instant

/** lastChanged: letzter Wechsel des Werts; lastReported: letzte Meldung, auch ohne Wertänderung. */
data class HaEntityState(
    val entityId: EntityId,
    val state: String,
    val lastReported: Instant?,
    val lastChanged: Instant? = null,
) {
    fun isUsable(): Boolean = state != "unavailable" && state != "unknown"
}

interface HomeAssistantStates {
    fun isConnected(): Boolean
    fun state(entityId: EntityId): HaEntityState?
}

interface HomeAssistantActions {
    /** Ruft einen HA-Dienst auf; true, wenn HA den Aufruf erfolgreich beantwortet hat. */
    fun callService(domain: String, service: String, data: Map<String, Any?>): Boolean
}

interface ChargerFleet {
    fun snapshots(): List<ChargerSnapshot>
    fun applyLimit(chargerId: ChargerId, current: Ampere): ActuationResult

    /** Fordert frische Messwerte an (nicht blockierend), damit die Ladeleistung zur Netzmessung passt. */
    fun requestFreshMeasurements() = Unit
}

interface PhaseModeGateway {
    fun setPhaseMode(chargerId: ChargerId, phaseMode: PhaseMode): ActuationResult
}

data class ActuationResult(val success: Boolean, val message: String)
