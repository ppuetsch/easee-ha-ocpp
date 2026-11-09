package de.puetsch.easeeocpp.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

/**
 * Alter Weg: Der Service ist selbst OCPP-Server der Charger (`easee.ocpp.enabled=true`). Standard ist die Steuerung
 * über die Easee-Integration in Home Assistant (hacharger); dann bleiben OCPP-Server, TLS-Connector und Port 8443 aus.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "easee.ocpp", name = ["enabled"], havingValue = "true")
@ComponentScan(basePackages = ["de.puetsch.easeeocpp.ocpp", "de.puetsch.easeeocpp.server"])
class OcppServerConfiguration
