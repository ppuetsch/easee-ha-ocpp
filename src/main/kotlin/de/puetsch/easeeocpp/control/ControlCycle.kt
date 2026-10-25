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
    private val chargerIds: List<ChargerId> = properties.chargers.map { it.id }

    fun evaluate(readings: HaReadings, snapshots: List<ChargerSnapshot>, now: Instant): ControlEvaluation {
        val inputs = readings.surplusInputs(evPower = snapshots.sumOf { it.power })
        val demand = demandFor(readings.settings, inputs)
        val activePriority = priorityChargerSelector.select(readings.settings.priorityCharger, now)
        val allocation = allocate(demand, activePriority, snapshots, now)
        val decision = ControlDecision(
            decidedAt = now,
            phaseMode = demand.phaseMode,
            allocation = allocation,
            surplus = demand.surplus,
            phaseWithHighestVoltage = phaseWithHighestVoltage(snapshots, activePriority),
            reason = ControlReason.describe(demand, readings.settings.priorityCharger, activePriority, allocation),
        )
        return ControlEvaluation(inputs, decision)
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
        snapshots: List<ChargerSnapshot>,
        now: Instant,
    ): AllocationResult {
        val eligibleChargers = snapshots
            .filter { stickyEligibility.isEligible(it, now) }
            .mapTo(mutableSetOf()) { it.chargerId }
        return currentAllocator.allocate(
            totalCurrent = demand.totalCurrent,
            phaseMode = demand.phaseMode,
            priorityCharger = activePriority,
            chargerIds = chargerIds,
            eligible = eligibleChargers,
        )
    }

    /** Nur Anzeige: die Phasenwahl wird über OCPP nicht angewendet. */
    private fun phaseWithHighestVoltage(snapshots: List<ChargerSnapshot>, activePriority: ChargerId): Int? =
        snapshots
            .firstOrNull { it.chargerId == activePriority }
            ?.let { phaseVoltageEvaluator.highestVoltagePhase(it) }
}
