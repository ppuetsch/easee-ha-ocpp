package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.domain.model.PhaseMode
import java.time.Duration
import java.time.Instant

private val PHASE_MODE_RETRY_BASE: Duration = Duration.ofSeconds(30)
private val PHASE_MODE_RETRY_MAX: Duration = Duration.ofMinutes(10)
private const val MAX_BACKOFF_EXPONENT = 10

/**
 * Bremst Wiederholungen eines fehlgeschlagenen Phasenmodus-Befehls: 30 s, dann verdoppelt bis höchstens 10 min.
 * Ein anderer Zielwert wird sofort versucht, da er mit dem Fehler nichts zu tun hat.
 * Nicht thread-safe: der Regelzyklus läuft in einem Thread.
 */
class PhaseModeRetryBackoff {
    private var failedTarget: PhaseMode? = null
    private var consecutiveFailures = 0
    private var nextAttemptAt: Instant? = null

    fun isAttemptAllowed(target: PhaseMode, now: Instant): Boolean {
        if (target != failedTarget) return true
        val allowedAt = nextAttemptAt ?: return true
        return !now.isBefore(allowedAt)
    }

    fun recordFailure(target: PhaseMode, now: Instant) {
        consecutiveFailures = if (target == failedTarget) consecutiveFailures + 1 else 1
        failedTarget = target
        nextAttemptAt = now.plus(delayAfter(consecutiveFailures))
    }

    fun recordSuccess() {
        failedTarget = null
        consecutiveFailures = 0
        nextAttemptAt = null
    }

    private fun delayAfter(failures: Int): Duration {
        val exponent = minOf(failures - 1, MAX_BACKOFF_EXPONENT)
        val grownDelay = PHASE_MODE_RETRY_BASE.multipliedBy(1L shl exponent)
        return minOf(grownDelay, PHASE_MODE_RETRY_MAX)
    }
}
