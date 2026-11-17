package de.puetsch.easeeocpp.hacharger

import de.puetsch.easeeocpp.domain.model.ChargerStatus
import de.puetsch.easeeocpp.domain.model.EntityId

private const val PHASE_COUNT = 3

/**
 * Entity-IDs, die die Integration „Easee Local OCPP“ für einen Charger anlegt (Gerät „<Name> OCPP“). Die Endungen stammen aus den
 * deutschen Entity-Namen der Integration („OCPP-Leistung“ → `_ocpp_leistung`).
 */
data class HaChargerEntities(
    val connected: EntityId,
    val status: EntityId,
    val power: EntityId,
    val currents: List<EntityId>,
    val voltages: List<EntityId>,
) {
    fun measurementEntities(): List<EntityId> = listOf(power) + currents + voltages

    companion object {
        fun forPrefix(prefix: String): HaChargerEntities = HaChargerEntities(
            connected = "binary_sensor.${prefix}_ocpp_verbunden",
            status = "sensor.${prefix}_ocpp_status",
            power = "sensor.${prefix}_ocpp_leistung",
            currents = (1..PHASE_COUNT).map { phase -> "sensor.${prefix}_ocpp_strom_l$phase" },
            voltages = (1..PHASE_COUNT).map { phase -> "sensor.${prefix}_ocpp_spannung_l$phase" },
        )
    }
}

/** Die Integration meldet den OCPP-Status klein und ohne Trenner („suspendedev“). */
fun chargerStatusOf(haState: String): ChargerStatus? =
    ChargerStatus.entries.firstOrNull { status -> status.name.replace("_", "").equals(haState, ignoreCase = true) }
