package de.puetsch.easeeocpp.domain

import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.PhaseMode
import de.puetsch.easeeocpp.domain.model.SurplusInputs
import de.puetsch.easeeocpp.domain.model.SurplusResult
import de.puetsch.easeeocpp.domain.model.Watt

/**
 * Leistung zu Zielstrom, 1:1 nach easee/easee.py. Die PV-Schwelle wird vor allem anderen geprüft;
 * unterhalb davon gibt es keine Überschussladung.
 */
class SurplusCalculator(private val properties: ControlProperties) {

    fun calculate(inputs: SurplusInputs): SurplusResult {
        val minRequiredPvPower = minRequiredPvPower(inputs.securityBuffer)
        if (inputs.pvPower < minRequiredPvPower) {
            return notEnoughPvPower(minRequiredPvPower)
        }
        val rawTargetPower = rawTargetPower(inputs)
        val targetPower = rawTargetPower - inputs.securityBuffer
        return SurplusResult(
            enoughPvPower = true,
            minRequiredPvPower = minRequiredPvPower,
            rawTargetPower = rawTargetPower,
            targetPower = targetPower,
            targetCurrent = targetCurrent(targetPower, inputs.phaseMode),
        )
    }

    private fun notEnoughPvPower(minRequiredPvPower: Watt): SurplusResult = SurplusResult(
        enoughPvPower = false,
        minRequiredPvPower = minRequiredPvPower,
        rawTargetPower = 0.0,
        targetPower = 0.0,
        targetCurrent = 0,
    )

    private fun minRequiredPvPower(securityBuffer: Watt): Watt =
        minChargePower() + securityBuffer + properties.minHousePower

    private fun minChargePower(): Watt = (properties.minChargeCurrent * properties.gridVoltage).toDouble()

    private fun rawTargetPower(inputs: SurplusInputs): Watt {
        val surplusBeforeBuffer = inputs.evPower - inputs.gridPower + batteryContribution(inputs)
        return maxOf(surplusBeforeBuffer, 0.0)
    }

    private fun batteryContribution(inputs: SurplusInputs): Watt =
        if (inputs.preferCarOverBattery) inputs.batteryPower else minOf(inputs.batteryPower, 0.0)

    private fun targetCurrent(targetPower: Watt, phaseMode: PhaseMode): Ampere {
        if (targetPower <= 0.0) return 0
        val roundedCurrent = roundedCurrent(targetPower, phaseMode)
        return when {
            roundedCurrent >= properties.maxChargeCurrent -> properties.maxChargeCurrent
            roundedCurrent >= properties.minChargeCurrent -> roundedCurrent
            else -> 0
        }
    }

    /** Banker's rounding wie Python `round()` im Altskript. */
    private fun roundedCurrent(targetPower: Watt, phaseMode: PhaseMode): Ampere {
        val voltageTimesPhases = properties.gridVoltage * phaseMode.phaseCount
        return Math.rint(targetPower / voltageTimesPhases).toInt()
    }
}
