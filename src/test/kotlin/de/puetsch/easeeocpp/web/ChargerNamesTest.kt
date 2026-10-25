package de.puetsch.easeeocpp.web

import de.puetsch.easeeocpp.config.AppProperties
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@Epic("GUI")
@Feature("Charger-Namen")
class ChargerNamesTest {
    private val names = ChargerNames(AppProperties())

    @Test
    fun `bekannte Seriennummer wird zum Namen`() {
        assertEquals("Mülltonnen", names.nameOf("EHDFQLBX"))
        assertEquals("Eingangsseite", names.nameOf("EH2NSSVK"))
    }

    @Test
    fun `unbekannte Seriennummer bleibt unveraendert`() {
        assertEquals("EH0000000", names.nameOf("EH0000000"))
    }

    @Test
    fun `Seriennummern in Freitext werden ersetzt`() {
        val text = "Prio-Charger EHDFQLBX; Verteilung EHDFQLBX 0 A, EH2NSSVK 12 A"

        assertEquals("Prio-Charger Mülltonnen; Verteilung Mülltonnen 0 A, Eingangsseite 12 A", names.replaceSerialsIn(text))
    }
}
