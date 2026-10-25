package de.puetsch.easeeocpp.ocpp

import io.mockk.every
import io.mockk.mockk
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val CHARGER_ID = "EHDFQLBX"

@Epic("OCPP")
@Feature("Sitzungsregister")
class OcppSessionRegistryTest {
    private val registry = OcppSessionRegistry()
    private val firstSession = sessionFor(CHARGER_ID)
    private val secondSession = sessionFor(CHARGER_ID)

    @Test
    @Story("Ersetzen")
    fun newSessionReplacesAndReturnsOldOne() {
        registry.register(firstSession)

        val replaced = registry.register(secondSession)

        assertSame(firstSession, replaced)
        assertSame(secondSession, registry.find(CHARGER_ID))
    }

    @Test
    @Story("Ersetzen")
    fun unregisteringReplacedSessionDoesNotRemoveNewOne() {
        registry.register(firstSession)
        registry.register(secondSession)

        assertFalse(registry.unregister(firstSession))

        assertTrue(registry.isConnected(CHARGER_ID))
    }

    @Test
    @Story("Ersetzen")
    fun unregisteringCurrentSessionDisconnectsCharger() {
        registry.register(firstSession)

        assertTrue(registry.unregister(firstSession))

        assertNull(registry.find(CHARGER_ID))
        assertFalse(registry.isConnected(CHARGER_ID))
    }

    private fun sessionFor(chargerId: String): OcppChargerSession {
        val session: OcppChargerSession = mockk(relaxed = true)
        every { session.chargePointId } returns chargerId
        return session
    }
}
