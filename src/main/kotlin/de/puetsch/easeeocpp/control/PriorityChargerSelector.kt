package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.domain.model.ChargerId
import java.time.Duration
import java.time.Instant

/**
 * Ein Wechsel des Prio-Chargers wirkt erst, wenn der neue Wunsch länger als [switchDelay] ununterbrochen
 * anliegt. So wird ein kurzes Umschalten im HA-Dashboard nicht sofort in die Stromverteilung übernommen.
 * Der allererste Wunsch wird sofort übernommen.
 */
class PriorityChargerSelector(private val switchDelay: Duration) {

    private var activeCharger: ChargerId? = null
    private var pendingSwitch: PendingSwitch? = null

    fun select(requestedCharger: ChargerId, now: Instant): ChargerId {
        val current = activeCharger ?: return activate(requestedCharger)
        if (requestedCharger == current) {
            pendingSwitch = null
            return current
        }
        val switch = pendingSwitchFor(requestedCharger, now)
        return if (isDelayElapsed(switch, now)) activate(requestedCharger) else current
    }

    private fun pendingSwitchFor(requestedCharger: ChargerId, now: Instant): PendingSwitch {
        val existing = pendingSwitch
        if (existing != null && existing.target == requestedCharger) return existing
        return PendingSwitch(requestedCharger, now).also { pendingSwitch = it }
    }

    private fun isDelayElapsed(switch: PendingSwitch, now: Instant): Boolean =
        Duration.between(switch.since, now) >= switchDelay

    private fun activate(chargerId: ChargerId): ChargerId {
        activeCharger = chargerId
        pendingSwitch = null
        return chargerId
    }

    private data class PendingSwitch(val target: ChargerId, val since: Instant)
}
