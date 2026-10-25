package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerLimit
import de.puetsch.easeeocpp.domain.model.PhaseMode
import java.time.Duration
import java.time.Instant

/**
 * Merkt sich, was zuletzt an die Charger gegangen ist. Nur echte Änderungen starten die Settle-Pause;
 * ein Profil-Refresh ohne Änderung nicht. Nicht thread-safe: der Regelzyklus läuft in einem Thread.
 */
class ActuationTracker(private val settleDelay: Duration) {

    private var lastPhaseMode: PhaseMode? = null
    private val lastLimits = mutableMapOf<ChargerId, Ampere>()
    private var lastLimitAttemptAt: Instant? = null
    private var settleUntil: Instant? = null

    fun isSettling(now: Instant): Boolean = settleUntil(now) != null

    fun settleUntil(now: Instant): Instant? = settleUntil?.takeIf { now.isBefore(it) }

    fun startSettle(now: Instant) {
        settleUntil = now.plus(settleDelay)
    }

    fun phaseModeChanged(phaseMode: PhaseMode): Boolean = lastPhaseMode != phaseMode

    fun recordPhaseMode(phaseMode: PhaseMode) {
        lastPhaseMode = phaseMode
    }

    fun limitsChanged(limits: List<ChargerLimit>): Boolean =
        limits.any { lastLimits[it.chargerId] != it.current }

    fun recordLimit(chargerId: ChargerId, current: Ampere) {
        lastLimits[chargerId] = current
    }

    fun lastLimits(): Map<ChargerId, Ampere> = lastLimits.toMap()

    fun refreshDue(now: Instant, refreshInterval: Duration): Boolean {
        val lastAttempt = lastLimitAttemptAt ?: return true
        return Duration.between(lastAttempt, now) > refreshInterval
    }

    fun recordLimitAttempt(now: Instant) {
        lastLimitAttemptAt = now
    }
}
