package de.puetsch.easeeocpp.ocpp.messages

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class BootNotificationRequest(
    val chargePointVendor: String,
    val chargePointModel: String,
    val chargePointSerialNumber: String? = null,
    val firmwareVersion: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class BootNotificationResponse(
    val status: String,
    val currentTime: String,
    val interval: Int,
)

data class HeartbeatResponse(val currentTime: String)

data class StatusNotificationRequest(
    val connectorId: Int,
    val errorCode: String,
    val status: String,
)

data class MeterValuesRequest(
    val connectorId: Int,
    val meterValue: List<MeterValue>,
)

data class MeterValue(
    val timestamp: String,
    val sampledValue: List<SampledValue>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SampledValue(
    val value: String,
    val measurand: String? = null,
    val phase: String? = null,
    val unit: String? = null,
)

data class AuthorizeRequest(val idTag: String)

data class AuthorizeResponse(val idTagInfo: IdTagInfo)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class IdTagInfo(
    val status: String,
    val expiryDate: String? = null,
    val parentIdTag: String? = null,
)

data class StartTransactionRequest(
    val connectorId: Int,
    val idTag: String,
    val meterStart: Int,
    val timestamp: String,
)

data class StartTransactionResponse(
    val transactionId: Int,
    val idTagInfo: IdTagInfo,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class StopTransactionRequest(
    val meterStop: Int,
    val timestamp: String,
    val transactionId: Int,
    val reason: String? = null,
    val idTag: String? = null,
)

data class StopTransactionResponse(val idTagInfo: IdTagInfo)
