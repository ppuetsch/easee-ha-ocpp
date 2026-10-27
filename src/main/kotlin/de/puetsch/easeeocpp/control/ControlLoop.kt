package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.AllocationResult
import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerLimit
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import de.puetsch.easeeocpp.domain.model.ControlAction
import de.puetsch.easeeocpp.domain.model.ControlDecision
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.PhaseMode
import de.puetsch.easeeocpp.ha.HaConnectionProblemSource
import de.puetsch.easeeocpp.ports.ActuationResult
import de.puetsch.easeeocpp.ports.ChargerFleet
import de.puetsch.easeeocpp.ports.HomeAssistantStates
import de.puetsch.easeeocpp.ports.PhaseModeGateway
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

const val KIND_PHASE_MODE = "PHASE_MODE"
const val KIND_CURRENT_LIMIT = "CURRENT_LIMIT"
const val KIND_CURRENT_REFRESH = "CURRENT_REFRESH"
const val OUTCOME_SHADOW = "Schattenmodus – nicht gesendet"
const val OUTCOME_SENT = "gesendet"
const val SKIP_HA_DISCONNECTED = "HA nicht verbunden"
const val SKIP_HA_TOKEN_REJECTED = "HA-Token abgelehnt"
const val SKIP_MISSING_INPUT_PREFIX = "Eingang fehlt: "

internal const val OUTCOME_FAILED_PREFIX = "fehlgeschlagen: "
private const val CONTROL_THREAD_NAME = "control-loop"
private const val ERROR_PREFIX = "Regelzyklus fehlgeschlagen: "

/** Ergebnis der Phasenmodus-Umschaltung: die Befehle und ob sie an allen Chargern gelungen sind. */
private data class PhaseModeAttempt(val actions: List<ControlAction>, val applied: Boolean) {
    companion object {
        val NONE = PhaseModeAttempt(actions = emptyList(), applied = false)
    }
}

/**
 * Dünner Trigger plus Zyklus: [runCycle] liest die Eingänge, rechnet und stellt nur bei echten Änderungen
 * (oder nach Ablauf des Profil-Refreshs) um. Während der Settle-Pause wird gerechnet, aber nichts gesendet.
 * Im Schattenmodus wird der Zustand nachgeführt, aber kein Gerät angesprochen.
 */
@Component
class ControlLoop(
    private val settingsReader: HaSettingsReader,
    private val haStates: HomeAssistantStates,
    private val connectionProblems: HaConnectionProblemSource,
    private val chargerFleet: ChargerFleet,
    private val phaseModeGateway: PhaseModeGateway,
    private val cycle: ControlCycle,
    private val history: ControlHistory,
    private val stateHolder: ControlStateHolder,
    private val properties: AppProperties,
    private val clock: Clock,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(ControlLoop::class.java)
    private val tracker = ActuationTracker(properties.control.settleDelay)
    private val rampLimiter = RampLimiter(properties.control)
    private val phaseModeBackoff = PhaseModeRetryBackoff()
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var running = false

    fun runCycle() {
        val now = clock.instant()
        try {
            executeCycle(now)
            stateHolder.update { it.copy(lastError = null) }
        } catch (failure: VirtualMachineError) {
            throw failure
        } catch (failure: Throwable) {
            log.warn("Regelzyklus fehlgeschlagen: {}", failure.message, failure)
            stateHolder.update { it.copy(evaluatedAt = now, lastError = ERROR_PREFIX + failure.message) }
        }
    }

    private fun executeCycle(now: Instant) {
        chargerFleet.requestFreshMeasurements()
        val snapshots = chargerFleet.snapshots()
        when {
            connectionProblems.connectionProblem() != null ->
                publishSkipped(now, haConnected = false, snapshots, SKIP_HA_TOKEN_REJECTED)

            !haStates.isConnected() ->
                publishSkipped(now, haConnected = false, snapshots, SKIP_HA_DISCONNECTED)

            else -> readAndEvaluate(now, snapshots)
        }
    }

    private fun readAndEvaluate(now: Instant, snapshots: List<ChargerSnapshot>) {
        when (val reading = settingsReader.read()) {
            is HaReadResult.MissingInput ->
                publishSkipped(now, haConnected = true, snapshots, SKIP_MISSING_INPUT_PREFIX + reading.entityId)

            is HaReadResult.Complete -> evaluateAndActuate(now, reading.readings, snapshots)
        }
    }

    private fun evaluateAndActuate(now: Instant, readings: HaReadings, snapshots: List<ChargerSnapshot>) {
        val evaluation = cycle.evaluate(readings, snapshots, now, tracker.lastLimits())
        val rampedLimits = rampLimiter.limit(
            now,
            evaluation.decision.allocation.limits,
            tracker.lastLimits(),
            startingPoint = { chargerId -> measuredCurrent(chargerId, snapshots) },
            reductionsOnly = tracker.isSettling(now),
        )
        val decision = withRampNote(evaluation.decision, rampedLimits)
        val actions = actuate(now, decision.copy(allocation = AllocationResult(rampedLimits, rampedLimits.sumOf { it.current })), settling = tracker.isSettling(now))
        actions.forEach(history::record)
        stateHolder.update { previous ->
            previous.copy(
                evaluatedAt = now,
                haConnected = true,
                actuationEnabled = properties.control.actuationEnabled,
                inputs = evaluation.inputs,
                settings = readings.settings,
                chargers = snapshots,
                decision = decision,
                skippedReason = null,
                lastAction = actions.lastOrNull() ?: previous.lastAction,
                history = history.recent(),
                settleUntil = tracker.settleUntil(now),
            )
        }
    }

    private fun publishSkipped(now: Instant, haConnected: Boolean, snapshots: List<ChargerSnapshot>, reason: String) {
        stateHolder.update { previous ->
            previous.copy(
                evaluatedAt = now,
                haConnected = haConnected,
                actuationEnabled = properties.control.actuationEnabled,
                inputs = null,
                settings = null,
                chargers = snapshots,
                decision = null,
                skippedReason = reason,
                history = history.recent(),
                settleUntil = tracker.settleUntil(now),
            )
        }
    }

    /**
     * Ein fehlgeschlagener Phasenbefehl startet keine Settle-Pause und blockiert die Stromregelung nicht.
     * Der Settle-Delay folgt nur gelungenen Änderungen; Limit-Änderungen werden wie bisher mit Versuch gesetzt.
     */
    private fun actuate(now: Instant, decision: ControlDecision, settling: Boolean): List<ControlAction> {
        val phaseAttempt = if (settling) PhaseModeAttempt.NONE else phaseModeActionsIfChanged(now, decision.phaseMode)
        val limits = decision.allocation.limits
        val limitsChanged = tracker.limitsChanged(limits)
        val limitActions = limitActionsIfDue(now, limits, limitsChanged)
        if (phaseAttempt.applied || (limitsChanged && !settling)) tracker.startSettle(now)
        return phaseAttempt.actions + limitActions
    }

    /** Zeigt in der Begründung, wenn die Rampe das Ziel noch nicht erreicht hat. */
    private fun withRampNote(decision: ControlDecision, rampedLimits: List<ChargerLimit>): ControlDecision {
        val held = decision.allocation.limits.zip(rampedLimits).filter { (target, ramped) -> target.current != ramped.current }
        if (held.isEmpty()) return decision
        val note = held.joinToString(", ") { (target, ramped) -> "${ramped.chargerId} ${ramped.current} A (Ziel ${target.current} A)" }
        return decision.copy(reason = "${decision.reason}; Rampe: $note")
    }

    private fun measuredCurrent(chargerId: ChargerId, snapshots: List<ChargerSnapshot>): Ampere {
        val charger = snapshots.firstOrNull { it.chargerId == chargerId }?.takeIf { it.status == ChargerStatus.CHARGING }
        val phases = listOfNotNull(charger?.currentL1, charger?.currentL2, charger?.currentL3)
        return phases.maxOrNull()?.let { kotlin.math.ceil(it).toInt() } ?: 0
    }

    private fun phaseModeActionsIfChanged(now: Instant, phaseMode: PhaseMode): PhaseModeAttempt {
        val changeWanted = tracker.phaseModeChanged(phaseMode)
        if (!changeWanted || !phaseModeBackoff.isAttemptAllowed(phaseMode, now)) return PhaseModeAttempt.NONE
        val results = properties.chargers.map { charger ->
            charger.id to actuateOrSimulate { phaseModeGateway.setPhaseMode(charger.id, phaseMode) }
        }
        val allApplied = results.all { it.second.success }
        recordPhaseModeOutcome(now, phaseMode, allApplied)
        val actions = results.map { (chargerId, result) ->
            ControlAction(now, chargerId, KIND_PHASE_MODE, phaseMode, null, outcomeOf(result))
        }
        return PhaseModeAttempt(actions, applied = allApplied)
    }

    private fun recordPhaseModeOutcome(now: Instant, phaseMode: PhaseMode, applied: Boolean) {
        if (applied) {
            tracker.recordPhaseMode(phaseMode)
            phaseModeBackoff.recordSuccess()
        } else {
            phaseModeBackoff.recordFailure(phaseMode, now)
        }
    }

    private fun limitActionsIfDue(now: Instant, limits: List<ChargerLimit>, limitsChanged: Boolean): List<ControlAction> {
        val refreshDue = tracker.refreshDue(now, properties.control.profileRefreshInterval)
        if (!limitsChanged && !refreshDue) return emptyList()
        val kind = if (limitsChanged) KIND_CURRENT_LIMIT else KIND_CURRENT_REFRESH
        tracker.recordLimitAttempt(now)
        return limits.map { limit ->
            val result = actuateOrSimulate { chargerFleet.applyLimit(limit.chargerId, limit.current) }
            if (result.success) tracker.recordLimit(limit.chargerId, limit.current)
            ControlAction(now, limit.chargerId, kind, null, limit.current, outcomeOf(result))
        }
    }

    private fun actuateOrSimulate(call: () -> ActuationResult): ActuationResult =
        if (properties.control.actuationEnabled) call() else ActuationResult(success = true, message = OUTCOME_SHADOW)

    private fun outcomeOf(result: ActuationResult): String = when {
        !properties.control.actuationEnabled -> OUTCOME_SHADOW
        result.success -> OUTCOME_SENT
        else -> OUTCOME_FAILED_PREFIX + result.message
    }

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        val interval = properties.control.interval.toMillis()
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, CONTROL_THREAD_NAME).apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay(::runCycle, 0, interval, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    override fun stop() {
        running = false
        scheduler?.shutdownNow()
        scheduler = null
    }

    override fun isRunning(): Boolean = running
}
