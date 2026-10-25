package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.model.ControlAction
import de.puetsch.easeeocpp.domain.model.PhaseMode
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@Epic("Regelung")
@Feature("Verlauf")
class ControlHistoryTest {

    @TempDir
    lateinit var tempDir: Path

    private fun historyProperties(file: Path, size: Int = 50): AppProperties = AppProperties(
        control = ControlProperties(historyFile = file.toString(), historySize = size),
    )

    private fun action(minute: Int, current: Int = 7): ControlAction = ControlAction(
        at = CONTROL_START.plusSeconds(minute * 60L),
        chargerId = MULTONNEN_ID,
        kind = KIND_CURRENT_LIMIT,
        phaseMode = null,
        current = current,
        outcome = OUTCOME_SENT,
    )

    @Test
    @Story("Persistenz")
    fun `recorded actions survive a restart`() {
        val file = tempDir.resolve("history.jsonl")
        val phaseAction = ControlAction(CONTROL_START, EINGANGSSEITE_ID, KIND_PHASE_MODE, PhaseMode.THREE, null, OUTCOME_SENT)
        ControlHistory(historyProperties(file)).apply {
            record(phaseAction)
            record(action(minute = 1))
        }

        val reloaded = ControlHistory(historyProperties(file)).recent()

        assertEquals(listOf(phaseAction, action(minute = 1)), reloaded)
    }

    @Test
    @Story("Persistenz")
    fun `missing history file starts with an empty history`() {
        val history = ControlHistory(historyProperties(tempDir.resolve("none.jsonl")))

        assertTrue(history.recent().isEmpty())
    }

    @Test
    @Story("Persistenz")
    fun `history file is created together with its missing directory`() {
        val file = tempDir.resolve("data").resolve("control-history.jsonl")

        ControlHistory(historyProperties(file)).record(action(minute = 0))

        assertTrue(Files.exists(file))
    }

    @Test
    @Story("Laden")
    fun `only the newest entries up to the history size are loaded`() {
        val file = tempDir.resolve("history.jsonl")
        ControlHistory(historyProperties(file, size = 10)).apply {
            (0..4).forEach { record(action(minute = it)) }
        }

        val loaded = ControlHistory(historyProperties(file, size = 2)).recent()

        assertEquals(listOf(action(minute = 3), action(minute = 4)), loaded)
    }

    @Test
    @Story("Begrenzung")
    fun `memory keeps only the newest entries up to the history size`() {
        val history = ControlHistory(historyProperties(tempDir.resolve("history.jsonl"), size = 2))

        (0..2).forEach { history.record(action(minute = it)) }

        assertEquals(listOf(action(minute = 1), action(minute = 2)), history.recent())
    }

    @Test
    @Story("Schreibfehler")
    fun `write failure is logged and does not interrupt recording`() {
        val blocker = tempDir.resolve("blocker")
        Files.writeString(blocker, "keine Datei, sondern ein Hindernis")
        val history = ControlHistory(historyProperties(blocker.resolve("sub").resolve("history.jsonl")))

        history.record(action(minute = 0))

        assertEquals(listOf(action(minute = 0)), history.recent())
    }

    @Test
    @Story("Zeitstempel")
    fun `timestamps round trip as instants`() {
        val file = tempDir.resolve("history.jsonl")
        val at = Instant.parse("2026-10-25T10:15:30Z")
        ControlHistory(historyProperties(file)).record(action(minute = 0).copy(at = at))

        assertEquals(at, ControlHistory(historyProperties(file)).recent().single().at)
    }
}
