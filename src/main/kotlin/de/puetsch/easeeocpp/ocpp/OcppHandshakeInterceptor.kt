package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.ChargerId
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
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
private const val SUBPROTOCOL_LIST_SEPARATOR = ','
private const val SEC_WEBSOCKET_PROTOCOL_HEADER = "Sec-WebSocket-Protocol"

/**
 * Prüft Subprotokoll, HTTP-Basic-Auth und die konfigurierte ChargePointId, bevor die WebSocket-Verbindung entsteht.
 * Benutzer ist die ChargePointId, Passwort das konfigurierte Geheimnis. Ohne Passwort werden alle Verbindungen
 * abgelehnt, außer `ocpp.allowAnonymous=true` ist gesetzt (nur für Tests gedacht).
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
        if (!requestsOcppSubprotocol(request)) {
            logger.warn("Handshake von {} ohne Subprotokoll {} abgelehnt", chargePointId, OCPP_SUBPROTOCOL)
            response.setStatusCode(HttpStatus.BAD_REQUEST)
            return false
        }
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

    /** Warnt beim Start, wenn das Fehlen des Passworts dazu führt, dass kein Charger sich verbinden kann oder jeder darf. */
    @EventListener(ApplicationReadyEvent::class)
    fun warnIfPasswordMissing() {
        if (settings.ocpp.basicAuthPassword.isNotEmpty()) return
        if (settings.ocpp.allowAnonymous) {
            logger.warn(
                "OCPP: ocpp.allowAnonymous=true und kein Passwort - JEDER kann sich als Charger verbinden. Nur für Tests!",
            )
        } else {
            logger.warn("OCPP: OCPP_BASIC_AUTH_PASSWORD ist leer - ALLE Charger-Verbindungen werden mit 401 abgelehnt.")
        }
    }

    private fun chargePointIdFromPath(path: String): ChargerId = path.substringAfterLast('/')

    private fun isConfiguredCharger(chargePointId: ChargerId): Boolean =
        settings.chargers.any { it.id == chargePointId }

    private fun requestsOcppSubprotocol(request: ServerHttpRequest): Boolean =
        request.headers[SEC_WEBSOCKET_PROTOCOL_HEADER].orEmpty()
            .flatMap { it.split(SUBPROTOCOL_LIST_SEPARATOR) }
            .any { it.trim() == OCPP_SUBPROTOCOL }

    private fun isAuthorized(request: ServerHttpRequest, chargePointId: ChargerId): Boolean {
        val expectedPassword = settings.ocpp.basicAuthPassword
        if (expectedPassword.isEmpty()) return settings.ocpp.allowAnonymous
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
