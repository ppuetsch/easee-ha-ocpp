package de.puetsch.easeeocpp.e2e

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import de.puetsch.easeeocpp.web.StateFixtures
import io.qameta.allure.Allure
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Test
import java.time.Instant

@Feature("Betriebszustand")
class GuiStatusTest : AbstractGuiTest() {

    @Test
    @Story("Schattenmodus")
    fun `schattenmodus-banner ist sichtbar wenn die Betaetigung aus ist`() {
        stateStore.state = StateFixtures.shadowModeState(Instant.now())

        openPage()

        schritt("Banner prüfen") {
            assertThat(page.locator("[data-testid='schattenmodus-banner']")).isVisible()
            assertThat(page.locator("[data-testid='schattenmodus-banner']")).containsText("Schattenmodus aktiv")
            assertThat(page.locator("[data-testid='schattenmodus-aus']")).hasCount(0)
        }
    }

    @Test
    @Story("Schattenmodus")
    fun `schattenmodus-banner fehlt wenn die Betaetigung aktiv ist`() {
        openPage()

        assertThat(page.locator("[data-testid='schattenmodus-banner']")).hasCount(0)
        assertThat(page.locator("[data-testid='schattenmodus-aus']")).isVisible()
    }

    @Test
    @Story("Fehlerzustand")
    fun `uebersprungener Zyklus zeigt Grund und getrennte Verbindungen`() {
        stateStore.state = StateFixtures.skippedState(Instant.now())

        openPage()

        schritt("Fehlerzustand prüfen") {
            assertShownText("ha-status", "getrennt")
            assertShownText("ergebnis-uebersprungen", StateFixtures.SKIPPED_REASON)
            assertShownText("ergebnis-begruendung", StateFixtures.SKIPPED_REASON)
            assertShownValue("ergebnis-zielleistung", "–")
            assertThat(chargerRow(StateFixtures.PRIO_CHARGER)).containsText("getrennt")
        }
    }
}
