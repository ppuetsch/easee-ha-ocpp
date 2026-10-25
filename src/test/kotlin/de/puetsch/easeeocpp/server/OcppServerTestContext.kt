package de.puetsch.easeeocpp.server

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.ocpp.OCPP_SUBPROTOCOL
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.socket.server.support.DefaultHandshakeHandler

private const val OCPP_ENDPOINT_PATTERN = "/ocpp/*"

/**
 * Eingeschränkter Testkontext nur mit Server-Connector, Pfadfilter und einem Platzhalter-WebSocket-Endpunkt.
 * Bewusst ohne `@Configuration`/`@SpringBootConfiguration`, damit der Komponenten-Scan der Anwendung ihn nicht
 * aufnimmt; Tests übergeben ihn explizit als `classes`.
 */
@EnableAutoConfiguration
@EnableWebSocket
@EnableConfigurationProperties(AppProperties::class)
@Import(OcppConnectorCustomizer::class, OcppPortPathFilter::class)
class OcppServerTestContext : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(AcceptingWebSocketHandler(), OCPP_ENDPOINT_PATTERN)
            .setHandshakeHandler(DefaultHandshakeHandler().apply { setSupportedProtocols(OCPP_SUBPROTOCOL) })
    }

    class AcceptingWebSocketHandler : TextWebSocketHandler() {
        override fun afterConnectionEstablished(session: WebSocketSession) = Unit
    }
}
