package de.puetsch.easeeocpp.domain

import de.puetsch.easeeocpp.domain.model.ChargerSnapshot

/**
 * Bestimmt die Phase mit der höchsten Spannung, wie im Altskript: Spannung plus halber Strom je Phase.
 * Phase 1 gewinnt nur bei strikter Mehrheit, bei Gleichstand fällt die Wahl auf Phase 2 oder 3.
 */
class PhaseVoltageEvaluator {

    fun highestVoltagePhase(snapshot: ChargerSnapshot): Int? {
        val adjustedVoltages = adjustedVoltages(snapshot) ?: return null
        return when {
            adjustedVoltages.phaseOneWins() -> PHASE_ONE
            adjustedVoltages.phaseTwoWins() -> PHASE_TWO
            else -> PHASE_THREE
        }
    }

    private fun adjustedVoltages(snapshot: ChargerSnapshot): AdjustedPhaseVoltages? {
        val phaseOne = adjustedVoltage(snapshot.voltageL1, snapshot.currentL1) ?: return null
        val phaseTwo = adjustedVoltage(snapshot.voltageL2, snapshot.currentL2) ?: return null
        val phaseThree = adjustedVoltage(snapshot.voltageL3, snapshot.currentL3) ?: return null
        return AdjustedPhaseVoltages(phaseOne, phaseTwo, phaseThree)
    }

    private fun adjustedVoltage(voltage: Double?, current: Double?): Double? {
        if (voltage == null || current == null) return null
        return voltage + current / CURRENT_SHARE_DIVISOR
    }

    private data class AdjustedPhaseVoltages(val phaseOne: Double, val phaseTwo: Double, val phaseThree: Double) {
        fun phaseOneWins(): Boolean = phaseOne > phaseTwo && phaseOne > phaseThree

        fun phaseTwoWins(): Boolean = phaseTwo > phaseThree
    }

    private companion object {
        const val PHASE_ONE = 1
        const val PHASE_TWO = 2
        const val PHASE_THREE = 3
        const val CURRENT_SHARE_DIVISOR = 2.0
    }
}
