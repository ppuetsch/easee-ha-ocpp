package de.puetsch.easeeocpp.server

import de.puetsch.easeeocpp.config.AppProperties
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.WebSocket
import java.util.concurrent.ExecutionException

private const val HTTP_NOT_FOUND = 404
private const val PLAIN_SCHEME = "http"

@Epic("OCPP")
@Feature("TLS")
@SpringBootTest(classes = [OcppServerTestContext::class], webEnvironment = RANDOM_PORT)
class OcppPlainConnectorTest {
    @Autowired
    private lateinit var settings: AppProperties

    private val ocppPort: Int get() = settings.ocpp.port

    @Test
    @Story("Ohne TLS (ssl.enabled=false) akzeptiert der OCPP-Port ws")
    fun plainWsHandshakeSucceedsWhenSslDisabled() {
        val webSocket = connectWebSocket(defaultTrustHttpClient(), chargerWebSocketUri("ws", ocppPort))

        assertEquals(TEST_OCPP_SUBPROTOCOL, webSocket.subprotocol)
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Test beendet").join()
    }

    @Test
    @Story("Ohne TLS bleibt die Pfadtrennung aktiv")
    fun actuatorIsNotFoundOnPlainOcppPort() {
        val status = httpStatus(defaultTrustHttpClient(), URI.create("$PLAIN_SCHEME://localhost:$ocppPort/actuator/health"))

        assertEquals(HTTP_NOT_FOUND, status)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun plainProperties(registry: DynamicPropertyRegistry) {
            registry.add("easee.ocpp.port") { freeTcpPort() }
            registry.add("easee.ocpp.ssl.enabled") { false }
        }
    }
}
