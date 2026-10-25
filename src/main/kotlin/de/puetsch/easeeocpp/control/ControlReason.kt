package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.domain.model.AllocationResult
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.SurplusResult
import kotlin.math.roundToInt

private const val REASON_SEPARATOR = "; "

/** Deutscher Begründungstext für die Anzeige in der GUI. */
internal object ControlReason {

    fun describe(
        demand: ControlDemand,
        requestedPriority: ChargerId,
        activePriority: ChargerId,
        allocation: AllocationResult,
    ): String = listOfNotNull(
        modeText(demand.surplus),
        "Gesamtstrom ${demand.totalCurrent} A, ${demand.phaseMode.phaseCount}-phasig",
        minimumText(demand.minimumRaised),
        priorityText(requestedPriority, activePriority),
        distributionText(allocation),
    ).joinToString(REASON_SEPARATOR)

    private fun modeText(surplus: SurplusResult?): String = when {
        surplus == null -> "Festmodus"
        surplus.enoughPvPower -> "Überschussmodus: Zielleistung ${surplus.targetPower.roundToInt()} W"
        else -> "Überschussmodus: PV unter Mindestschwelle ${surplus.minRequiredPvPower.roundToInt()} W"
    }

    private fun minimumText(minimumRaised: Boolean): String? =
        if (minimumRaised) "auf Mindeststrom angehoben" else null

    private fun priorityText(requestedPriority: ChargerId, activePriority: ChargerId): String =
        if (requestedPriority == activePriority) {
            "Prio-Charger $activePriority"
        } else {
            "Prio-Wechsel zu $requestedPriority noch nicht wirksam, weiter $activePriority"
        }

    private fun distributionText(allocation: AllocationResult): String =
        "Verteilung " + allocation.limits.joinToString(", ") { "${it.chargerId} ${it.current} A" }
}
