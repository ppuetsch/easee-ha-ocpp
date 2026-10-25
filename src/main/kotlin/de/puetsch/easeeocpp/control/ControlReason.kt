package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.domain.model.AllocationResult
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.SurplusResult
import java.time.Duration
import kotlin.math.roundToInt

private const val REASON_SEPARATOR = "; "
private const val SECONDS_PER_MINUTE = 60L

/** Deutscher Begründungstext für die Anzeige in der GUI. */
internal object ControlReason {

    fun describe(
        demand: ControlDemand,
        requestedPriority: ChargerId,
        activePriority: ChargerId,
        allocation: AllocationResult,
        distributionText: String,
    ): String = listOfNotNull(
        modeText(demand.surplus),
        "Gesamtstrom ${demand.totalCurrent} A, ${demand.phaseMode.phaseCount}-phasig",
        minimumText(demand.minimumRaised),
        priorityText(requestedPriority, activePriority),
        distributionText,
        limitsText(allocation),
    ).joinToString(REASON_SEPARATOR)

    fun distributionText(eligible: Set<ChargerId>, mode: AllocationMode, idleAfter: Duration): String = when {
        eligible.isEmpty() -> "kein Charger angesteckt"
        eligible.size == 1 -> "Nur ${eligible.single()} angesteckt – bekommt allen Strom"
        mode == AllocationMode.SHARED ->
            "Prio-Charger zieht seit ${formatDuration(idleAfter)} nichts – beide Charger bekommen Strom"
        else -> "beide angesteckt, Prio-Charger zuerst"
    }

    private fun formatDuration(duration: Duration): String {
        val seconds = duration.seconds
        return if (seconds % SECONDS_PER_MINUTE == 0L) "${seconds / SECONDS_PER_MINUTE} min" else "$seconds s"
    }

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

    private fun limitsText(allocation: AllocationResult): String =
        "Verteilung " + allocation.limits.joinToString(", ") { "${it.chargerId} ${it.current} A" }
}
