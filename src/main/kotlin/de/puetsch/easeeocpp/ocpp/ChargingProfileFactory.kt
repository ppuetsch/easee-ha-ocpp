package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.OcppProperties
import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.ocpp.messages.ChargingProfile
import de.puetsch.easeeocpp.ocpp.messages.ChargingProfileValues
import de.puetsch.easeeocpp.ocpp.messages.ChargingSchedule
import de.puetsch.easeeocpp.ocpp.messages.ChargingSchedulePeriod
import de.puetsch.easeeocpp.ocpp.messages.SetChargingProfileRequest

/** Connector 0 = Ladepunkt-weites Profil; das Basisprofil (Stack 0, 0 A) sichert ab, das Regelprofil (Stack 1) überschreibt es. */
private const val CHARGE_POINT_CONNECTOR_ID = 0
private const val BASE_PROFILE_ID = 1
private const val LIMIT_PROFILE_ID = 2
private const val BASE_STACK_LEVEL = 0
private const val LIMIT_STACK_LEVEL = 1
private const val SCHEDULE_START_PERIOD = 0
private const val BASE_LIMIT_AMPERE = 0.0

/**
 * Der Port kennt keine Phasenwahl, daher ist die Phasenzahl hier fest. Die Wirkung von numberPhases
 * ist nicht belegt und wird nur mit dem Feature-Flag ocpp.use-number-phases am echten Charger getestet.
 */
private const val NUMBER_PHASES_WHEN_ENABLED = 3

class ChargingProfileFactory(private val settings: OcppProperties) {
    fun baseRequest(): SetChargingProfileRequest = request(
        profileId = BASE_PROFILE_ID,
        stackLevel = BASE_STACK_LEVEL,
        schedule = schedule(durationSeconds = null, limit = BASE_LIMIT_AMPERE.toDouble(), numberPhases = null),
    )

    fun limitRequest(current: Ampere): SetChargingProfileRequest = request(
        profileId = LIMIT_PROFILE_ID,
        stackLevel = LIMIT_STACK_LEVEL,
        schedule = schedule(
            durationSeconds = settings.profileDuration.seconds.toInt(),
            limit = current.toDouble(),
            numberPhases = numberPhasesIfEnabled(),
        ),
    )

    private fun request(profileId: Int, stackLevel: Int, schedule: ChargingSchedule): SetChargingProfileRequest =
        SetChargingProfileRequest(
            connectorId = CHARGE_POINT_CONNECTOR_ID,
            csChargingProfiles = ChargingProfile(
                chargingProfileId = profileId,
                stackLevel = stackLevel,
                chargingProfilePurpose = ChargingProfileValues.PURPOSE_CHARGE_POINT_MAX,
                chargingProfileKind = ChargingProfileValues.KIND_ABSOLUTE,
                chargingSchedule = schedule,
            ),
        )

    private fun schedule(durationSeconds: Int?, limit: Double, numberPhases: Int?): ChargingSchedule =
        ChargingSchedule(
            duration = durationSeconds,
            chargingRateUnit = ChargingProfileValues.RATE_UNIT_AMPERE,
            chargingSchedulePeriod = listOf(
                ChargingSchedulePeriod(
                    startPeriod = SCHEDULE_START_PERIOD,
                    limit = limit,
                    numberPhases = numberPhases,
                ),
            ),
        )

    private fun numberPhasesIfEnabled(): Int? =
        if (settings.useNumberPhases) NUMBER_PHASES_WHEN_ENABLED else null
}
