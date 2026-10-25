package de.puetsch.easeeocpp.easee

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.PhaseMode
import de.puetsch.easeeocpp.ports.ActuationResult
import de.puetsch.easeeocpp.ports.PhaseModeGateway
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.http.HttpResponse
import java.time.Clock

private const val HTTP_UNAUTHORIZED = 401
private const val MSG_CREDENTIALS_MISSING = "Easee-Cloud-Zugangsdaten fehlen"
private const val MSG_REQUEST_INTERRUPTED = "Easee-Cloud-Anfrage abgebrochen"
private const val MSG_CONFIGURATION_INVALID = "Easee-Cloud-Konfiguration ungültig"

@Component
class EaseeCloudClient(
    private val properties: AppProperties,
    private val clock: Clock,
) : PhaseModeGateway {
    private val logger = LoggerFactory.getLogger(EaseeCloudClient::class.java)
    private val api = EaseeCloudApi(properties.cloud.baseUrl)
    private val accessTokens = AccessTokenCache(clock) {
        api.login(properties.cloud.username, properties.cloud.password)
    }

    override fun setPhaseMode(chargerId: ChargerId, phaseMode: PhaseMode): ActuationResult {
        if (!hasCredentials()) {
            return failure(MSG_CREDENTIALS_MISSING)
        }
        return try {
            toActuationResult(sendPhaseMode(chargerId, phaseMode), phaseMode)
        } catch (exception: EaseeCloudException) {
            failure(exception.message ?: "Easee-Cloud-Fehler")
        } catch (exception: IOException) {
            failure("Easee-Cloud nicht erreichbar (${exception::class.simpleName})")
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            failure(MSG_REQUEST_INTERRUPTED)
        } catch (exception: IllegalArgumentException) {
            failure(MSG_CONFIGURATION_INVALID)
        }
    }

    private fun hasCredentials(): Boolean =
        properties.cloud.username.isNotBlank() && properties.cloud.password.isNotBlank()

    private fun sendPhaseMode(chargerId: ChargerId, phaseMode: PhaseMode): HttpResponse<String> {
        val firstResponse = api.setPhaseMode(accessTokens.currentBearerToken(), chargerId, phaseMode)
        if (firstResponse.statusCode() != HTTP_UNAUTHORIZED) {
            return firstResponse
        }
        accessTokens.invalidate()
        return api.setPhaseMode(accessTokens.currentBearerToken(), chargerId, phaseMode)
    }

    private fun toActuationResult(response: HttpResponse<String>, phaseMode: PhaseMode): ActuationResult {
        val statusCode = response.statusCode()
        if (isSuccessfulStatus(statusCode)) {
            return ActuationResult(true, "Phasenmodus auf ${phaseMode.phaseCount} gesetzt (HTTP $statusCode)")
        }
        return failure("Easee-Cloud antwortete mit HTTP $statusCode")
    }

    private fun failure(message: String): ActuationResult {
        logger.warn("Easee-Cloud Phasenmodus-Befehl fehlgeschlagen: {}", message)
        return ActuationResult(false, message)
    }
}
