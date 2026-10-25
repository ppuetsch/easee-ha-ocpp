package de.puetsch.easeeocpp.health

import de.puetsch.easeeocpp.control.ControlState
import de.puetsch.easeeocpp.control.ControlStateStore
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Der Regelzyklus läuft alle 5 s. Bleibt er länger aus, hängt der Regler und der Container gilt als ungesund. */
private val MAX_CYCLE_AGE: Duration = Duration.ofSeconds(60)

/** Direkt nach dem Start darf noch kein Zyklus vorliegen, ohne dass der Dienst als defekt gilt. */
private val STARTUP_GRACE: Duration = Duration.ofSeconds(60)

private const val DETAIL_HA_CONNECTED = "haConnected"
private const val DETAIL_SKIPPED_REASON = "skippedReason"
private const val DETAIL_HINT = "hinweis"
private const val NO_SKIP_REASON = "-"
private const val HINT_STARTING = "Anlaufzeit: noch kein Regelzyklus gelaufen"
private const val HINT_MISSING = "Seit Ablauf der Anlaufzeit kein Regelzyklus gelaufen"
private const val HINT_STALE = "Letzter Regelzyklus älter als 60 s"

private enum class CycleStatus { FRESH, STARTING, MISSING, STALE }

/**
 * Meldet nur den Zustand des Regelzyklus als DOWN. Fehlt die HA-Verbindung (z. B. im Schattenmodus),
 * ist der Zyklus trotzdem frisch und der Dienst bleibt UP; HA-Verbindung und Grund stehen nur als Detail.
 */
@Component
class EaseeControlHealthIndicator(
    private val stateStore: ControlStateStore,
    private val clock: Clock,
) : HealthIndicator {

    private val startedAt: Instant = clock.instant()

    override fun health(): Health {
        val state = stateStore.current()
        return buildHealth(cycleStatus(state.evaluatedAt), state)
    }

    private fun cycleStatus(evaluatedAt: Instant?): CycleStatus = when {
        evaluatedAt == null && isWithinStartupGrace() -> CycleStatus.STARTING
        evaluatedAt == null -> CycleStatus.MISSING
        isOlderThanMaxAge(evaluatedAt) -> CycleStatus.STALE
        else -> CycleStatus.FRESH
    }

    private fun buildHealth(status: CycleStatus, state: ControlState): Health = when (status) {
        CycleStatus.FRESH -> upHealth(state, hint = null)
        CycleStatus.STARTING -> upHealth(state, HINT_STARTING)
        CycleStatus.MISSING -> downHealth(state, HINT_MISSING)
        CycleStatus.STALE -> downHealth(state, HINT_STALE)
    }

    private fun upHealth(state: ControlState, hint: String?): Health =
        Health.up().withCycleDetails(state, hint).build()

    private fun downHealth(state: ControlState, hint: String): Health =
        Health.down().withCycleDetails(state, hint).build()

    private fun Health.Builder.withCycleDetails(state: ControlState, hint: String?): Health.Builder {
        withDetail(DETAIL_HA_CONNECTED, state.haConnected)
        withDetail(DETAIL_SKIPPED_REASON, state.skippedReason ?: NO_SKIP_REASON)
        if (hint != null) withDetail(DETAIL_HINT, hint)
        return this
    }

    private fun isWithinStartupGrace(): Boolean = Duration.between(startedAt, clock.instant()) < STARTUP_GRACE

    private fun isOlderThanMaxAge(evaluatedAt: Instant): Boolean =
        Duration.between(evaluatedAt, clock.instant()) > MAX_CYCLE_AGE
}
