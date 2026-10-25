package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargeSettings
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.EntityId
import de.puetsch.easeeocpp.domain.model.PhaseMode
import de.puetsch.easeeocpp.domain.model.Watt
import de.puetsch.easeeocpp.ports.HomeAssistantStates
import org.springframework.stereotype.Component

private const val HA_ON = "on"

/**
 * Liest die Regel-Eingänge aus dem HA-Zustandscache. Fehlt ein Eingang oder ist er `unavailable`/`unknown`,
 * wird der Zyklus übersprungen statt mit einem Ersatzwert zu regeln. Ein Alter-Check entfällt bewusst:
 * unveränderte HA-Werte erzeugen kein Event, sind also nicht veraltet.
 */
@Component
class HaSettingsReader(
    private val states: HomeAssistantStates,
    private val properties: AppProperties,
) {
    fun read(): HaReadResult {
        val missingEntity = requiredEntityIds().firstOrNull { !isUsable(it) }
        return if (missingEntity != null) {
            HaReadResult.MissingInput(missingEntity)
        } else {
            HaReadResult.Complete(readAll())
        }
    }

    private fun requiredEntityIds(): List<EntityId> {
        val entities = properties.ha.entities
        return listOf(
            entities.pvPower,
            entities.gridPower,
            entities.batteryPower,
            entities.securityBuffer,
            entities.preferCarOverBattery,
            entities.pvSurplusCharge,
            entities.targetPhases,
            entities.targetCurrent,
            entities.minimumEvCurrent,
            entities.priorityCharger,
        )
    }

    private fun isUsable(entityId: EntityId): Boolean = states.state(entityId)?.isUsable() == true

    private fun readAll(): HaReadings {
        val entities = properties.ha.entities
        return HaReadings(
            pvPower = wattOf(entities.pvPower),
            gridPower = wattOf(entities.gridPower),
            batteryPower = wattOf(entities.batteryPower),
            securityBuffer = wattOf(entities.securityBuffer),
            preferCarOverBattery = booleanOf(entities.preferCarOverBattery),
            settings = readChargeSettings(),
        )
    }

    private fun readChargeSettings(): ChargeSettings {
        val entities = properties.ha.entities
        val phaseMode = phaseModeOf(entities.targetPhases)
        return ChargeSettings(
            surplusChargeEnabled = booleanOf(entities.pvSurplusCharge),
            surplusPhaseMode = phaseMode,
            fixedPhaseMode = phaseMode,
            fixedCurrent = ampereOf(entities.targetCurrent),
            minimumCurrent = ampereOf(entities.minimumEvCurrent),
            priorityCharger = priorityChargerOf(entities.priorityCharger),
        )
    }

    private fun stateOf(entityId: EntityId): String =
        states.state(entityId)?.state ?: error("Eingang fehlt: $entityId")

    private fun wattOf(entityId: EntityId): Watt = stateOf(entityId).toDouble()

    /** Wie Python `int(float(...))` im Altskript: Nachkommastellen werden abgeschnitten. */
    private fun ampereOf(entityId: EntityId): Ampere = stateOf(entityId).toDouble().toInt()

    private fun booleanOf(entityId: EntityId): Boolean = stateOf(entityId) == HA_ON

    /** HA liefert Zahlen als Text, z. B. "3" oder "3.0". Phasenwerte außer 1 und 3 werden abgelehnt. */
    private fun phaseModeOf(entityId: EntityId): PhaseMode =
        PhaseMode.fromPhaseCount(stateOf(entityId).toDouble().toInt())

    private fun priorityChargerOf(entityId: EntityId): ChargerId {
        val chargerName = stateOf(entityId)
        return properties.chargers.firstOrNull { it.name == chargerName }?.id
            ?: throw IllegalArgumentException("Unbekannter Prio-Charger: $chargerName")
    }
}
