package de.puetsch.easeeocpp.ocpp

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.config.OcppProperties
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import java.net.URI
import java.util.Base64

private const val CHARGER_ID = "EHDFQLBX"
private const val PASSWORD = "geheim"
private const val CHARGE_POINT_URI = "ws://localhost:8443/ocpp/$CHARGER_ID"
private const val SUBPROTOCOL_LIST = "foo, $OCPP_SUBPROTOCOL"
private const val OTHER_SUBPROTOCOL = "mqtt"
private const val SEC_WEBSOCKET_PROTOCOL_HEADER = "Sec-WebSocket-Protocol"

@Epic("OCPP")
@Feature("Handshake-Prüfung")
class OcppHandshakeInterceptorTest {
    @Test
    @Story("Subprotokoll")
    fun handshakeWithOcppSubprotocolAndValidCredentialsIsAccepted() {
        val attributes = mutableMapOf<String, Any>()

        val accepted = handshake(passwordSettings(), protocol = OCPP_SUBPROTOCOL, authorization = basicAuth(CHARGER_ID, PASSWORD), attributes = attributes)

        assertTrue(accepted)
        assertEquals(CHARGER_ID, attributes[CHARGE_POINT_ID_ATTRIBUTE])
    }

    @Test
    @Story("Subprotokoll")
    fun missingSubprotocolIsRejectedWithBadRequest() {
        val response = mockResponse()

        val accepted = handshake(passwordSettings(), protocol = null, authorization = basicAuth(CHARGER_ID, PASSWORD), response = response)

        assertFalse(accepted)
        verify { response.setStatusCode(HttpStatus.BAD_REQUEST) }
    }

    @Test
    @Story("Subprotokoll")
    fun otherSubprotocolIsRejected() {
        val accepted = handshake(passwordSettings(), protocol = OTHER_SUBPROTOCOL, authorization = basicAuth(CHARGER_ID, PASSWORD))

        assertFalse(accepted)
    }

    @Test
    @Story("Subprotokoll")
    fun subprotocolListContainingOcppIsAccepted() {
        val accepted = handshake(passwordSettings(), protocol = SUBPROTOCOL_LIST, authorization = basicAuth(CHARGER_ID, PASSWORD))

        assertTrue(accepted)
    }

    @Test
    @Story("Authentifizierung")
    fun wrongPasswordIsRejectedWithUnauthorized() {
        val response = mockResponse()

        val accepted = handshake(passwordSettings(), authorization = basicAuth(CHARGER_ID, "falsch"), response = response)

        assertFalse(accepted)
        verify { response.setStatusCode(HttpStatus.UNAUTHORIZED) }
    }

    @Test
    @Story("Authentifizierung")
    fun emptyPasswordRejectsEverythingUnlessAnonymousIsAllowed() {
        val settings = AppProperties(ocpp = OcppProperties(basicAuthPassword = "", allowAnonymous = false))
        val response = mockResponse()

        val accepted = handshake(settings, authorization = basicAuth(CHARGER_ID, "irgendwas"), response = response)

        assertFalse(accepted)
        verify { response.setStatusCode(HttpStatus.UNAUTHORIZED) }
    }

    @Test
    @Story("Authentifizierung")
    fun emptyPasswordWithAllowAnonymousAcceptsWithoutCredentials() {
        val settings = AppProperties(ocpp = OcppProperties(basicAuthPassword = "", allowAnonymous = true))

        assertTrue(handshake(settings, authorization = null))
    }

    @Test
    @Story("Authentifizierung")
    fun startupWarnsLoudlyWhenPasswordIsMissing() {
        val warnings = captureWarnings { OcppHandshakeInterceptor(AppProperties(ocpp = OcppProperties(basicAuthPassword = ""))).warnIfPasswordMissing() }

        assertTrue(warnings.any { it.contains("401") }, warnings.toString())
    }

    @Test
    @Story("Authentifizierung")
    fun startupStaysQuietWhenPasswordIsSet() {
        val warnings = captureWarnings { OcppHandshakeInterceptor(passwordSettings()).warnIfPasswordMissing() }

        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    private fun passwordSettings(): AppProperties = AppProperties(ocpp = OcppProperties(basicAuthPassword = PASSWORD))

    private fun handshake(
        settings: AppProperties,
        protocol: String? = OCPP_SUBPROTOCOL,
        authorization: String?,
        attributes: MutableMap<String, Any> = mutableMapOf(),
        response: ServerHttpResponse = mockResponse(),
    ): Boolean {
        val request = mockRequest(protocol, authorization)
        return OcppHandshakeInterceptor(settings).beforeHandshake(request, response, mockk<WebSocketHandler>(), attributes)
    }

    private fun mockRequest(protocol: String?, authorization: String?): ServerHttpRequest {
        val headers = HttpHeaders()
        protocol?.let { headers.set(SEC_WEBSOCKET_PROTOCOL_HEADER, it) }
        authorization?.let { headers.set(HttpHeaders.AUTHORIZATION, it) }
        return mockk {
            every { uri } returns URI.create(CHARGE_POINT_URI)
            every { getHeaders() } returns headers
        }
    }

    private fun mockResponse(): ServerHttpResponse {
        val response: ServerHttpResponse = mockk(relaxed = true)
        every { response.headers } returns HttpHeaders()
        return response
    }

    private fun basicAuth(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    private fun captureWarnings(action: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(OcppHandshakeInterceptor::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            action()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.map { it.formattedMessage }
    }
}
