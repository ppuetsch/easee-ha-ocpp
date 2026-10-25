package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

@Epic("Regelung")
@Feature("Regelschleife")
class ControlLoopTest {

    @TempDir
    lateinit var tempDir: Path

    private fun harness(control: ControlProperties? = null): ControlHarness {
        val historyFile = tempDir.resolve("history.jsonl")
        return ControlHarness(historyFile, control ?: defaultControlProperties(historyFile))
    }

    @Test
    @Story("Erster Zyklus")
    fun `first cycle sets phase mode on both chargers before the limits`() {
        val harness = harness()

        harness.runCycle()

        assertEquals(
            listOf(
                "phase:$MULTONNEN_ID:3",
                "phase:$EINGANGSSEITE_ID:3",
                "limit:$MULTONNEN_ID:7",
                "limit:$EINGANGSSEITE_ID:0",
            ),
            harness.charging.calls,
        )
    }

    @Test
    @Story("Änderungserkennung")
    fun `unchanged values are not sent again before the refresh interval`() {
        val harness = harness()
        harness.runCycle()
        harness.charging.calls.clear()
        harness.clock.advance(Duration.ofSeconds(35))

        harness.runCycle()

        assertTrue(harness.charging.calls.isEmpty())
    }

    @Test
    @Story("Profil-Refresh")
    fun `limits are refreshed after the profile refresh interval without starting a settle pause`() {
        val harness = harness()
        harness.runCycle()
        harness.charging.calls.clear()
        harness.clock.advance(Duration.ofSeconds(61))

        harness.runCycle()

        assertEquals(listOf("limit:$MULTONNEN_ID:7", "limit:$EINGANGSSEITE_ID:0"), harness.charging.calls)
        assertNull(harness.state().settleUntil)
        assertEquals(KIND_CURRENT_REFRESH, harness.state().lastAction?.kind)
    }

    @Test
    @Story("Settle-Delay")
    fun `settle delay blocks every change until thirty seconds have passed`() {
        val harness = harness()
        harness.runCycle()
        harness.charging.calls.clear()
        harness.clock.advance(Duration.ofSeconds(5))
        harness.setEntity(harness.properties.ha.entities.pvSurplusCharge, "off")
        harness.setEntity(harness.properties.ha.entities.targetCurrent, "10.0")

        harness.runCycle()

        assertTrue(harness.charging.calls.isEmpty())
        assertNotNull(harness.state().settleUntil)
        assertNotNull(harness.state().decision)

        harness.clock.advance(Duration.ofSeconds(25))
        harness.runCycle()

        assertEquals(listOf("limit:$MULTONNEN_ID:10", "limit:$EINGANGSSEITE_ID:0"), harness.charging.calls)
        assertEquals(CONTROL_START.plusSeconds(60), harness.state().settleUntil)
    }

    @Test
    @Story("Phasenwechsel-Reihenfolge")
    fun `phase mode change is sent to both chargers before the limit change`() {
        val harness = harness()
        harness.runCycle()
        harness.charging.calls.clear()
        harness.clock.advance(Duration.ofSeconds(31))
        harness.setEntity(harness.properties.ha.entities.pvSurplusCharge, "off")
        harness.setEntity(harness.properties.ha.entities.targetPhases, "1")
        harness.setEntity(harness.properties.ha.entities.targetCurrent, "10.0")

        harness.runCycle()

        assertEquals(
            listOf(
                "phase:$MULTONNEN_ID:1",
                "phase:$EINGANGSSEITE_ID:1",
                "limit:$MULTONNEN_ID:10",
                "limit:$EINGANGSSEITE_ID:0",
            ),
            harness.charging.calls,
        )
    }

    @Test
    @Story("Historie")
    fun `actions of an applied change are written to the history`() {
        val harness = harness()

        harness.runCycle()

        assertEquals(4, harness.history.recent().size)
        assertEquals(4, harness.state().history.size)
    }

    @Test
    @Story("Schattenmodus")
    fun `shadow mode calculates and shows the decision but calls no charger`() {
        val harness = harness(ControlProperties(actuationEnabled = false, historyFile = tempDir.resolve("h.jsonl").toString()))

        harness.runCycle()

        assertTrue(harness.charging.calls.isEmpty())
        assertNotNull(harness.state().decision)
        assertEquals(OUTCOME_SHADOW, harness.state().lastAction?.outcome)
        assertFalse(harness.state().actuationEnabled)
    }

    @Test
    @Story("Fehlender Eingang")
    fun `missing home assistant input skips the cycle without actuation`() {
        val harness = harness()
        val pvEntity = harness.properties.ha.entities.pvPower
        harness.setEntity(pvEntity, "unavailable")

        harness.runCycle()

        assertTrue(harness.charging.calls.isEmpty())
        assertEquals(SKIP_MISSING_INPUT_PREFIX + pvEntity, harness.state().skippedReason)
        assertNull(harness.state().decision)
    }

    @Test
    @Story("HA getrennt")
    fun `disconnected home assistant skips the cycle without actuation`() {
        val harness = harness()
        harness.states.connected = false

        harness.runCycle()

        assertTrue(harness.charging.calls.isEmpty())
        assertEquals(SKIP_HA_DISCONNECTED, harness.state().skippedReason)
        assertFalse(harness.state().haConnected)
    }

    @Test
    @Story("Fehler im Zyklus")
    fun `exception in a cycle is recorded and the loop keeps working`() {
        val harness = harness()
        harness.charging.snapshotFailure = IllegalStateException("Charger-Status kaputt")

        harness.runCycle()

        assertEquals("Regelzyklus fehlgeschlagen: Charger-Status kaputt", harness.state().lastError)
        assertTrue(harness.charging.calls.isEmpty())

        harness.charging.snapshotFailure = null
        harness.runCycle()

        assertNull(harness.state().lastError)
        assertEquals(4, harness.charging.calls.size)
    }

    @Test
    @Story("Ungültige HA-Werte")
    fun `invalid phase value is reported as cycle error and nothing is sent`() {
        val harness = harness()
        harness.setEntity(harness.properties.ha.entities.targetPhases, "2")

        harness.runCycle()

        assertEquals("Regelzyklus fehlgeschlagen: phase mode unknown 2", harness.state().lastError)
        assertTrue(harness.charging.calls.isEmpty())
    }

    @Test
    @Story("Fehlgeschlagener Phasenwechsel")
    fun `failed phase mode change is retried after the settle pause`() {
        val harness = harness()
        harness.charging.failingPhaseMode = true
        harness.runCycle()
        harness.charging.failingPhaseMode = false
        harness.charging.calls.clear()

        harness.clock.advance(Duration.ofSeconds(31))
        harness.runCycle()

        assertTrue(harness.charging.calls.first().startsWith("phase:"))
    }

    @Test
    @Story("Prio-Charger-Eingang")
    fun `unknown priority charger name is reported as cycle error`() {
        val harness = harness()
        harness.setEntity(harness.properties.ha.entities.priorityCharger, "Garage")

        harness.runCycle()

        assertEquals("Regelzyklus fehlgeschlagen: Unbekannter Prio-Charger: Garage", harness.state().lastError)
    }

    @Test
    @Story("Prio-Charger offline")
    fun `charger that is not plugged in gets no current`() {
        val harness = harness()
        harness.charging.snapshots = listOf(
            chargerSnapshot(MULTONNEN_ID, status = ChargerStatus.AVAILABLE, power = 5000.0),
            chargerSnapshot(EINGANGSSEITE_ID),
        )

        harness.runCycle()

        assertEquals(listOf("limit:$MULTONNEN_ID:0", "limit:$EINGANGSSEITE_ID:7"), harness.charging.calls.takeLast(2))
    }

    @Test
    @Story("HA-Token abgelehnt")
    fun `rejected home assistant token skips the cycle without actuation`() {
        val harness = harness()
        harness.states.problem = "Token abgelehnt: Invalid access token"

        harness.runCycle()

        assertTrue(harness.charging.calls.isEmpty())
        assertEquals(SKIP_HA_TOKEN_REJECTED, harness.state().skippedReason)
        assertFalse(harness.state().haConnected)
    }

    @Test
    @Story("Fehlgeschlagener Phasenwechsel")
    fun `failed phase mode change is retried with a growing waiting time`() {
        val harness = harness()
        harness.charging.failingPhaseMode = true
        harness.runCycle()
        harness.charging.calls.clear()

        harness.clock.advance(Duration.ofSeconds(31))
        harness.runCycle()
        harness.charging.calls.clear()

        harness.clock.advance(Duration.ofSeconds(30))
        harness.runCycle()
        assertTrue(harness.charging.calls.none { it.startsWith("phase:") })

        harness.clock.advance(Duration.ofSeconds(30))
        harness.runCycle()
        assertTrue(harness.charging.calls.any { it.startsWith("phase:") })
    }

    @Test
    @Story("Fehlgeschlagener Phasenwechsel")
    fun `failed phase mode change does not block the limit change`() {
        val harness = harness()
        harness.runCycle()
        harness.charging.calls.clear()
        harness.charging.failingPhaseMode = true
        harness.clock.advance(Duration.ofSeconds(31))
        harness.setEntity(harness.properties.ha.entities.pvSurplusCharge, "off")
        harness.setEntity(harness.properties.ha.entities.targetPhases, "1")
        harness.setEntity(harness.properties.ha.entities.targetCurrent, "10.0")

        harness.runCycle()

        assertEquals(
            listOf(
                "phase:$MULTONNEN_ID:1",
                "phase:$EINGANGSSEITE_ID:1",
                "limit:$MULTONNEN_ID:10",
                "limit:$EINGANGSSEITE_ID:0",
            ),
            harness.charging.calls,
        )
    }

    @Test
    @Story("Fehler im Zyklus")
    fun `plain error in a cycle is recorded and does not stop the loop`() {
        val harness = harness()
        harness.charging.snapshotFailure = AssertionError("Invariante verletzt")

        harness.runCycle()

        assertEquals("Regelzyklus fehlgeschlagen: Invariante verletzt", harness.state().lastError)
    }

    @Test
    @Story("Fehler im Zyklus")
    fun `virtual machine error is not swallowed`() {
        val harness = harness()
        harness.charging.snapshotFailure = OutOfMemoryError("simuliert")

        assertThrows(OutOfMemoryError::class.java) { harness.runCycle() }
    }
}
