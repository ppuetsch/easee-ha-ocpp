package de.puetsch.easeeocpp.web

import de.puetsch.easeeocpp.config.AppProperties
import org.springframework.stereotype.Component

/** Die Oberfläche zeigt die Namen aus der Konfiguration (Mülltonnen, Eingangsseite) statt der Seriennummern. */
@Component
class ChargerNames(properties: AppProperties) {
    private val nameBySerial: Map<String, String> = properties.chargers.associate { it.id to it.name }

    fun nameOf(serial: String): String = nameBySerial[serial] ?: serial

    fun replaceSerialsIn(text: String): String =
        nameBySerial.entries.fold(text) { result, (serial, name) -> result.replace(serial, name) }
}
