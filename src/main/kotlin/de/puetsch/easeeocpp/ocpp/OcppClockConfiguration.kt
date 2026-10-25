package de.puetsch.easeeocpp.ocpp

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Eigene Konfiguration ohne Abhängigkeiten, damit die Uhr nicht in einen Zyklus mit den OCPP-Beans gerät. */
@Configuration
class OcppClockConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemUTC()
}
