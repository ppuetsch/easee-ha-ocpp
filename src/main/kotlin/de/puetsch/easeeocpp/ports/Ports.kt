package de.puetsch.easeeocpp.ports

import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.EntityId
import de.puetsch.easeeocpp.domain.model.PhaseMode
import java.time.Instant

data class HaEntityState(val entityId: EntityId, val state: String, val lastReported: Instant?) {
    fun isUsable(): Boolean = state != "unavailable" && state != "unknown"
}

interface HomeAssistantStates {
    fun isConnected(): Boolean
    fun state(entityId: EntityId): HaEntityState?
}

interface ChargerFleet {
    fun snapshots(): List<ChargerSnapshot>
    fun applyLimit(chargerId: ChargerId, current: Ampere): ActuationResult
}

interface PhaseModeGateway {
    fun setPhaseMode(chargerId: ChargerId, phaseMode: PhaseMode): ActuationResult
}

data class ActuationResult(val success: Boolean, val message: String)
