package de.puetsch.easeeocpp.ha

import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

@Epic("Home Assistant")
@Feature("Reconnect")
class HaReconnectBackoffTest {
    private val minimum = Duration.ofSeconds(1)
    private val maximum = Duration.ofSeconds(8)
    private val backoff = HaReconnectBackoff(minimumDelay = minimum, maximumDelay = maximum)

    @Test
    @Story("Exponentielles Wachstum")
    fun `Wartezeit verdoppelt sich bis zur Obergrenze`() {
        val delays = List(5) { backoff.takeNextDelay() }

        assertEquals(
            listOf(1L, 2L, 4L, 8L, 8L).map { Duration.ofSeconds(it) },
            delays,
        )
    }

    @Test
    @Story("Zuruecksetzen")
    fun `reset startet wieder bei der Mindestwartezeit`() {
        repeat(3) { backoff.takeNextDelay() }

        backoff.reset()

        assertEquals(minimum, backoff.takeNextDelay())
    }
}
