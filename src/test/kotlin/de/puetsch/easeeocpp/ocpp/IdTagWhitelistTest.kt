package de.puetsch.easeeocpp.ocpp

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.config.OcppProperties
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

private const val FAKE_TAG = "TEST-TAG-A"
private const val OTHER_FAKE_TAG = "TEST-TAG-B"

@Epic("OCPP")
@Feature("RFID-Whitelist")
class IdTagWhitelistTest {

    @Test
    @Story("Freigabe")
    fun listedIdTagIsAllowed() {
        assertTrue(whitelistOf(FAKE_TAG).isAllowed(FAKE_TAG))
    }

    @Test
    @Story("Freigabe")
    fun comparisonIgnoresCase() {
        assertTrue(whitelistOf(FAKE_TAG).isAllowed(FAKE_TAG.lowercase()))
    }

    @Test
    @Story("Freigabe")
    fun configuredEntryWithSurroundingWhitespaceMatches() {
        assertTrue(whitelistOf("  $FAKE_TAG  ").isAllowed(FAKE_TAG))
    }

    @Test
    @Story("Freigabe")
    fun unknownIdTagIsRejected() {
        assertFalse(whitelistOf(FAKE_TAG).isAllowed(OTHER_FAKE_TAG))
    }

    @Test
    @Story("Fail-closed")
    fun emptyListRejectsEverything() {
        val whitelist = whitelistOf()

        assertFalse(whitelist.hasEntries())
        assertFalse(whitelist.isAllowed(FAKE_TAG))
    }

    @Test
    @Story("Fail-closed")
    fun blankEntriesAreIgnored() {
        val whitelist = whitelistOf("", "   ")

        assertFalse(whitelist.hasEntries())
        assertFalse(whitelist.isAllowed(""))
    }

    @Test
    @Story("Start-Warnung")
    fun emptyListWarnsAtStartup() {
        val warnings = captureWarnings { whitelistOf().warnIfEmpty() }

        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("EASEE_OCPP_ALLOWEDIDTAGS"))
    }

    @Test
    @Story("Start-Warnung")
    fun filledListDoesNotWarnAtStartup() {
        val warnings = captureWarnings { whitelistOf(FAKE_TAG).warnIfEmpty() }

        assertTrue(warnings.isEmpty())
    }

    private fun whitelistOf(vararg tags: String): IdTagWhitelist =
        IdTagWhitelist(AppProperties(ocpp = OcppProperties(allowedIdTags = tags.toList())))

    private fun captureWarnings(action: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(IdTagWhitelist::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            action()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.map { it.formattedMessage }
    }
}
