package de.puetsch.easeeocpp.domain

import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.model.AllocationResult
import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.PhaseMode
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Epic("Regelung")
@Feature("Stromverteilung auf zwei Charger")
class CurrentAllocatorTest {

    private val priorityCharger: ChargerId = "EHDFQLBX"
    private val otherCharger: ChargerId = "EH2NSSVK"
    private val chargerIds = listOf(priorityCharger, otherCharger)
    private val bothEligible = setOf(priorityCharger, otherCharger)

    @Test
    fun `three phases with total 32 splits 16 and 16`() {
        val result = allocate(total = 32, phaseMode = PhaseMode.THREE)

        assertLimits(result, priority = 16, other = 16)
    }

    @Test
    fun `one phase with total 20 gives everything to priority charger`() {
        val result = allocate(total = 20, phaseMode = PhaseMode.ONE)

        assertLimits(result, priority = 20, other = 0)
    }

    @Test
    fun `one phase with total 32 gives full current to priority charger`() {
        val result = allocate(total = 32, phaseMode = PhaseMode.ONE)

        assertLimits(result, priority = 32, other = 0)
    }

    @Test
    fun `three phases with total 8 gives everything to priority charger`() {
        val result = allocate(total = 8, phaseMode = PhaseMode.THREE)

        assertLimits(result, priority = 8, other = 0)
    }

    @Test
    fun `total below minimum current yields zero for both chargers`() {
        val result = allocate(total = 5, phaseMode = PhaseMode.THREE)

        assertLimits(result, priority = 0, other = 0)
    }

    @Test
    fun `total above circuit maximum is capped before splitting`() {
        val result = allocate(total = 40, phaseMode = PhaseMode.THREE)

        assertLimits(result, priority = 16, other = 16)
    }

    @Test
    fun `remaining current beyond circuit maximum is shortened on the other charger`() {
        val properties = ControlProperties(circuitMaxCurrentPerPhase = 30)

        val result = allocate(total = 32, phaseMode = PhaseMode.THREE, properties = properties)

        assertLimits(result, priority = 16, other = 14)
        assertTrue(result.totalCurrent <= 30)
    }

    @Test
    fun `priority charger not eligible gives capped current to the other charger`() {
        val result = allocate(total = 20, phaseMode = PhaseMode.ONE, eligible = setOf(otherCharger))

        assertLimits(result, priority = 0, other = 20)
    }

    @Test
    fun `one phase lets the other charger take the full current when priority is not eligible`() {
        val result = allocate(total = 32, phaseMode = PhaseMode.ONE, eligible = setOf(otherCharger))

        assertLimits(result, priority = 0, other = 32)
    }

    @Test
    fun `three phases cap the other charger at 16 A when priority is not eligible`() {
        val result = allocate(total = 32, phaseMode = PhaseMode.THREE, eligible = setOf(otherCharger))

        assertLimits(result, priority = 0, other = 16)
    }

    @Test
    fun `three phases cap the other charger at 16 A even if max charge current is higher`() {
        val properties = ControlProperties(maxChargeCurrent = 32)

        val result = allocate(total = 32, phaseMode = PhaseMode.THREE, eligible = setOf(otherCharger), properties = properties)

        assertLimits(result, priority = 0, other = 16)
    }

    @Test
    fun `other charger not eligible leaves priority capped and rest unused`() {
        val result = allocate(total = 32, phaseMode = PhaseMode.THREE, eligible = setOf(priorityCharger))

        assertLimits(result, priority = 16, other = 0)
    }

    @Test
    fun `no charger eligible yields zero for both`() {
        val result = allocate(total = 20, phaseMode = PhaseMode.ONE, eligible = emptySet())

        assertLimits(result, priority = 0, other = 0)
    }

    @Test
    fun `rebalance option lowers priority charger so the other gets its minimum current`() {
        val properties = ControlProperties(allowRebalanceForMinCurrent = true)

        val result = allocate(total = 20, phaseMode = PhaseMode.THREE, properties = properties)

        assertLimits(result, priority = 14, other = 6)
    }

    @Test
    fun `without rebalance option the rest below minimum is dropped`() {
        val result = allocate(total = 20, phaseMode = PhaseMode.THREE)

        assertLimits(result, priority = 16, other = 0)
    }

    @Test
    fun `rebalance option does nothing when total is below twice the minimum current`() {
        val properties = ControlProperties(allowRebalanceForMinCurrent = true)

        val result = allocate(total = 10, phaseMode = PhaseMode.THREE, properties = properties)

        assertLimits(result, priority = 10, other = 0)
    }

    @Test
    fun `every charger id appears in the limits in the given order`() {
        val result = allocate(total = 8, phaseMode = PhaseMode.THREE)

        assertEquals(chargerIds, result.limits.map { it.chargerId })
    }

    private fun allocate(
        total: Ampere,
        phaseMode: PhaseMode,
        eligible: Set<ChargerId> = bothEligible,
        properties: ControlProperties = ControlProperties(),
    ): AllocationResult = CurrentAllocator(properties).allocate(
        totalCurrent = total,
        phaseMode = phaseMode,
        priorityCharger = priorityCharger,
        chargerIds = chargerIds,
        eligible = eligible,
    )

    private fun assertLimits(result: AllocationResult, priority: Ampere, other: Ampere) {
        val byCharger = result.limits.associate { it.chargerId to it.current }
        assertEquals(priority, byCharger[priorityCharger])
        assertEquals(other, byCharger[otherCharger])
        assertEquals(priority + other, result.totalCurrent)
    }
}
