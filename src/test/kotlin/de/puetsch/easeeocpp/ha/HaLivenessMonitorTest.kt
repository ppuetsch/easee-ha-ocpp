package de.puetsch.easeeocpp.ha

import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Epic("Home Assistant")
@Feature("Verbindungsüberwachung")
class HaLivenessMonitorTest {

    private val monitor = HaLivenessMonitor(maxMissedPongs = 2, maxUnansweredCalls = 3)

    @Test
    @Story("Ping")
    fun `a connection answering every ping is not dead`() {
        repeat(5) {
            assertFalse(monitor.registerPingTick())
            monitor.pingSent()
            monitor.pongReceived()
        }
    }

    @Test
    @Story("Ping")
    fun `two pongs missing in a row declare the connection dead`() {
        assertFalse(monitor.registerPingTick())
        monitor.pingSent()

        assertFalse(monitor.registerPingTick())
        monitor.pingSent()

        assertTrue(monitor.registerPingTick())
    }

    @Test
    @Story("Ping")
    fun `a pong between two missing pongs resets the count`() {
        monitor.registerPingTick()
        monitor.pingSent()
        assertFalse(monitor.registerPingTick())
        monitor.pingSent()
        monitor.pongReceived()

        assertFalse(monitor.registerPingTick())
    }

    @Test
    @Story("Service-Aufrufe")
    fun `three unanswered calls in a row declare the connection dead`() {
        assertFalse(monitor.callUnanswered())
        assertFalse(monitor.callUnanswered())

        assertTrue(monitor.callUnanswered())
    }

    @Test
    @Story("Service-Aufrufe")
    fun `an answered call resets the unanswered count`() {
        monitor.callUnanswered()
        monitor.callUnanswered()
        monitor.callAnswered()

        assertFalse(monitor.callUnanswered())
        assertFalse(monitor.callUnanswered())
    }

    @Test
    @Story("Reset")
    fun `reset clears pending pings and counts`() {
        monitor.registerPingTick()
        monitor.pingSent()
        monitor.registerPingTick()
        monitor.callUnanswered()
        monitor.callUnanswered()

        monitor.reset()

        assertFalse(monitor.registerPingTick())
        assertFalse(monitor.callUnanswered())
    }
}
