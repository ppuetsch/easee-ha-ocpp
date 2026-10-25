package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.ControlAction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val historyMapper: JsonMapper = JsonMapper.builder()
    .addModule(kotlinModule())
    .build()

/**
 * Verlauf der Eingriffe: die letzten Einträge im Speicher, alle Einträge als JSON-Lines-Datei.
 * Schreibfehler werden nur protokolliert, damit ein volles Laufwerk die Regelung nicht stoppt.
 */
@Component
class ControlHistory(properties: AppProperties) {
    private val log = LoggerFactory.getLogger(ControlHistory::class.java)
    private val historyPath: Path = Path.of(properties.control.historyFile)
    private val historySize: Int = properties.control.historySize
    private val actions: ArrayDeque<ControlAction> = ArrayDeque(loadLastEntries())

    @Synchronized
    fun record(action: ControlAction) {
        actions.addLast(action)
        dropOldestBeyondLimit()
        appendToFile(action)
    }

    @Synchronized
    fun recent(): List<ControlAction> = actions.toList()

    private fun dropOldestBeyondLimit() {
        while (actions.size > historySize) actions.removeFirst()
    }

    private fun loadLastEntries(): List<ControlAction> {
        val lines = readLines()
        return lines
            .mapNotNull(::parseLine)
            .takeLast(historySize)
    }

    private fun readLines(): List<String> {
        if (!Files.exists(historyPath)) return emptyList()
        return try {
            Files.readAllLines(historyPath)
        } catch (failure: IOException) {
            log.warn("Verlauf {} konnte nicht gelesen werden: {}", historyPath, failure.message)
            emptyList()
        }
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
}
