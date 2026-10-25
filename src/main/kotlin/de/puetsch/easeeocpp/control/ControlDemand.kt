package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.PhaseMode
import de.puetsch.easeeocpp.domain.model.SurplusResult

/** Gewünschter Gesamtstrom eines Zyklus vor der Verteilung auf die Charger. */
data class ControlDemand(
    val totalCurrent: Ampere,
    val phaseMode: PhaseMode,
    val surplus: SurplusResult?,
    val minimumRaised: Boolean,
)
