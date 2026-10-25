package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.CurrentAllocator
import de.puetsch.easeeocpp.domain.EligibilityPolicy
import de.puetsch.easeeocpp.domain.PauseHysteresis
import de.puetsch.easeeocpp.domain.PhaseVoltageEvaluator
import de.puetsch.easeeocpp.domain.SurplusCalculator
import de.puetsch.easeeocpp.domain.model.AllocationResult
import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargeSettings
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.ControlDecision
import de.puetsch.easeeocpp.domain.model.SurplusInputs
import org.springframework.stereotype.Component
import java.time.Instant

/** Verteilungsmodus, wenn nur ein Charger berechtigt ist; bekommt dann den gesamten Strom. */
const val SINGLE_CHARGER_MODE = "SINGLE_CHARGER"

data class ControlEvaluation(
    val inputs: SurplusInputs,
    val decision: ControlDecision,
)

/**
 * Reine Regel-Logik eines Zyklus nach easee.py: Gesamtstrom bestimmen, auf Mindeststrom anheben,
 * Pausen-Hysterese anwenden und auf die Charger verteilen. Ändert keine Geräte.
 */
@Component
class ControlCycle(
    private val surplusCalculator: SurplusCalculator,
    private val currentAllocator: CurrentAllocator,
    private val eligibilityPolicy: EligibilityPolicy,
    private val phaseVoltageEvaluator: PhaseVoltageEvaluator,
    private val pauseHysteresis: PauseHysteresis,
    private val properties: AppProperties,
) {
    private val priorityChargerSelector = PriorityChargerSelector(properties.control.pauseDelay)
    private val stickyEligibility = StickyEligibility(eligibilityPolicy)
    private val priorityModeTracker = PriorityModeTracker(properties.control)
    private val chargerIds: List<ChargerId> = properties.chargers.map { it.id }

    fun evaluate(
        readings: HaReadings,
        snapshots: List<ChargerSnapshot>,
        now: Instant,
        lastAppliedLimits: Map<ChargerId, Ampere>,
    ): ControlEvaluation {
        val inputs = readings.surplusInputs(evPower = snapshots.sumOf { it.power })
        val demand = demandFor(readings.settings, inputs)
        val activePriority = priorityChargerSelector.select(readings.settings.priorityCharger, now)
        val eligible = eligibleChargers(snapshots, now)
        val mode = priorityModeTracker.update(now, activePriority, eligible, snapshots, lastAppliedLimits)
        val allocation = allocate(demand, activePriority, eligible, mode)
        val decision = ControlDecision(
            decidedAt = now,
            phaseMode = demand.phaseMode,
            allocation = allocation,
            surplus = demand.surplus,
            phaseWithHighestVoltage = phaseWithHighestVoltage(snapshots, activePriority),
            reason = ControlReason.describe(
                demand = demand,
                requestedPriority = readings.settings.priorityCharger,
                activePriority = activePriority,
                allocation = allocation,
                distributionText = ControlReason.distributionText(eligible, mode, properties.control.priorityIdleAfter),
            ),
            allocationMode = allocationModeName(eligible.size, mode),
        )
        return ControlEvaluation(inputs, decision)
    }

    private fun eligibleChargers(snapshots: List<ChargerSnapshot>, now: Instant): Set<ChargerId> =
        snapshots
            .filter { stickyEligibility.isEligible(it, now) }
            .mapTo(mutableSetOf()) { it.chargerId }

    private fun allocationModeName(eligibleCount: Int, mode: AllocationMode): String? = when (eligibleCount) {
        0 -> null
        1 -> SINGLE_CHARGER_MODE
        else -> mode.name
    }

    private fun demandFor(settings: ChargeSettings, inputs: SurplusInputs): ControlDemand =
        if (settings.surplusChargeEnabled) surplusDemand(inputs, settings) else fixedDemand(settings)

    private fun surplusDemand(inputs: SurplusInputs, settings: ChargeSettings): ControlDemand {
        val surplus = surplusCalculator.calculate(inputs)
        val atLeastMinimum = maxOf(surplus.targetCurrent, settings.minimumCurrent)
        val afterHysteresis = pauseHysteresis.filter(atLeastMinimum)
        return ControlDemand(
            totalCurrent = afterHysteresis,
            phaseMode = settings.surplusPhaseMode,
            surplus = surplus,
            minimumRaised = surplus.targetCurrent < settings.minimumCurrent,
        )
    }

    private fun fixedDemand(settings: ChargeSettings): ControlDemand {
        val atLeastMinimum: Ampere = maxOf(settings.fixedCurrent, settings.minimumCurrent)
        return ControlDemand(
            totalCurrent = atLeastMinimum,
            phaseMode = settings.fixedPhaseMode,
            surplus = null,
            minimumRaised = settings.fixedCurrent < settings.minimumCurrent,
        )
    }

    private fun allocate(
        demand: ControlDemand,
        activePriority: ChargerId,
        eligible: Set<ChargerId>,
        mode: AllocationMode,
    ): AllocationResult = currentAllocator.allocate(
        totalCurrent = demand.totalCurrent,
        phaseMode = demand.phaseMode,
        priorityCharger = activePriority,
        chargerIds = chargerIds,
        eligible = eligible,
        sharedMode = mode == AllocationMode.SHARED,
    )

    /** Nur Anzeige: die Phasenwahl wird über OCPP nicht angewendet. */
    private fun phaseWithHighestVoltage(snapshots: List<ChargerSnapshot>, activePriority: ChargerId): Int? =
        snapshots
            .firstOrNull { it.chargerId == activePriority }
            ?.let { phaseVoltageEvaluator.highestVoltagePhase(it) }
}
