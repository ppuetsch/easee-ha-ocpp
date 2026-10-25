package de.puetsch.easeeocpp.domain

import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.model.AllocationResult
import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerLimit
import de.puetsch.easeeocpp.domain.model.PhaseMode

private const val CHARGERS_NEEDING_MINIMUM_FOR_REBALANCE = 2

/**
 * Verteilt den Gesamtstrom auf zwei Charger: der Prio-Charger bekommt bis zu seiner Phasen-Obergrenze,
 * der Rest geht an den anderen. Die Summe bleibt durch die Circuit-Grenze pro Phase begrenzt.
 * Ausgelegt auf genau zwei Charger (der "andere" ist der erste nicht-prioritäre Charger).
 */
class CurrentAllocator(private val properties: ControlProperties) {

    fun allocate(
        totalCurrent: Ampere,
        phaseMode: PhaseMode,
        priorityCharger: ChargerId,
        chargerIds: List<ChargerId>,
        eligible: Set<ChargerId>,
    ): AllocationResult {
        val otherCharger = chargerIds.firstOrNull { it != priorityCharger }
        val split = split(
            total = distributableCurrent(totalCurrent),
            priorityCap = priorityCap(phaseMode),
            priorityEligible = priorityCharger in eligible,
            otherEligible = otherCharger != null && otherCharger in eligible,
        )
        val limits = chargerIds.map { chargerId ->
            ChargerLimit(chargerId, currentFor(chargerId, priorityCharger, otherCharger, split))
        }
        return AllocationResult(limits = limits, totalCurrent = limits.sumOf { it.current })
    }

    private fun distributableCurrent(totalCurrent: Ampere): Ampere =
        totalCurrent.coerceIn(0, properties.circuitMaxCurrentPerPhase)

    private fun priorityCap(phaseMode: PhaseMode): Ampere =
        if (phaseMode == PhaseMode.ONE) properties.priorityCapOnePhase else properties.priorityCapThreePhases

    private fun currentFor(
        chargerId: ChargerId,
        priorityCharger: ChargerId,
        otherCharger: ChargerId?,
        split: CurrentSplit,
    ): Ampere = when (chargerId) {
        priorityCharger -> split.priority
        otherCharger -> split.other
        else -> 0
    }

    private fun split(
        total: Ampere,
        priorityCap: Ampere,
        priorityEligible: Boolean,
        otherEligible: Boolean,
    ): CurrentSplit = when {
        !priorityEligible && !otherEligible -> CurrentSplit(priority = 0, other = 0)
        !priorityEligible -> CurrentSplit(priority = 0, other = otherCharge(total, priorityCap))
        !otherEligible -> CurrentSplit(priority = priorityCharge(total, priorityCap), other = 0)
        else -> splitBetweenBothEligible(total, priorityCap)
    }

    private fun splitBetweenBothEligible(total: Ampere, priorityCap: Ampere): CurrentSplit {
        val priority = priorityCharge(total, priorityCap)
        val rest = total - priority
        if (shouldRebalanceForMinCurrent(total, rest)) {
            return CurrentSplit(priority = total - properties.minChargeCurrent, other = properties.minChargeCurrent)
        }
        return CurrentSplit(priority = priority, other = otherCharge(rest, priorityCap))
    }

    /**
     * Ohne Option bleibt ein Rest unter dem Mindeststrom ungenutzt. Mit Option wird der Prio-Charger gesenkt,
     * damit der andere Charger seinen Mindeststrom bekommt, sofern der Gesamtstrom dafür reicht.
     */
    private fun shouldRebalanceForMinCurrent(total: Ampere, rest: Ampere): Boolean =
        properties.allowRebalanceForMinCurrent &&
            rest < properties.minChargeCurrent &&
            total >= CHARGERS_NEEDING_MINIMUM_FOR_REBALANCE * properties.minChargeCurrent

    private fun priorityCharge(total: Ampere, priorityCap: Ampere): Ampere =
        belowMinimumToZero(minOf(total, priorityCap))

    /** Beide Charger hängen an derselben Phasen-Konfiguration, daher gilt für beide dieselbe Obergrenze. */
    private fun otherCharge(amount: Ampere, priorityCap: Ampere): Ampere =
        belowMinimumToZero(minOf(amount, priorityCap))

    private fun belowMinimumToZero(amount: Ampere): Ampere =
        if (amount < properties.minChargeCurrent) 0 else amount

    private data class CurrentSplit(val priority: Ampere, val other: Ampere)
}
