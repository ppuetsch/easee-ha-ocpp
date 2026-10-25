package de.puetsch.easeeocpp.ha

import de.puetsch.easeeocpp.ports.HaEntityState
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@Epic("Home Assistant")
@Feature("Zustands-Cache")
class HaStateCacheTest {
    private val cache = HaStateCache()

    @Test
    @Story("replaceAll")
    fun `replaceAll entfernt veraltete Entities und uebernimmt den Bestand`() {
        cache.put(HaEntityState("sensor.alt", "1", null))
        cache.put(HaEntityState("sensor.bleibt", "2", null))

        cache.replaceAll(listOf(HaEntityState("sensor.bleibt", "3", null), HaEntityState("sensor.neu", "4", null)))

        assertNull(cache.get("sensor.alt"))
        assertEquals("3", cache.get("sensor.bleibt")?.state)
        assertEquals("4", cache.get("sensor.neu")?.state)
    }

    @Test
    @Story("put und remove")
    fun `put ueberschreibt und remove entfernt`() {
        cache.put(HaEntityState("sensor.a", "1", null))
        cache.put(HaEntityState("sensor.a", "2", null))
        assertEquals("2", cache.get("sensor.a")?.state)

        cache.remove("sensor.a")

        assertNull(cache.get("sensor.a"))
    }
}
