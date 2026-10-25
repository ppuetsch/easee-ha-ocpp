package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Das Regelprofil muss vor Ablauf erneuert werden, sonst fällt der Charger zwischen zwei Erneuerungen auf das
 * 0-A-Basisprofil zurück. Die Werte liegen in zwei Klassen (ControlProperties, OcppProperties), deshalb wird hier geprüft.
 */
@Component
class ProfileTimingValidator(private val settings: AppProperties) {

    @PostConstruct
    fun validate() {
        if (!renewalFitsInsideProfile()) throw IllegalStateException(violationMessage())
    }

    private fun renewalFitsInsideProfile(): Boolean =
        settings.control.profileRefreshInterval < settings.ocpp.profileDuration

    private fun violationMessage(): String {
        val refreshSeconds = settings.control.profileRefreshInterval.seconds
        val durationSeconds = settings.ocpp.profileDuration.seconds
        return "Ungültige Profil-Zeiten: easee.control.profile-refresh-interval ($refreshSeconds s) muss kleiner sein " +
            "als easee.ocpp.profile-duration ($durationSeconds s), sonst läuft das Regelprofil ab, bevor es erneuert wird."
    }
}
