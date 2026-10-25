package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.ha.HaHeartbeatPublisher
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

private const val NO_CYCLE_YET = "Noch kein Regelzyklus gelaufen"

/** Hält den aktuellen Regelzustand für GUI und Actuator. Der Heartbeat-Zeitpunkt wird beim Lesen ergänzt. */
@Component
class ControlStateHolder(
    private val heartbeatPublisher: HaHeartbeatPublisher,
    properties: AppProperties,
) : ControlStateStore {

    private val state: AtomicReference<ControlState> = AtomicReference(initialState(properties.control.actuationEnabled))

    override fun current(): ControlState =
        state.get().copy(lastHeartbeatAt = heartbeatPublisher.lastSuccessfulHeartbeatAt())

    fun update(transform: (ControlState) -> ControlState) {
        state.updateAndGet(transform)
    }

    private fun initialState(actuationEnabled: Boolean): ControlState = ControlState(
        evaluatedAt = null,
        haConnected = false,
        actuationEnabled = actuationEnabled,
        inputs = null,
        settings = null,
        chargers = emptyList(),
        decision = null,
        skippedReason = NO_CYCLE_YET,
        lastAction = null,
        history = emptyList(),
        settleUntil = null,
        lastHeartbeatAt = null,
        lastError = null,
    )
}
