package de.puetsch.easeeocpp

import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

/**
 * Der eigene OCPP-Server (Pakete ocpp und server) wird nur über [config.OcppServerConfiguration] geladen.
 * Ein eigenes @ComponentScan ersetzt das von @SpringBootApplication, daher stehen dessen Standardfilter hier mit.
 */
@SpringBootApplication
@ComponentScan(
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [TypeExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [AutoConfigurationExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.REGEX, pattern = ["""de\.puetsch\.easeeocpp\.(ocpp|server)\..*"""]),
    ],
)
@ConfigurationPropertiesScan
class EaseeHaOcppApplication

fun main(args: Array<String>) {
    runApplication<EaseeHaOcppApplication>(*args)
}
