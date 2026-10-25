package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.domain.model.PhaseMode
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

@Epic("Regelung")
@Feature("Wiederholung Phasenmodus")
class PhaseModeRetryBackoffTest {

    private val backoff = PhaseModeRetryBackoff()

    @Test
    @Story("Erster Versuch")
    fun `attempt without any failure is allowed`() {
        assertTrue(backoff.isAttemptAllowed(PhaseMode.ONE, CONTROL_START))
    }

    @Test
    @Story("Wachsende Wartezeit")
    fun `first retry waits thirty seconds`() {
        backoff.recordFailure(PhaseMode.ONE, CONTROL_START)

        assertFalse(backoff.isAttemptAllowed(PhaseMode.ONE, CONTROL_START.plusSeconds(29)))
        assertTrue(backoff.isAttemptAllowed(PhaseMode.ONE, CONTROL_START.plusSeconds(30)))
    }

    @Test
    @Story("Wachsende Wartezeit")
    fun `waiting doubles with each consecutive failure`() {
        var now = CONTROL_START
        backoff.recordFailure(PhaseMode.ONE, now)
        now = now.plusSeconds(30)
        backoff.recordFailure(PhaseMode.ONE, now)

        assertFalse(backoff.isAttemptAllowed(PhaseMode.ONE, now.plusSeconds(59)))
        assertTrue(backoff.isAttemptAllowed(PhaseMode.ONE, now.plusSeconds(60)))
    }

    @Test
    @Story("Obergrenze")
    fun `waiting never exceeds ten minutes`() {
        var now = CONTROL_START
        repeat(20) {
            backoff.recordFailure(PhaseMode.ONE, now)
            now = now.plus(Duration.ofMinutes(10))
        }
        backoff.recordFailure(PhaseMode.ONE, now)

        assertTrue(backoff.isAttemptAllowed(PhaseMode.ONE, now.plus(Duration.ofMinutes(10))))
        assertFalse(backoff.isAttemptAllowed(PhaseMode.ONE, now.plus(Duration.ofMinutes(9))))
    }

    @Test
    @Story("Anderer Zielwert")
    fun `a different target is attempted immediately`() {
        backoff.recordFailure(PhaseMode.ONE, CONTROL_START)

        assertTrue(backoff.isAttemptAllowed(PhaseMode.THREE, CONTROL_START))
    }

    @Test
    @Story("Erfolg")
    fun `success clears the waiting`() {
        backoff.recordFailure(PhaseMode.ONE, CONTROL_START)
        backoff.recordSuccess()

        assertTrue(backoff.isAttemptAllowed(PhaseMode.ONE, CONTROL_START))
    }
}
