package de.puetsch.easeeocpp.web

import de.puetsch.easeeocpp.config.AppProperties
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@Epic("GUI")
@Feature("View-Modell")
class StateViewFactoryTest {
    private val now: Instant = Instant.parse("2026-10-25T12:00:00Z")
    private val factory = StateViewFactory(Clock.fixed(now, ZoneOffset.UTC), ChargerNames(AppProperties()))

    @Test
    @Story("vollständiger Zustand")
    fun `vollstaendiger Zustand wird mit Einheiten und Texten formatiert`() {
        val view = factory.create(StateFixtures.fullState(now))

        assertEquals("4.200 W", view.eingaenge.pvLeistung)
        assertEquals("6 A", view.eingaenge.mindeststrom)
        assertEquals("Auto vor Batterie", view.eingaenge.batteriePraeferenz)
        assertEquals("an", view.eingaenge.ueberschuss)
        assertEquals("1.740 W", view.ergebnis.mindestPvLeistung)
        assertEquals("L2", view.ergebnis.phaseHoechsteSpannung)
        assertEquals("verbunden", view.verbindungen.haStatus)
        assertEquals("ok", view.verbindungen.haStatusKlasse)
        assertEquals("noch 20 s", view.settleRestzeit)
    }

    @Test
    @Story("Verteilung und Charger")
    fun `verteilung und chargerstatus werden je Charger aufgefuehrt`() {
        val view = factory.create(StateFixtures.fullState(now))

        val names = ChargerNames(AppProperties())
        assertEquals(
            listOf(
                VerteilungView(StateFixtures.PRIO_CHARGER, names.nameOf(StateFixtures.PRIO_CHARGER), "6 A"),
                VerteilungView(StateFixtures.SECOND_CHARGER, names.nameOf(StateFixtures.SECOND_CHARGER), "0 A"),
            ),
            view.ergebnis.verteilung,
        )
        assertEquals("Lädt", view.verbindungen.chargers.first().status)
        assertEquals("getrennt", view.verbindungen.chargers.last().verbindung)
        assertEquals(FALLBACK_TEXT, view.verbindungen.chargers.last().letzterMesswert)
    }

    @Test
    @Story("Auto angesteckt")
    fun `angesteckt-status je Charger folgt dem Ladestatus`() {
        val view = factory.create(StateFixtures.fullState(now))

        assertEquals("ja", view.verbindungen.chargers.first().autoAngesteckt)
        assertEquals("nein", view.verbindungen.chargers.last().autoAngesteckt)
    }

    @Test
    @Story("Verteilungsmodus")
    fun `verteilungsmodus wird als deutsches Wort angezeigt`() {
        assertEquals("Priorisiert", verteilungsmodusFor("PRIORITIZED"))
        assertEquals("Geteilt", verteilungsmodusFor("SHARED"))
        assertEquals("Ein Auto", verteilungsmodusFor("SINGLE_CHARGER"))
    }

    @Test
    @Story("Verteilungsmodus")
    fun `fehlender verteilungsmodus zeigt den Platzhalter`() {
        assertEquals(FALLBACK_TEXT, verteilungsmodusFor(null))
    }

    private fun verteilungsmodusFor(allocationMode: String?): String {
        val state = StateFixtures.fullState(now)
        val withMode = state.copy(decision = state.decision?.copy(allocationMode = allocationMode))
        return factory.create(withMode).ergebnis.verteilungsmodus
    }

    @Test
    @Story("Historie")
    fun `historie ist neueste zuerst sortiert`() {
        val view = factory.create(StateFixtures.fullState(now))

        assertEquals(3, view.historie.size)
        assertEquals("SET_CURRENT", view.historie.first().aktion)
        assertEquals("SET_PHASES", view.historie.last().aktion)
        assertTrue(view.historie.first().zeit.endsWith("(vor 10 s)"))
    }

    @Test
    @Story("Schattenmodus")
    fun `schattenmodus wird nur bei deaktivierter Betaetigung angezeigt`() {
        assertTrue(factory.create(StateFixtures.shadowModeState(now)).schattenmodus)
        assertFalse(factory.create(StateFixtures.fullState(now)).schattenmodus)
    }

    @Test
    @Story("Fehlerzustand")
    fun `uebersprungener Zyklus zeigt Grund und keine Berechnungswerte`() {
        val view = factory.create(StateFixtures.skippedState(now))

        assertEquals(StateFixtures.SKIPPED_REASON, view.uebersprungenGrund)
        assertEquals(StateFixtures.SKIPPED_REASON, view.ergebnis.begruendung)
        assertEquals(FALLBACK_TEXT, view.ergebnis.zielleistung)
        assertEquals(emptyList<VerteilungView>(), view.ergebnis.verteilung)
        assertEquals("getrennt", view.verbindungen.chargers.single().verbindung)
        assertEquals("warnung", view.verbindungen.haStatusKlasse)
    }

    @Test
    @Story("leerer Zustand")
    fun `leerer Zustand liefert Gedankenstriche statt Ausnahmen`() {
        val view = factory.create(StateFixtures.emptyState())

        assertEquals(FALLBACK_TEXT, view.auswertungZeit)
        assertEquals(FALLBACK_TEXT, view.eingaenge.pvLeistung)
        assertEquals(FALLBACK_TEXT, view.eingaenge.batteriePraeferenz)
        assertEquals(FALLBACK_TEXT, view.ergebnis.begruendung)
        assertEquals(FALLBACK_TEXT, view.letzterEingriff.zeit)
        assertEquals(FALLBACK_TEXT, view.settleRestzeit)
        assertEquals(emptyList<HistoryRowView>(), view.historie)
        assertEquals("getrennt", view.verbindungen.haStatus)
    }
}
