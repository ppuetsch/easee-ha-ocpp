package de.puetsch.easeeocpp.domain

import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import java.time.Duration
import java.time.Instant

/**
 * Ein Charger zählt für die Stromverteilung nur, wenn ein Fahrzeug angeschlossen ist. Ein wartender Zustand
 * (Fahrzeug pausiert oder beendet) fällt erst nach einer Karenzzeit heraus, damit Kurzpausen den Prio-Charger
 * nicht umverteilen.
 */
class EligibilityPolicy(private val properties: ControlProperties) {

    fun isEligible(snapshot: ChargerSnapshot, now: Instant): Boolean =
        snapshot.connected && snapshot.status.hasVehiclePluggedIn() && !hasWaitedTooLong(snapshot, now)

    private fun hasWaitedTooLong(snapshot: ChargerSnapshot, now: Instant): Boolean {
        if (snapshot.status !in WAITING_STATUSES) return false
        val statusSince = snapshot.statusSince ?: return false
        return Duration.between(statusSince, now) > properties.ineligibleAfter
    }

    private companion object {
        val WAITING_STATUSES = setOf(ChargerStatus.SUSPENDED_EV, ChargerStatus.FINISHING)
    }
}
