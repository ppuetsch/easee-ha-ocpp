package de.puetsch.easeeocpp.ocpp

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode

sealed interface ParseOutcome {
    data class Accepted(val frame: OcppFrame) : ParseOutcome

    /** [errorReply] ist nur gesetzt, wenn die Nachricht ein CALL mit lesbarer UniqueId war; auf Antworten wird nie geantwortet. */
    data class Rejected(val errorReply: OcppFrame.CallError?, val reason: String) : ParseOutcome
}

object OcppFrameCodec {
    private const val MESSAGE_TYPE_CALL = 2
    private const val MESSAGE_TYPE_CALL_RESULT = 3
    private const val MESSAGE_TYPE_CALL_ERROR = 4
    private const val CALL_LENGTH = 4
    private const val CALL_RESULT_LENGTH = 3
    private const val CALL_ERROR_LENGTH = 5
    private const val MIN_FRAME_LENGTH = 3

    fun parse(text: String): ParseOutcome {
        val root = readTreeOrNull(text) ?: return rejectedWithoutReply("kein gültiges JSON")
        if (!root.isArray || root.size() < MIN_FRAME_LENGTH) {
            return rejectedWithoutReply("Frame ist kein Array mit mindestens $MIN_FRAME_LENGTH Elementen")
        }
        val messageTypeNode = root.path(0)
        if (!messageTypeNode.isIntegralNumber) {
            return rejectedWithoutReply("MessageTypeId fehlt oder ist keine Zahl")
        }
        return when (messageTypeNode.intValue()) {
            MESSAGE_TYPE_CALL -> parseCall(root)
            MESSAGE_TYPE_CALL_RESULT -> parseCallResult(root)
            MESSAGE_TYPE_CALL_ERROR -> parseCallError(root)
            else -> rejectedWithoutReply("unbekannte MessageTypeId ${messageTypeNode.intValue()}")
        }
    }

    fun serialize(frame: OcppFrame): String = OcppJson.mapper.writeValueAsString(toWireArray(frame))

    private fun readTreeOrNull(text: String): JsonNode? =
        try {
            OcppJson.mapper.readTree(text)
        } catch (malformed: JacksonException) {
            null
        }

    private fun parseCall(root: JsonNode): ParseOutcome {
        val uniqueId = uniqueIdOrNull(root) ?: return rejectedWithoutReply("UniqueId fehlt oder ist ungültig")
        if (root.size() != CALL_LENGTH) {
            return rejectedWithReply(uniqueId, "CALL muss $CALL_LENGTH Elemente haben")
        }
        val actionNode = root.path(2)
        if (!actionNode.isTextual || actionNode.asText().isBlank()) {
            return rejectedWithReply(uniqueId, "Action fehlt oder ist leer")
        }
        val payload = root.path(3)
        if (!payload.isObject) {
            return rejectedWithReply(uniqueId, "Payload ist kein JSON-Objekt")
        }
        return ParseOutcome.Accepted(OcppFrame.Call(uniqueId, actionNode.asText(), payload))
    }

    private fun parseCallResult(root: JsonNode): ParseOutcome {
        val uniqueId = uniqueIdOrNull(root) ?: return rejectedWithoutReply("UniqueId fehlt oder ist ungültig")
        val payload = root.path(2)
        if (root.size() != CALL_RESULT_LENGTH || !payload.isObject) {
            return rejectedWithoutReply("CALLRESULT ist ungültig")
        }
        return ParseOutcome.Accepted(OcppFrame.CallResult(uniqueId, payload))
    }

    private fun parseCallError(root: JsonNode): ParseOutcome {
        val uniqueId = uniqueIdOrNull(root) ?: return rejectedWithoutReply("UniqueId fehlt oder ist ungültig")
        val errorCode = root.path(2)
        val errorDescription = root.path(3)
        val errorDetails = root.path(4)
        val isWellFormed = root.size() == CALL_ERROR_LENGTH &&
            errorCode.isTextual &&
            errorDescription.isTextual &&
            errorDetails.isObject
        if (!isWellFormed) {
            return rejectedWithoutReply("CALLERROR ist ungültig")
        }
        return ParseOutcome.Accepted(
            OcppFrame.CallError(uniqueId, errorCode.asText(), errorDescription.asText(), errorDetails),
        )
    }

    private fun uniqueIdOrNull(root: JsonNode): String? {
        val node = root.path(1)
        val isUsable = node.isTextual && node.asText().isNotEmpty() && node.asText().length <= MAX_UNIQUE_ID_LENGTH
        return if (isUsable) node.asText() else null
    }

    private fun rejectedWithoutReply(reason: String): ParseOutcome.Rejected =
        ParseOutcome.Rejected(errorReply = null, reason = reason)

    private fun rejectedWithReply(uniqueId: String, reason: String): ParseOutcome.Rejected =
        ParseOutcome.Rejected(
            errorReply = callErrorFrame(uniqueId, OcppErrorCode.FORMAT_VIOLATION, reason),
            reason = reason,
        )

    private fun toWireArray(frame: OcppFrame): JsonNode {
        val array = OcppJson.mapper.createArrayNode()
        when (frame) {
            is OcppFrame.Call -> array.add(MESSAGE_TYPE_CALL).add(frame.uniqueId).add(frame.action).add(frame.payload)
            is OcppFrame.CallResult -> array.add(MESSAGE_TYPE_CALL_RESULT).add(frame.uniqueId).add(frame.payload)
            is OcppFrame.CallError -> array.add(MESSAGE_TYPE_CALL_ERROR).add(frame.uniqueId)
                .add(frame.errorCode).add(frame.errorDescription).add(frame.errorDetails)
        }
        return array
    }
}
