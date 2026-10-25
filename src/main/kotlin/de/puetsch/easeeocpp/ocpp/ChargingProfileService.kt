package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.ports.ActuationResult
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Sendet die Ladeprofile und merkt sich den gewünschten Strom, damit er nach Boot/Reconnect erneut gesendet werden kann.
 * Das Basisprofil wird pro Sitzung nur einmal gesendet; eine neue Verbindung ist eine neue Sitzung.
 */
@Component
class ChargingProfileService(
    private val sessions: OcppSessionRegistry,
    settings: AppProperties,
    clock: Clock,
) {
    private val profiles = ChargingProfileFactory(settings.ocpp, clock)
    private val desiredCurrentByChargerId = ConcurrentHashMap<ChargerId, Ampere>()
    private val baseProfileSessionByChargerId = ConcurrentHashMap<ChargerId, OcppChargerSession>()

    /** Blockiert, bis die Profile beantwortet sind oder der Timeout greift. Nicht aus dem Receive-Thread aufrufen. */
    fun applyLimit(chargerId: ChargerId, current: Ampere): ActuationResult {
        desiredCurrentByChargerId[chargerId] = current
        return sendLimit(chargerId, current)
    }

    fun resendDesiredLimit(chargerId: ChargerId): ActuationResult? {
        val desiredCurrent = desiredCurrentFor(chargerId) ?: return null
        return sendLimit(chargerId, desiredCurrent)
    }

    fun desiredCurrentFor(chargerId: ChargerId): Ampere? = desiredCurrentByChargerId[chargerId]

    @Synchronized
    private fun sendLimit(chargerId: ChargerId, current: Ampere): ActuationResult {
        val session = sessions.find(chargerId)
            ?: return ActuationResult(success = false, message = "Charger $chargerId nicht verbunden")
        return sendBaseProfileIfNeeded(chargerId, session) ?: sendLimitProfile(session, current)
    }

    /** Liefert null, wenn das Basisprofil auf dieser Sitzung gilt, sonst das Fehlschlagen als Ergebnis. */
    private fun sendBaseProfileIfNeeded(chargerId: ChargerId, session: OcppChargerSession): ActuationResult? {
        if (baseProfileSessionByChargerId[chargerId] === session) return null
        val response = awaitResponse(session.call(OcppActions.SET_CHARGING_PROFILE, profiles.baseRequest()))
        if (!response.isAccepted()) {
            return ActuationResult(success = false, message = "Basisprofil abgelehnt: ${response.describe()}")
        }
        baseProfileSessionByChargerId[chargerId] = session
        return null
    }

    private fun sendLimitProfile(session: OcppChargerSession, current: Ampere): ActuationResult {
        val response = awaitResponse(session.call(OcppActions.SET_CHARGING_PROFILE, profiles.limitRequest(current)))
        return if (response.isAccepted()) {
            ActuationResult(success = true, message = "Limit $current A gesetzt")
        } else {
            ActuationResult(success = false, message = "Regelprofil abgelehnt: ${response.describe()}")
        }
    }

    private fun awaitResponse(response: CompletableFuture<OcppResponse>): OcppResponse = response.join()
}
