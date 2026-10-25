package de.puetsch.easeeocpp.ocpp

import tools.jackson.databind.JsonNode

/** Wird verwendet, wenn eine Nachricht keine lesbare UniqueId hat (OCPP-J hat dafür keine Vorgabe). */
const val UNKNOWN_UNIQUE_ID = "-1"
const val MAX_UNIQUE_ID_LENGTH = 36

sealed interface OcppFrame {
    val uniqueId: String

    data class Call(
        override val uniqueId: String,
        val action: String,
        val payload: JsonNode,
    ) : OcppFrame

    data class CallResult(
        override val uniqueId: String,
        val payload: JsonNode,
    ) : OcppFrame

    data class CallError(
        override val uniqueId: String,
        val errorCode: String,
        val errorDescription: String,
        val errorDetails: JsonNode,
    ) : OcppFrame
}

enum class OcppErrorCode(val wireValue: String) {
    NOT_IMPLEMENTED("NotImplemented"),
    NOT_SUPPORTED("NotSupported"),
    INTERNAL_ERROR("InternalError"),
    PROTOCOL_ERROR("ProtocolError"),
    FORMAT_VIOLATION("FormatViolation"),
    PROPERTY_CONSTRAINT_VIOLATION("PropertyConstraintViolation"),
    GENERIC_ERROR("GenericError"),
}

class OcppProtocolViolationException(
    val errorCode: OcppErrorCode,
    description: String,
) : RuntimeException(description)

fun callErrorFrame(uniqueId: String, errorCode: OcppErrorCode, errorDescription: String): OcppFrame.CallError =
    OcppFrame.CallError(
        uniqueId = uniqueId,
        errorCode = errorCode.wireValue,
        errorDescription = errorDescription,
        errorDetails = OcppJson.mapper.createObjectNode(),
    )
