package de.puetsch.easeeocpp.web

import de.puetsch.easeeocpp.domain.model.PhaseMode
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

@Epic("GUI")
@Feature("Formatierung")
class StateFormattingTest {
    private val now: Instant = Instant.parse("2026-07-01T12:00:00Z")

    @Test
    @Story("Watt mit Tausendertrennzeichen")
    fun `formatWatt nutzt deutsche Tausendertrennung und Einheit`() {
        assertEquals("4.200 W", formatWatt(4200.0))
        assertEquals("-350 W", formatWatt(-350.0))
    }

    @Test
    @Story("fehlende Werte")
    fun `fehlende Werte werden als Gedankenstrich dargestellt`() {
        assertEquals(FALLBACK_TEXT, formatWatt(null))
        assertEquals(FALLBACK_TEXT, formatAmpere(null))
        assertEquals(FALLBACK_TEXT, formatPhaseMode(null))
        assertEquals(FALLBACK_TEXT, formatOrFallback("   "))
    }

    @Test
    @Story("Ampere und Phasen")
    fun `formatAmpere und formatPhaseMode liefern Einheiten`() {
        assertEquals("16 A", formatAmpere(16))
        assertEquals("3-phasig", formatPhaseMode(PhaseMode.THREE))
        assertEquals("L2", formatPhaseLabel(2))
    }

    @Test
    @Story("relatives Alter")
    fun `formatAge unterscheidet Sekunden Minuten und Stunden`() {
        assertEquals("vor 12 s", formatAge(now.minusSeconds(12), now))
        assertEquals("vor 2 min", formatAge(now.minusSeconds(125), now))
        assertEquals("vor 3 h", formatAge(now.minusSeconds(3 * 3600 + 5), now))
    }

    @Test
    @Story("relatives Alter")
    fun `formatAge ist bei Zukunftszeitpunkten null Sekunden und bei fehlendem Wert ein Gedankenstrich`() {
        assertEquals("vor 0 s", formatAge(now.plusSeconds(30), now))
        assertEquals(FALLBACK_TEXT, formatAge(null, now))
    }

    @Test
    @Story("Uhrzeit in Berlin")
    fun `formatClock zeigt die Ortszeit Europe Berlin`() {
        assertEquals("14:00:00", formatClock(now))
        assertEquals("13:00:00", formatClock(Instant.parse("2026-01-15T12:00:00Z")))
        assertEquals("14:00:00 (vor 0 s)", formatClockWithAge(now, now))
    }

    @Test
    @Story("Settle-Delay")
    fun `formatSettleRemaining zeigt Restzeit oder keine Sperre`() {
        assertEquals("noch 20 s", formatSettleRemaining(now.plusSeconds(20), now))
        assertEquals("keine Sperre", formatSettleRemaining(now.minusSeconds(1), now))
        assertEquals(FALLBACK_TEXT, formatSettleRemaining(null, now))
    }
}
