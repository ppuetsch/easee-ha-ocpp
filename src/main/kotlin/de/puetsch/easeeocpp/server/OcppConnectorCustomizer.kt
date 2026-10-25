package de.puetsch.easeeocpp.server

import de.puetsch.easeeocpp.config.AppProperties
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.stereotype.Component

/**
 * Hängt den OCPP-Connector an den eingebetteten Tomcat. Der Haupt-Connector bleibt `server.port` (GUI, Actuator).
 */
@Component
class OcppConnectorCustomizer(
    private val settings: AppProperties,
) : WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    override fun customize(factory: TomcatServletWebServerFactory) {
        factory.addAdditionalConnectors(OcppConnectorFactory(settings).createConnector())
    }
}
