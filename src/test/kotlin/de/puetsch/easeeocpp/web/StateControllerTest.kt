package de.puetsch.easeeocpp.web

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.control.ControlStateStore
import io.mockk.every
import io.mockk.mockk
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@Epic("GUI")
@Feature("Controller")
@WebMvcTest(controllers = [StateController::class, StateApiController::class])
@Import(StateViewFactory::class)
@EnableConfigurationProperties(AppProperties::class)
class StateControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var stateStore: ControlStateStore

    @Test
    @Story("Seite")
    fun `startseite rendert deutsche Abschnitte mit Testkennungen`() {
        every { stateStore.current() } returns StateFixtures.fullState(NOW)

        mockMvc.get("/").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
            content { string(containsString("data-testid=\"section-verbindungen\"")) }
            content { string(containsString("Berechnungsergebnis")) }
            content { string(containsString("4.200 W")) }
        }
    }

    @Test
    @Story("Schattenmodus")
    fun `schattenmodus-banner erscheint nur bei deaktivierter Betaetigung`() {
        every { stateStore.current() } returns StateFixtures.shadowModeState(NOW)

        mockMvc.get("/fragments/state").andExpect {
            status { isOk() }
            content { string(containsString("data-testid=\"schattenmodus-banner\"")) }
        }
    }

    @Test
    @Story("Fragment")
    fun `fragment liefert nur den Zustandsbereich ohne Seitenrahmen`() {
        every { stateStore.current() } returns StateFixtures.fullState(NOW)

        mockMvc.get("/fragments/state").andExpect {
            status { isOk() }
            content { string(containsString("data-testid=\"section-eingaenge\"")) }
            content { string(not(containsString("<html"))) }
        }
    }

    @Test
    @Story("leerer Zustand")
    fun `leerer Zustand rendert ohne Fehler mit Gedankenstrichen`() {
        every { stateStore.current() } returns StateFixtures.emptyState()

        mockMvc.get("/").andExpect {
            status { isOk() }
            content { string(containsString("Noch keine Eingriffe")) }
        }
    }

    @Test
    @Story("JSON-API")
    fun `api state liefert den Zustand als JSON mit ISO-Zeitstempeln`() {
        every { stateStore.current() } returns StateFixtures.fullState(NOW)

        mockMvc.get("/api/state").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.haConnected") { value(true) }
            jsonPath("$.inputs.pvPower") { value(4200.0) }
            jsonPath("$.evaluatedAt") { value("2026-07-01T11:59:56Z") }
        }
    }

    @TestConfiguration
    class ControllerTestConfiguration {
        @Bean
        fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean
        fun controlStateStore(): ControlStateStore = mockk()
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-01T12:00:00Z")
    }
}
