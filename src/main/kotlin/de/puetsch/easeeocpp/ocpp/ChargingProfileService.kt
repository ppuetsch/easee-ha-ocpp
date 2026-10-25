package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.ports.ActuationResult
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** Sendet die Ladeprofile und merkt sich den gewünschten Strom, damit er nach Boot/Reconnect erneut gesendet werden kann. */
@Component
class ChargingProfileService(
    private val sessions: OcppSessionRegistry,
    settings: AppProperties,
) {
    private val profiles = ChargingProfileFactory(settings.ocpp)
    private val desiredCurrentByChargerId = ConcurrentHashMap<ChargerId, Ampere>()

    /** Blockiert, bis beide Profile beantwortet sind oder der Timeout greift. Nicht aus dem Receive-Thread aufrufen. */
    fun applyLimit(chargerId: ChargerId, current: Ampere): ActuationResult {
        desiredCurrentByChargerId[chargerId] = current
        return sendLimit(chargerId, current)
    }

    fun resendDesiredLimit(chargerId: ChargerId): ActuationResult? {
        val desiredCurrent = desiredCurrentFor(chargerId) ?: return null
        return sendLimit(chargerId, desiredCurrent)
    }

    fun desiredCurrentFor(chargerId: ChargerId): Ampere? = desiredCurrentByChargerId[chargerId]

    private fun sendLimit(chargerId: ChargerId, current: Ampere): ActuationResult {
        val session = sessions.find(chargerId)
            ?: return ActuationResult(success = false, message = "Charger $chargerId nicht verbunden")
        val baseResponse = awaitResponse(session.call(OcppActions.SET_CHARGING_PROFILE, profiles.baseRequest()))
        if (!baseResponse.isAccepted()) {
            return ActuationResult(success = false, message = "Basisprofil abgelehnt: ${baseResponse.describe()}")
        }
        val limitResponse = awaitResponse(session.call(OcppActions.SET_CHARGING_PROFILE, profiles.limitRequest(current)))
        return if (limitResponse.isAccepted()) {
            ActuationResult(success = true, message = "Limit $current A gesetzt")
        } else {
            ActuationResult(success = false, message = "Regelprofil abgelehnt: ${limitResponse.describe()}")
        }
    }

    private fun awaitResponse(response: java.util.concurrent.CompletableFuture<OcppResponse>): OcppResponse = response.join()
}
