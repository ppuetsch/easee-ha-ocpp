package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.ChargerId
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

const val CHARGE_POINT_ID_ATTRIBUTE = "chargePointId"
private const val BASIC_AUTH_PREFIX = "Basic "

/**
 * HTTP-Basic-Auth: Benutzer ist die ChargePointId, Passwort das konfigurierte Geheimnis.
 * Ein leeres Passwort schaltet die Prüfung ab und ist nur für Tests gedacht.
 */
@Component
class OcppHandshakeInterceptor(private val settings: AppProperties) : HandshakeInterceptor {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val chargePointId = chargePointIdFromPath(request.uri.path)
        if (!isAuthorized(request, chargePointId)) {
            logger.warn("Handshake von {} ohne gültige Basic-Auth abgelehnt", chargePointId)
            response.setStatusCode(HttpStatus.UNAUTHORIZED)
            response.headers.set(HttpHeaders.WWW_AUTHENTICATE, "Basic")
            return false
        }
        if (!isConfiguredCharger(chargePointId)) {
            logger.warn("Unbekannte ChargePointId {} abgelehnt", chargePointId)
            response.setStatusCode(HttpStatus.FORBIDDEN)
            return false
        }
        attributes[CHARGE_POINT_ID_ATTRIBUTE] = chargePointId
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) = Unit

    private fun chargePointIdFromPath(path: String): ChargerId = path.substringAfterLast('/')

    private fun isConfiguredCharger(chargePointId: ChargerId): Boolean =
        settings.chargers.any { it.id == chargePointId }

    private fun isAuthorized(request: ServerHttpRequest, chargePointId: ChargerId): Boolean {
        val expectedPassword = settings.ocpp.basicAuthPassword
        if (expectedPassword.isEmpty()) return true
        val presentedCredentials = presentedCredentials(request)
        return presentedCredentials != null &&
            MessageDigest.isEqual(
                presentedCredentials.toByteArray(StandardCharsets.UTF_8),
                "$chargePointId:$expectedPassword".toByteArray(StandardCharsets.UTF_8),
            )
    }

    private fun presentedCredentials(request: ServerHttpRequest): String? {
        val header = request.headers.getFirst(HttpHeaders.AUTHORIZATION) ?: return null
        if (!header.startsWith(BASIC_AUTH_PREFIX)) return null
        return try {
            val encoded = header.removePrefix(BASIC_AUTH_PREFIX).trim()
            String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        } catch (malformed: IllegalArgumentException) {
            null
        }
    }
}
