package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.ha.HaHeartbeatPublisher
import io.mockk.every
import io.mockk.mockk
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

@Epic("Regelung")
@Feature("Zustandsspeicher")
class ControlStateHolderTest {

    @Test
    @Story("Startzustand")
    fun `initial state reports no cycle yet and the configured actuation mode`() {
        val holder = ControlStateHolder(heartbeatReturning(null), AppProperties())

        val state = holder.current()

        assertEquals(null, state.evaluatedAt)
        assertFalse(state.actuationEnabled)
        assertEquals("Noch kein Regelzyklus gelaufen", state.skippedReason)
    }

    @Test
    @Story("Heartbeat")
    fun `last heartbeat is taken from the heartbeat publisher on read`() {
        val holder = ControlStateHolder(heartbeatReturning(CONTROL_START), AppProperties())

        assertEquals(CONTROL_START, holder.current().lastHeartbeatAt)
    }

    @Test
    @Story("Aktualisierung")
    fun `update replaces the state with the transformed one`() {
        val holder = ControlStateHolder(heartbeatReturning(null), AppProperties())

        holder.update { it.copy(haConnected = true, skippedReason = null) }

        assertEquals(true, holder.current().haConnected)
        assertEquals(null, holder.current().skippedReason)
    }

    private fun heartbeatReturning(at: java.time.Instant?): HaHeartbeatPublisher = mockk {
        every { lastSuccessfulHeartbeatAt() } returns at
    }
}
