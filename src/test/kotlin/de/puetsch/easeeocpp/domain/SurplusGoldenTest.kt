package de.puetsch.easeeocpp.domain

import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.model.PhaseMode
import de.puetsch.easeeocpp.domain.model.SurplusInputs
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.math.abs

@Epic("Regelung")
@Feature("Golden-Tests gegen Python-Referenz")
class SurplusGoldenTest {

    private val calculator = SurplusCalculator(ControlProperties())
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    @Test
    fun `calculator reproduces python reference for every golden case`() {
        val goldenCases = loadGoldenCases()
        val mismatches = goldenCases.flatMap { describeMismatches(it) }

        assertTrue(goldenCases.size >= MINIMUM_GOLDEN_CASE_COUNT, "zu wenige Golden-Faelle: ${goldenCases.size}")
        assertTrue(mismatches.isEmpty(), mismatches.joinToString(separator = "\n"))
    }

    @Test
    fun `golden cases include exact half-ampere rounding boundaries`() {
        val boundaryCases = loadGoldenCases().filter { isExactHalfAmpere(it) }

        assertTrue(boundaryCases.size >= MINIMUM_HALF_AMPERE_CASE_COUNT, "Rundungsgrenzen: ${boundaryCases.size}")
    }

    private fun isExactHalfAmpere(goldenCase: GoldenCase): Boolean {
        val amperes = goldenCase.expectedTargetPower / (GRID_VOLTAGE * goldenCase.phases)
        return abs(amperes % 1.0 - 0.5) < HALF_AMPERE_EPSILON
    }

    private fun describeMismatches(goldenCase: GoldenCase): List<String> {
        val actual = calculator.calculate(goldenCase.toSurplusInputs())
        val caseName = "$goldenCase"
        return buildList {
            if (actual.enoughPvPower != goldenCase.expectedEnoughPvPower) {
                add("$caseName: enoughPvPower ${actual.enoughPvPower} != ${goldenCase.expectedEnoughPvPower}")
            }
            if (actual.targetCurrent != goldenCase.expectedTargetCurrent) {
                add("$caseName: targetCurrent ${actual.targetCurrent} != ${goldenCase.expectedTargetCurrent}")
            }
            if (!isWithinTolerance(actual.targetPower, goldenCase.expectedTargetPower)) {
                add("$caseName: targetPower ${actual.targetPower} != ${goldenCase.expectedTargetPower}")
            }
            if (!isWithinTolerance(actual.rawTargetPower, goldenCase.expectedRawTargetPower)) {
                add("$caseName: rawTargetPower ${actual.rawTargetPower} != ${goldenCase.expectedRawTargetPower}")
            }
        }
    }

    private fun isWithinTolerance(actual: Double, expected: Double): Boolean =
        abs(actual - expected) <= POWER_TOLERANCE_WATT

    private fun loadGoldenCases(): List<GoldenCase> {
        val resourceStream = requireNotNull(javaClass.getResourceAsStream(GOLDEN_RESOURCE)) {
            "Ressource $GOLDEN_RESOURCE fehlt"
        }
        return resourceStream.use { mapper.readValue(it, Array<GoldenCase>::class.java).toList() }
    }

    data class GoldenCase(
        val pvPower: Double,
        val gridPower: Double,
        val batteryPower: Double,
        val evPower: Double,
        val securityBuffer: Double,
        val preferCarOverBattery: Boolean,
        val phases: Int,
        val expectedEnoughPvPower: Boolean,
        val expectedMinRequiredPvPower: Double,
        val expectedRawTargetPower: Double,
        val expectedTargetPower: Double,
        val expectedTargetCurrent: Int,
    ) {
        fun toSurplusInputs(): SurplusInputs = SurplusInputs(
            pvPower = pvPower,
            gridPower = gridPower,
            batteryPower = batteryPower,
            evPower = evPower,
            securityBuffer = securityBuffer,
            preferCarOverBattery = preferCarOverBattery,
            phaseMode = PhaseMode.fromPhaseCount(phases),
        )
    }

    private companion object {
        const val GOLDEN_RESOURCE = "/golden/golden-cases.json"
        const val MINIMUM_GOLDEN_CASE_COUNT = 200
        const val POWER_TOLERANCE_WATT = 1e-9
        const val MINIMUM_HALF_AMPERE_CASE_COUNT = 10
        const val HALF_AMPERE_EPSILON = 1e-9
        const val GRID_VOLTAGE = 240.0
    }
}
