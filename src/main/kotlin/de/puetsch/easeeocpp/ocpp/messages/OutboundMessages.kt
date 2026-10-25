package de.puetsch.easeeocpp.ocpp.messages

import com.fasterxml.jackson.annotation.JsonInclude

object OcppConfigurationKeys {
    const val METER_VALUES_SAMPLED_DATA = "MeterValuesSampledData"
    const val METER_VALUE_SAMPLE_INTERVAL = "MeterValueSampleInterval"
}

object OcppRequestedMessages {
    const val STATUS_NOTIFICATION = "StatusNotification"
    const val METER_VALUES = "MeterValues"
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChangeConfigurationRequest(
    val key: String,
    val value: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GetConfigurationRequest(val key: List<String>? = null)

data class GetConfigurationResponse(
    val configurationKey: List<ConfigurationKey> = emptyList(),
    val unknownKey: List<String> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ConfigurationKey(
    val key: String,
    val readonly: Boolean,
    val value: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TriggerMessageRequest(
    val requestedMessage: String,
    val connectorId: Int? = null,
)

data class SetChargingProfileRequest(
    val connectorId: Int,
    val csChargingProfiles: ChargingProfile,
)
