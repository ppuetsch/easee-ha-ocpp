package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.domain.EligibilityPolicy
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import java.time.Instant

/**
 * Ein Charger, der in einem Wartezustand zu lange wartet, bleibt nicht berechtigt, auch wenn der Status
 * danach wieder zwischen Wartezuständen wechselt. Sonst würde die Stromverteilung bei jedem Statuswechsel
 * kippen. Die Berechtigung kehrt erst zurück, wenn der Charger wieder lädt, vorbereitet wird oder frei war.
 * SUSPENDED_EVSE gibt frei: Die Wallbox pausiert dann wegen unseres eigenen 0-A-Limits. Ohne Freigabe bekäme
 * ein ausgeschlossener Charger nie wieder Strom.
 */
class StickyEligibility(private val policy: EligibilityPolicy) {
    private val waitingTooLong = mutableSetOf<ChargerId>()

    fun isEligible(snapshot: ChargerSnapshot, now: Instant): Boolean {
        releaseIfActiveOrReplugged(snapshot)
        if (policy.hasWaitedTooLong(snapshot, now)) waitingTooLong += snapshot.chargerId
        return policy.isEligible(snapshot, now) && snapshot.chargerId !in waitingTooLong
    }

    private fun releaseIfActiveOrReplugged(snapshot: ChargerSnapshot) {
        if (snapshot.status in RELEASING_STATUSES) waitingTooLong -= snapshot.chargerId
    }

    private companion object {
        val RELEASING_STATUSES = setOf(
            ChargerStatus.CHARGING,
            ChargerStatus.PREPARING,
            ChargerStatus.AVAILABLE,
            ChargerStatus.UNAVAILABLE,
            ChargerStatus.SUSPENDED_EVSE,
        )
    }
}
