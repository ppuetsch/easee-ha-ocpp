package de.puetsch.easeeocpp.health

import de.puetsch.easeeocpp.control.ControlState
import de.puetsch.easeeocpp.control.ControlStateStore
import de.puetsch.easeeocpp.domain.MutableClock
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.Status
import java.time.Duration
import java.time.Instant

private val START_INSTANT: Instant = Instant.parse("2026-10-25T10:00:00Z")

@Epic("Betrieb")
@Feature("Health-Indikator Regelzyklus")
class EaseeControlHealthIndicatorTest {

    private val clock = MutableClock(START_INSTANT)

    @Test
    @Story("Frischer Zyklus")
    fun `is UP when the last cycle is recent`() {
        val indicator = indicatorWith(stateEvaluatedAt(START_INSTANT.minusSeconds(5)))

        assertEquals(Status.UP, indicator.health().status)
    }

    @Test
    @Story("Schwelle")
    fun `is UP when the last cycle is exactly at the age limit`() {
        val indicator = indicatorWith(stateEvaluatedAt(START_INSTANT.minusSeconds(60)))

        assertEquals(Status.UP, indicator.health().status)
    }

    @Test
    @Story("Veralteter Zyklus")
    fun `is DOWN when the last cycle is older than the age limit`() {
        val indicator = indicatorWith(stateEvaluatedAt(START_INSTANT.minusSeconds(61)))

        assertEquals(Status.DOWN, indicator.health().status)
    }

    @Test
    @Story("Anlaufzeit")
    fun `is UP with a hint while no cycle has run during startup grace`() {
        val indicator = indicatorWith(stateEvaluatedAt(null))
        clock.advance(Duration.ofSeconds(59))

        val health = indicator.health()

        assertEquals(Status.UP, health.status)
        assertTrue(health.details.containsKey("hinweis"))
    }

    @Test
    @Story("Kein Zyklus nach Anlaufzeit")
    fun `is DOWN when no cycle has run after the startup grace`() {
        val indicator = indicatorWith(stateEvaluatedAt(null))
        clock.advance(Duration.ofSeconds(60))

        assertEquals(Status.DOWN, indicator.health().status)
    }

    @Test
    @Story("HA-Verbindung ist kein Kriterium")
    fun `stays UP when HA is disconnected but the cycle is fresh`() {
        val indicator = indicatorWith(
            stateEvaluatedAt(START_INSTANT, haConnected = false, skippedReason = "HA nicht verbunden"),
        )

        val health = indicator.health()

        assertEquals(Status.UP, health.status)
        assertEquals(false, health.details["haConnected"])
    }

    @Test
    @Story("Details")
    fun `reports HA connection and skipped reason as details`() {
        val indicator = indicatorWith(
            stateEvaluatedAt(START_INSTANT, haConnected = true, skippedReason = "Eingangswert fehlt"),
        )

        val details = indicator.health().details

        assertEquals(true, details["haConnected"])
        assertEquals("Eingangswert fehlt", details["skippedReason"])
        assertFalse(details.containsKey("hinweis"))
    }

    @Test
    @Story("Details")
    fun `DOWN health carries the reason as hint`() {
        val indicator = indicatorWith(stateEvaluatedAt(START_INSTANT.minusSeconds(120)))

        val health: Health = indicator.health()

        assertEquals(Status.DOWN, health.status)
        assertTrue(health.details.containsKey("hinweis"))
    }

    private fun indicatorWith(state: ControlState): EaseeControlHealthIndicator =
        EaseeControlHealthIndicator(FixedStateStore(state), clock)

    private fun stateEvaluatedAt(
        evaluatedAt: Instant?,
        haConnected: Boolean = true,
        skippedReason: String? = null,
    ): ControlState = ControlState(
        evaluatedAt = evaluatedAt,
        haConnected = haConnected,
        actuationEnabled = false,
        inputs = null,
        settings = null,
        chargers = emptyList(),
        decision = null,
        skippedReason = skippedReason,
        lastAction = null,
        history = emptyList(),
        settleUntil = null,
        lastHeartbeatAt = null,
    )

    private class FixedStateStore(private val state: ControlState) : ControlStateStore {
        override fun current(): ControlState = state
    }
}
