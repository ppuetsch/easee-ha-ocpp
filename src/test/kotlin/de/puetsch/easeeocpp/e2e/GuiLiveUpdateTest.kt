package de.puetsch.easeeocpp.e2e

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.qameta.allure.Allure
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@Feature("Live-Aktualisierung")
class GuiLiveUpdateTest : AbstractGuiTest() {

    @Test
    @Story("Aktualisierung ohne Reload")
    fun `geaenderter Wert erscheint innerhalb weniger Sekunden ohne Neuladen`() {
        openPage()
        assertShownValue("eingang-pv", "4.200 W")
        page.evaluate("window.seitenMarkierung = 'unveraendert'")

        schritt("Fake-Zustand ändern") {
            val current = stateStore.state
            stateStore.state = current.copy(inputs = current.inputs?.copy(pvPower = 1000.0))
        }

        assertShownValue("eingang-pv", "1.000 W")
        assertEquals("unveraendert", page.evaluate("window.seitenMarkierung"))
    }

    @Test
    @Story("Aktualisierung ohne Reload")
    fun `schattenmodus wird live zugeschaltet`() {
        openPage()
        assertEquals(0, page.locator("[data-testid='schattenmodus-banner']").count())

        val current = stateStore.state
        stateStore.state = current.copy(actuationEnabled = false)

        assertThat(page.locator("[data-testid='schattenmodus-banner']")).isVisible()
    }
}
