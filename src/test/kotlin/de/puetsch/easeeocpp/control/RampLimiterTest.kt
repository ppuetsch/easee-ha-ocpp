package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.model.ChargerLimit
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

private const val CHARGER = "EH-A"
private const val OTHER_CHARGER = "EH-B"
private val START: Instant = Instant.parse("2026-10-25T10:00:00Z")

@Epic("Regelung")
@Feature("Rampe")
class RampLimiterTest {
    private val limiter = RampLimiter(ControlProperties())

    private fun step(
        secondsSinceStart: Long,
        desired: Int,
        applied: Int?,
        reductionsOnly: Boolean = false,
        startingPoint: Int = 0,
    ): Int = limiter.limit(
        START.plusSeconds(secondsSinceStart),
        listOf(ChargerLimit(CHARGER, desired)),
        applied?.let { mapOf(CHARGER to it) }.orEmpty(),
        startingPoint = { startingPoint },
        reductionsOnly = reductionsOnly,
    ).single().current

    @Test
    @Story("Anstieg")
    fun `Strom steigt hoechstens um 1 A je 15 s`() {
        assertEquals(11, step(0, desired = 16, applied = 10))
        assertEquals(11, step(14, desired = 16, applied = 11))
        assertEquals(12, step(15, desired = 16, applied = 11))
        assertEquals(12, step(29, desired = 16, applied = 12))
        assertEquals(13, step(30, desired = 16, applied = 12))
    }

    @Test
    @Story("Anstieg")
    fun `Anstieg endet am Ziel`() {
        assertEquals(12, step(0, desired = 12, applied = 12))
        assertEquals(12, step(30, desired = 12, applied = 11))
    }

    @Test
    @Story("Absenkung")
    fun `Absenkung gilt sofort auf den Zielwert`() {
        assertEquals(7, step(0, desired = 7, applied = 16))
    }

    @Test
    @Story("Absenkung")
    fun `nach einer Absenkung muss der Anstieg wieder 15 s warten`() {
        assertEquals(7, step(0, desired = 7, applied = 16))
        assertEquals(7, step(10, desired = 12, applied = 7))
        assertEquals(8, step(15, desired = 12, applied = 7))
    }

    @Test
    @Story("Absenkung")
    fun `in der Settle-Pause gehen Absenkungen durch, Erhoehungen nicht`() {
        assertEquals(9, step(0, desired = 9, applied = 12, reductionsOnly = true))
        assertEquals(9, step(60, desired = 14, applied = 9, reductionsOnly = true))
    }

    @Test
    @Story("Start")
    fun `pausierter Charger startet direkt mit dem Mindeststrom`() {
        assertEquals(6, step(0, desired = 16, applied = 0))
        assertEquals(0, step(100, desired = 0, applied = 0))
        assertEquals(6, step(200, desired = 6, applied = 0))
    }

    @Test
    @Story("Start")
    fun `ohne bekanntes Limit startet die Rampe beim gemessenen Strom`() {
        assertEquals(10, step(0, desired = 16, applied = null, startingPoint = 9))
        assertEquals(6, step(100, desired = 16, applied = null, startingPoint = 0))
    }

    @Test
    @Story("Mehrere Charger")
    fun `jeder Charger hat seine eigene Rampe`() {
        val result = limiter.limit(
            START,
            listOf(ChargerLimit(CHARGER, 16), ChargerLimit(OTHER_CHARGER, 6)),
            mapOf(CHARGER to 10, OTHER_CHARGER to 12),
            startingPoint = { 0 },
            reductionsOnly = false,
        )

        assertEquals(listOf(11, 6), result.map { it.current })
    }
}
