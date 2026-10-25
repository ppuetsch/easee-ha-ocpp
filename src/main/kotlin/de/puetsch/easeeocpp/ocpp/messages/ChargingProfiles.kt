package de.puetsch.easeeocpp.ocpp.messages

import com.fasterxml.jackson.annotation.JsonInclude

object ChargingProfileValues {
    const val PURPOSE_CHARGE_POINT_MAX = "ChargePointMaxProfile"
    const val KIND_ABSOLUTE = "Absolute"
    const val RATE_UNIT_AMPERE = "A"
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChargingProfile(
    val chargingProfileId: Int,
    val stackLevel: Int,
    val chargingProfilePurpose: String,
    val chargingProfileKind: String,
    val chargingSchedule: ChargingSchedule,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChargingSchedule(
    val duration: Int? = null,
    val chargingRateUnit: String,
    val chargingSchedulePeriod: List<ChargingSchedulePeriod>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChargingSchedulePeriod(
    val startPeriod: Int,
    val limit: Double,
    val numberPhases: Int? = null,
)
