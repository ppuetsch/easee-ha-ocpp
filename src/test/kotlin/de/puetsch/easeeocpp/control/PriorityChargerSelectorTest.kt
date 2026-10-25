package de.puetsch.easeeocpp.control

import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

@Epic("Regelung")
@Feature("Prio-Charger-Wechsel")
class PriorityChargerSelectorTest {

    private val switchDelay = Duration.ofSeconds(60)
    private val selector = PriorityChargerSelector(switchDelay)

    @Test
    @Story("Erster Wunsch")
    fun `first request is adopted immediately`() {
        assertEquals(MULTONNEN_ID, selector.select(MULTONNEN_ID, CONTROL_START))
    }

    @Test
    @Story("Mindestdauer")
    fun `switch becomes effective only after the switch delay`() {
        selector.select(MULTONNEN_ID, CONTROL_START)

        assertEquals(MULTONNEN_ID, selector.select(EINGANGSSEITE_ID, CONTROL_START.plusSeconds(10)))
        assertEquals(MULTONNEN_ID, selector.select(EINGANGSSEITE_ID, CONTROL_START.plusSeconds(69)))
        assertEquals(EINGANGSSEITE_ID, selector.select(EINGANGSSEITE_ID, CONTROL_START.plusSeconds(70)))
    }

    @Test
    @Story("Mindestdauer")
    fun `returning to the active charger cancels a pending switch`() {
        selector.select(MULTONNEN_ID, CONTROL_START)
        selector.select(EINGANGSSEITE_ID, CONTROL_START.plusSeconds(10))
        selector.select(MULTONNEN_ID, CONTROL_START.plusSeconds(20))

        assertEquals(MULTONNEN_ID, selector.select(EINGANGSSEITE_ID, CONTROL_START.plusSeconds(30)))
        assertEquals(MULTONNEN_ID, selector.select(EINGANGSSEITE_ID, CONTROL_START.plusSeconds(89)))
        assertEquals(EINGANGSSEITE_ID, selector.select(EINGANGSSEITE_ID, CONTROL_START.plusSeconds(90)))
    }
}
