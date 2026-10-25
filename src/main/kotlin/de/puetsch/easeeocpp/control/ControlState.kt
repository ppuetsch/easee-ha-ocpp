package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.domain.model.ChargeSettings
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.ControlAction
import de.puetsch.easeeocpp.domain.model.ControlDecision
import de.puetsch.easeeocpp.domain.model.SurplusInputs
import java.time.Instant

data class ControlState(
    val evaluatedAt: Instant?,
    val haConnected: Boolean,
    val actuationEnabled: Boolean,
    val inputs: SurplusInputs?,
    val settings: ChargeSettings?,
    val chargers: List<ChargerSnapshot>,
    val decision: ControlDecision?,
    val skippedReason: String?,
    val lastAction: ControlAction?,
    val history: List<ControlAction>,
    val settleUntil: Instant?,
    val lastHeartbeatAt: Instant?,
    val lastError: String? = null,
)

interface ControlStateStore {
    fun current(): ControlState
}
