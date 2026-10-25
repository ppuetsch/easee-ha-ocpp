package de.puetsch.easeeocpp.domain.model

import java.time.Instant

data class SurplusInputs(
    val pvPower: Watt,
    val gridPower: Watt,
    val batteryPower: Watt,
    val evPower: Watt,
    val securityBuffer: Watt,
    val preferCarOverBattery: Boolean,
    val phaseMode: PhaseMode,
)

data class SurplusResult(
    val enoughPvPower: Boolean,
    val minRequiredPvPower: Watt,
    val rawTargetPower: Watt,
    val targetPower: Watt,
    val targetCurrent: Ampere,
)

data class ChargeSettings(
    val surplusChargeEnabled: Boolean,
    val surplusPhaseMode: PhaseMode,
    val fixedPhaseMode: PhaseMode,
    val fixedCurrent: Ampere,
    val minimumCurrent: Ampere,
    val priorityCharger: ChargerId,
)

data class ChargerSnapshot(
    val chargerId: ChargerId,
    val connected: Boolean,
    val status: ChargerStatus,
    val power: Watt,
    val currentL1: Double?,
    val currentL2: Double?,
    val currentL3: Double?,
    val voltageL1: Double?,
    val voltageL2: Double?,
    val voltageL3: Double?,
    val lastMeterValueAt: Instant?,
    val statusSince: Instant?,
)

data class ChargerLimit(val chargerId: ChargerId, val current: Ampere)

data class AllocationResult(val limits: List<ChargerLimit>, val totalCurrent: Ampere)

data class ControlDecision(
    val decidedAt: Instant,
    val phaseMode: PhaseMode,
    val allocation: AllocationResult,
    val surplus: SurplusResult?,
    val phaseWithHighestVoltage: Int?,
    val reason: String,
    /** Name des Verteilungsmodus: PRIORITIZED, SHARED oder SINGLE_CHARGER; null, wenn kein Charger berechtigt ist. */
    val allocationMode: String? = null,
)

data class ControlAction(
    val at: Instant,
    val chargerId: ChargerId?,
    val kind: String,
    val phaseMode: PhaseMode?,
    val current: Ampere?,
    val outcome: String,
)
