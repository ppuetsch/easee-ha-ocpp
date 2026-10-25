package de.puetsch.easeeocpp.domain.model

typealias Watt = Double
typealias Ampere = Int
typealias ChargerId = String
typealias EntityId = String

enum class PhaseMode(val phaseCount: Int) {
    ONE(1),
    THREE(3);

    companion object {
        fun fromPhaseCount(phaseCount: Int): PhaseMode =
            entries.firstOrNull { it.phaseCount == phaseCount }
                ?: throw IllegalArgumentException("phase mode unknown $phaseCount")
    }
}

enum class ChargerStatus {
    AVAILABLE, PREPARING, CHARGING, SUSPENDED_EV, SUSPENDED_EVSE, FINISHING, RESERVED, UNAVAILABLE, FAULTED;

    fun hasVehiclePluggedIn(): Boolean = this !in setOf(AVAILABLE, UNAVAILABLE, FAULTED)
}
