package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.domain.model.ChargeSettings
import de.puetsch.easeeocpp.domain.model.EntityId
import de.puetsch.easeeocpp.domain.model.SurplusInputs
import de.puetsch.easeeocpp.domain.model.Watt

/** Alle Home-Assistant-Eingänge eines Zyklus. Die Ladeleistung der Charger kommt separat aus dem Snapshot. */
data class HaReadings(
    val pvPower: Watt,
    val gridPower: Watt,
    val batteryPower: Watt,
    val securityBuffer: Watt,
    val preferCarOverBattery: Boolean,
    val settings: ChargeSettings,
) {
    fun surplusInputs(evPower: Watt): SurplusInputs = SurplusInputs(
        pvPower = pvPower,
        gridPower = gridPower,
        batteryPower = batteryPower,
        evPower = evPower,
        securityBuffer = securityBuffer,
        preferCarOverBattery = preferCarOverBattery,
        phaseMode = settings.surplusPhaseMode,
    )
}

sealed interface HaReadResult {
    data class Complete(val readings: HaReadings) : HaReadResult

    data class MissingInput(val entityId: EntityId) : HaReadResult
}
