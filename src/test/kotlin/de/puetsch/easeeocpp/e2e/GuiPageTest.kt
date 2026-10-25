package de.puetsch.easeeocpp.e2e

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import de.puetsch.easeeocpp.web.StateFixtures
import io.qameta.allure.Allure
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

@Feature("Seitenaufbau")
class GuiPageTest : AbstractGuiTest() {

    @Test
    @Story("Abschnitte")
    fun `alle Abschnitte sind sichtbar`() {
        openPage()

        schritt("Abschnitte prüfen") {
            SECTIONS.forEach { sectionId ->
                assertThat(page.locator("[data-testid='$sectionId']")).isVisible()
            }
        }
    }

    @Test
    @Story("Werte")
    fun `eingaenge und berechnung zeigen die Werte des Zustands`() {
        openPage()

        schritt("Eingänge") {
            assertShownValue("eingang-pv", "4.200 W")
            assertShownValue("eingang-netz", "-350 W")
            assertShownValue("eingang-batterie", "-120 W")
            assertShownValue("eingang-ev", "3.100 W")
            assertShownValue("eingang-puffer", "200 W")
            assertShownValue("eingang-mindeststrom", "6 A")
            assertShownValue("eingang-zielphasen", "3-phasig")
            assertShownValue("eingang-ueberschuss", "an")
            assertShownValue("eingang-batterie-praeferenz", "Auto vor Batterie")
            assertShownValue("eingang-prio-charger", StateFixtures.PRIO_CHARGER)
        }
        schritt("Berechnungsergebnis") {
            assertShownValue("ergebnis-mindest-pv", "1.740 W")
            assertShownValue("ergebnis-rohleistung", "3.910 W")
            assertShownValue("ergebnis-zielleistung", "3.710 W")
            assertShownValue("ergebnis-zielstrom", "6 A")
            assertShownValue("ergebnis-phase", "L2")
            assertShownText("ergebnis-begruendung", "Überschuss reicht für 6 A")
            assertThat(page.locator("[data-testid='ergebnis-verteilung'] li")).hasCount(2)
        }
    }

    @Test
    @Story("Verbindungen")
    fun `verbindungen zeigen Home Assistant Heartbeat und Charger`() {
        openPage()

        assertShownText("ha-status", "verbunden")
        assertThat(page.locator("[data-testid='ha-heartbeat']")).containsText(Pattern.compile("vor \\d+ s"))
        assertThat(chargerRow(StateFixtures.PRIO_CHARGER)).containsText("verbunden")
        assertThat(chargerRow(StateFixtures.PRIO_CHARGER).locator("[data-testid='charger-status']")).hasText("Lädt")
        assertThat(chargerRow(StateFixtures.PRIO_CHARGER).locator("[data-testid='charger-auto-angesteckt']")).hasText("ja")
        assertThat(chargerRow(StateFixtures.SECOND_CHARGER)).containsText("getrennt")
        assertThat(chargerRow(StateFixtures.SECOND_CHARGER).locator("[data-testid='charger-auto-angesteckt']")).hasText("nein")
        assertShownText("ergebnis-verteilungsmodus", "Priorisiert")
    }

    @Test
    @Story("Letzter Eingriff und Historie")
    fun `letzter eingriff und historie werden angezeigt`() {
        openPage()

        assertShownText("letzter-aktion", "SET_CURRENT")
        assertShownText("letzter-strom", "6 A")
        assertShownText("letzter-ergebnis", "OK")
        assertThat(page.locator("[data-testid='settle-restzeit']")).hasText(Pattern.compile("noch \\d+ s"))

        val historyRows = page.locator("[data-testid='historie'] tr.historie-zeile")
        assertThat(historyRows).hasCount(3)
        assertThat(historyRows.first().locator("td").nth(2)).hasText("SET_CURRENT")
        assertThat(historyRows.last().locator("td").nth(2)).hasText("SET_PHASES")
    }

    @Test
    @Story("leerer Zustand")
    fun `leerer Zustand zeigt Gedankenstriche und keine Fehlerseite`() {
        stateStore.state = StateFixtures.emptyState()

        openPage()

        assertShownValue("eingang-pv", "–")
        assertShownValue("ergebnis-zielstrom", "–")
        assertShownText("ha-status", "getrennt")
        assertThat(page.locator("[data-testid='historie-leer']")).isVisible()
    }

    private companion object {
        val SECTIONS = listOf(
            "section-verbindungen",
            "section-eingaenge",
            "section-ergebnis",
            "section-letzter-eingriff",
        )
    }
}
