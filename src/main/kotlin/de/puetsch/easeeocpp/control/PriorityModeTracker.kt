package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import java.time.Duration
import java.time.Instant

enum class AllocationMode { PRIORITIZED, SHARED }

private const val CHARGERS_FOR_SHARING = 2

/**
 * Entscheidet, ob der Prio-Charger den Strom allein bekommt (PRIORITIZED) oder ob beide Charger den Gesamtstrom
 * bekommen (SHARED). Zieht der Prio-Charger trotz gesetztem Limit so lange nichts, dass der andere ungenutzt
 * wartet, wird geteilt. Zurück zu PRIORITIZED, sobald beide wirklich laden oder nicht mehr beide berechtigt sind.
 *
 * Die Zeit kommt als Parameter aus dem injizierten Clock des Zyklus. Nicht thread-safe: ein Zyklus pro Zeit.
 */
class PriorityModeTracker(private val properties: ControlProperties) {

    private var mode = AllocationMode.PRIORITIZED
    private var idleSince: Instant? = null

    fun update(
        now: Instant,
        priorityCharger: ChargerId,
        eligible: Set<ChargerId>,
        snapshots: List<ChargerSnapshot>,
        lastAppliedLimits: Map<ChargerId, Ampere>,
    ): AllocationMode {
        if (eligible.size != CHARGERS_FOR_SHARING) return reset()
        mode = when (mode) {
            AllocationMode.PRIORITIZED -> nextAfterPrioritized(now, priorityCharger, snapshots, lastAppliedLimits)
            AllocationMode.SHARED -> nextAfterShared(eligible, snapshots)
        }
        return mode
    }

    private fun reset(): AllocationMode {
        mode = AllocationMode.PRIORITIZED
        idleSince = null
        return mode
    }

    private fun nextAfterPrioritized(
        now: Instant,
        priorityCharger: ChargerId,
        snapshots: List<ChargerSnapshot>,
        lastAppliedLimits: Map<ChargerId, Ampere>,
    ): AllocationMode {
        if (!isIdleWithLimit(priorityCharger, snapshots, lastAppliedLimits)) {
            idleSince = null
            return AllocationMode.PRIORITIZED
        }
        val since = idleSince ?: now.also { idleSince = it }
        if (Duration.between(since, now) < properties.priorityIdleAfter) return AllocationMode.PRIORITIZED
        idleSince = null
        return AllocationMode.SHARED
    }

    private fun nextAfterShared(eligible: Set<ChargerId>, snapshots: List<ChargerSnapshot>): AllocationMode =
        if (eligible.all { isCharging(powerOf(it, snapshots)) }) AllocationMode.PRIORITIZED else AllocationMode.SHARED

    /** Das Limit ist gesetzt, der Prio-Charger zieht aber unter der Schwelle: genau das ist der Leerlauf. */
    private fun isIdleWithLimit(
        priorityCharger: ChargerId,
        snapshots: List<ChargerSnapshot>,
        lastAppliedLimits: Map<ChargerId, Ampere>,
    ): Boolean {
        val limitIsSet = (lastAppliedLimits[priorityCharger] ?: 0) >= properties.minChargeCurrent
        return limitIsSet && !isCharging(powerOf(priorityCharger, snapshots))
    }

    private fun isCharging(power: Double): Boolean = power >= properties.idlePowerThreshold

    private fun powerOf(chargerId: ChargerId, snapshots: List<ChargerSnapshot>): Double =
        snapshots.firstOrNull { it.chargerId == chargerId }?.power ?: 0.0
}
