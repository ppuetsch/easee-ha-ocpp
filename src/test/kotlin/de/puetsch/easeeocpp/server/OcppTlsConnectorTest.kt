package de.puetsch.easeeocpp.server

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.testsupport.TestCertificates
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.util.concurrent.ExecutionException
import javax.net.ssl.SSLException

private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404
private const val TLS_SCHEME = "https"
private const val PLAIN_SCHEME = "http"

@Epic("OCPP")
@Feature("TLS")
@SpringBootTest(classes = [OcppServerTestContext::class], webEnvironment = RANDOM_PORT)
class OcppTlsConnectorTest {
    @LocalServerPort
    private var guiPort: Int = 0

    @Autowired
    private lateinit var settings: AppProperties

    private val ocppPort: Int get() = settings.ocpp.port

    @Test
    @Story("WSS-Handshake mit vertrauter CA")
    fun wssHandshakeSucceedsWithTrustedCa() {
        val webSocket: WebSocket = connectWebSocket(trustingHttpClient(), chargerWebSocketUri("wss", ocppPort))

        assertEquals(TEST_OCPP_SUBPROTOCOL, webSocket.subprotocol)
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Test beendet").join()
    }

    @Test
    @Story("WSS-Handshake mit ungetrauter CA")
    fun wssHandshakeFailsWithUntrustedCa() {
        val failure = assertThrows<ExecutionException> {
            connectWebSocket(defaultTrustHttpClient(), chargerWebSocketUri("wss", ocppPort))
        }

        assertTrue(causeChain(failure).any { it is SSLException }, "Erwartet TLS-Fehler, war: $failure")
    }

    @Test
    @Story("Plain ws auf dem TLS-Port")
    fun plainWsOnTlsPortFails() {
        assertThrows<ExecutionException> {
            connectWebSocket(trustingHttpClient(), chargerWebSocketUri("ws", ocppPort))
        }
    }

    @Test
    @Story("Actuator nur auf dem GUI-Port")
    fun actuatorIsNotFoundOnOcppPort() {
        val status = httpStatus(trustingHttpClient(), URI.create("$TLS_SCHEME://localhost:$ocppPort/actuator/health"))

        assertEquals(HTTP_NOT_FOUND, status)
    }

    @Test
    @Story("Pfadtraversierung über den OCPP-Port")
    fun traversalFromOcppPathToActuatorIsNotFound() {
        val status = httpStatus(
            trustingHttpClient(),
            URI.create("$TLS_SCHEME://localhost:$ocppPort/ocpp/../actuator/health"),
        )

        assertEquals(HTTP_NOT_FOUND, status)
    }

    @Test
    @Story("Actuator auf dem GUI-Port erreichbar")
    fun actuatorIsServedOnGuiPort() {
        val status = httpStatus(defaultTrustHttpClient(), URI.create("$PLAIN_SCHEME://localhost:$guiPort/actuator/health"))

        assertEquals(HTTP_OK, status)
    }

    @Test
    @Story("OCPP-Pfad über den GUI-Port")
    fun ocppPathOnGuiPortIsNotFound() {
        val failure = assertThrows<ExecutionException> {
            connectWebSocket(defaultTrustHttpClient(), chargerWebSocketUri("ws", guiPort))
        }

        val handshakeFailure = causeChain(failure).filterIsInstance<WebSocketHandshakeException>().firstOrNull()
        assertEquals(HTTP_NOT_FOUND, handshakeFailure?.response?.statusCode())
    }

    private fun causeChain(failure: Throwable): Sequence<Throwable> = generateSequence(failure) { it.cause }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun tlsProperties(registry: DynamicPropertyRegistry) {
            registry.add("easee.ocpp.port") { freeTcpPort() }
            registry.add("easee.ocpp.ssl.enabled") { true }
            registry.add("easee.ocpp.ssl.keystore-path") { TestCertificates.keystorePath() }
            registry.add("easee.ocpp.ssl.keystore-password") { TestCertificates.keystorePassword }
        }
    }
}
