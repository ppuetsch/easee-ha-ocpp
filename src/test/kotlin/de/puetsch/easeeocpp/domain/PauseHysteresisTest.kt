package de.puetsch.easeeocpp.domain

import de.puetsch.easeeocpp.config.ControlProperties
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@Epic("Regelung")
@Feature("Pausen-Hysterese")
class PauseHysteresisTest {

    private val properties = ControlProperties()
    private lateinit var clock: MutableClock
    private lateinit var hysteresis: PauseHysteresis

    @BeforeEach
    fun setUp() {
        clock = MutableClock(Instant.parse("2026-10-25T10:00:00Z"))
        hysteresis = PauseHysteresis(clock, properties)
    }

    @Test
    fun `zero demand without previous charge stays zero`() {
        assertEquals(0, hysteresis.filter(0))
    }

    @Test
    fun `positive demand passes through unchanged`() {
        assertEquals(16, hysteresis.filter(16))
    }

    @Test
    fun `zero demand right after charging holds minimum current`() {
        hysteresis.filter(16)

        assertEquals(properties.minChargeCurrent, hysteresis.filter(0))
    }

    @Test
    fun `zero demand exactly at pause delay still holds minimum current`() {
        hysteresis.filter(16)
        hysteresis.filter(0)
        clock.advance(properties.pauseDelay)

        assertEquals(properties.minChargeCurrent, hysteresis.filter(0))
    }

    @Test
    fun `zero demand longer than pause delay pauses charging`() {
        hysteresis.filter(16)
        hysteresis.filter(0)
        clock.advance(properties.pauseDelay.plusSeconds(1))

        assertEquals(0, hysteresis.filter(0))
    }

    @Test
    fun `once paused, zero demand stays zero`() {
        hysteresis.filter(16)
        hysteresis.filter(0)
        clock.advance(properties.pauseDelay.plusSeconds(1))
        hysteresis.filter(0)

        clock.advance(Duration.ofSeconds(5))

        assertEquals(0, hysteresis.filter(0))
    }

    @Test
    fun `renewed positive demand resets the zero demand timer`() {
        hysteresis.filter(16)
        hysteresis.filter(0)
        clock.advance(Duration.ofSeconds(30))
        hysteresis.filter(10)
        hysteresis.filter(0)
        clock.advance(Duration.ofSeconds(50))

        assertEquals(properties.minChargeCurrent, hysteresis.filter(0))
    }

    @Test
    fun `timer restarts after renewed positive demand and eventually pauses`() {
        hysteresis.filter(16)
        hysteresis.filter(0)
        clock.advance(Duration.ofSeconds(30))
        hysteresis.filter(10)
        hysteresis.filter(0)
        clock.advance(properties.pauseDelay.plusSeconds(1))

        assertEquals(0, hysteresis.filter(0))
    }
}
