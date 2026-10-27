package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerLimit
import java.time.Duration
import java.time.Instant

/**
 * Der Strom darf nur langsam steigen, damit die Batterieregelung dem Auto folgen kann und nicht gegen die
 * Netzmessung anläuft; zu viel Strom wird dagegen sofort zurückgenommen, sonst käme er aus dem Netz.
 * Nicht thread-safe: der Regelzyklus läuft in einem Thread.
 */
class RampLimiter(private val properties: ControlProperties) {

    private val lastChangeAt = mutableMapOf<ChargerId, Instant>()

    /**
     * Liefert je Charger den Strom, der jetzt gesetzt werden darf. `applied` ist das zuletzt gesetzte Limit,
     * `startingPoint` der Ausgangswert für einen Charger ohne bekanntes Limit (z. B. nach einem Neustart).
     * Mit `reductionsOnly` bleiben Erhöhungen liegen (Settle-Pause), Absenkungen gehen trotzdem durch.
     */
    fun limit(
        now: Instant,
        desired: List<ChargerLimit>,
        applied: Map<ChargerId, Ampere>,
        startingPoint: (ChargerId) -> Ampere,
        reductionsOnly: Boolean,
    ): List<ChargerLimit> = desired.map { target ->
        val current = applied[target.chargerId] ?: startingPoint(target.chargerId)
        val allowed = allowedCurrent(now, target, current, reductionsOnly)
        if (allowed != current) lastChangeAt[target.chargerId] = now
        ChargerLimit(target.chargerId, allowed)
    }

    private fun allowedCurrent(now: Instant, target: ChargerLimit, current: Ampere, reductionsOnly: Boolean): Ampere = when {
        target.current <= current -> target.current
        reductionsOnly || !rampStepDue(now, target.chargerId) -> current
        else -> minOf(target.current, maxOf(properties.minChargeCurrent, current + properties.rampUpStep))
    }

    private fun rampStepDue(now: Instant, chargerId: ChargerId): Boolean {
        val last = lastChangeAt[chargerId] ?: return true
        return Duration.between(last, now) >= properties.rampUpInterval
    }
}
