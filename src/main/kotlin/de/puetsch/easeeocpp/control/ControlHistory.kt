package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.ControlAction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

private val historyMapper: JsonMapper = JsonMapper.builder()
    .addModule(kotlinModule())
    .build()

private const val HISTORY_MAX_BYTES = 1024L * 1024L
private const val TAIL_READ_BYTES = 64L * 1024L
private const val BACKUP_SUFFIX = ".1"

/**
 * Verlauf der Eingriffe: die letzten Einträge im Speicher, alle Einträge als JSON-Lines-Datei.
 * Erfolgreiche Profil-Erneuerungen sind Routine und landen nicht im Verlauf. Ist die Datei größer als
 * [HISTORY_MAX_BYTES], wird sie als einzelnes Backup umbenannt. Schreibfehler werden nur protokolliert,
 * damit ein volles Laufwerk die Regelung nicht stoppt.
 */
@Component
class ControlHistory(properties: AppProperties) {
    private val log = LoggerFactory.getLogger(ControlHistory::class.java)
    private val historyPath: Path = Path.of(properties.control.historyFile)
    private val backupPath: Path = historyPath.resolveSibling("${historyPath.fileName}$BACKUP_SUFFIX")
    private val historySize: Int = properties.control.historySize
    private val actions: ArrayDeque<ControlAction> = ArrayDeque(loadLastEntries())

    @Synchronized
    fun record(action: ControlAction) {
        if (action.isRoutineRefresh()) return
        actions.addLast(action)
        dropOldestBeyondLimit()
        appendToFile(action)
    }

    @Synchronized
    fun recent(): List<ControlAction> = actions.toList()

    private fun ControlAction.isRoutineRefresh(): Boolean =
        kind == KIND_CURRENT_REFRESH && !outcome.startsWith(OUTCOME_FAILED_PREFIX)

    private fun dropOldestBeyondLimit() {
        while (actions.size > historySize) actions.removeFirst()
    }

    private fun loadLastEntries(): List<ControlAction> = readTailLines()
        .mapNotNull(::parseLine)
        .takeLast(historySize)

    /** Liest nur das Dateiende; ein angeschnittenes erstes Zeilenfragment wird verworfen. */
    private fun readTailLines(): List<String> {
        if (!Files.exists(historyPath)) return emptyList()
        return try {
            val tailStart = maxOf(0L, Files.size(historyPath) - TAIL_READ_BYTES)
            val tailText = readBytesFrom(tailStart)
            val completeText = if (tailStart > 0) tailText.substringAfter('\n', missingDelimiterValue = "") else tailText
            completeText.lines().filter { it.isNotBlank() }
        } catch (failure: IOException) {
            log.warn("Verlauf {} konnte nicht gelesen werden: {}", historyPath, failure.message)
            emptyList()
        }
    }

    private fun readBytesFrom(start: Long): String {
        val bytes = Files.newInputStream(historyPath).use { stream ->
            stream.skipNBytes(start)
            stream.readAllBytes()
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun parseLine(line: String): ControlAction? = try {
        historyMapper.readValue(line, ControlAction::class.java)
    } catch (failure: RuntimeException) {
        log.warn("Ungültige Verlaufszeile übersprungen: {}", failure.message)
        null
    }

    private fun appendToFile(action: ControlAction) {
        try {
            historyPath.parent?.let { Files.createDirectories(it) }
            rotateIfTooLarge()
            Files.writeString(
                historyPath,
                historyMapper.writeValueAsString(action) + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (failure: IOException) {
            log.warn("Verlauf {} konnte nicht geschrieben werden: {}", historyPath, failure.message)
        }
    }

    private fun rotateIfTooLarge() {
        if (!Files.exists(historyPath) || Files.size(historyPath) <= HISTORY_MAX_BYTES) return
        Files.move(historyPath, backupPath, StandardCopyOption.REPLACE_EXISTING)
    }
}
