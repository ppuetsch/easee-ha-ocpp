package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.support.DefaultHandshakeHandler

@Configuration
@EnableWebSocket
class OcppConfiguration(
    private val handler: OcppWebSocketHandler,
    private val handshakeInterceptor: OcppHandshakeInterceptor,
    private val settings: AppProperties,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "${settings.ocpp.path}/*")
            .addInterceptors(handshakeInterceptor)
            .setHandshakeHandler(ocppHandshakeHandler())
    }

    private fun ocppHandshakeHandler(): DefaultHandshakeHandler =
        DefaultHandshakeHandler().apply { setSupportedProtocols(OCPP_SUBPROTOCOL) }
}
