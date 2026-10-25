package de.puetsch.easeeocpp.ocpp

import tools.jackson.databind.JsonNode

const val OCPP_STATUS_ACCEPTED = "Accepted"
const val OCPP_STATUS_INVALID = "Invalid"

sealed interface OcppResponse {
    data class Result(val payload: JsonNode) : OcppResponse

    data class Error(val errorCode: String, val errorDescription: String) : OcppResponse

    data object Timeout : OcppResponse

    data object NotConnected : OcppResponse

    fun isAccepted(): Boolean = statusText() == OCPP_STATUS_ACCEPTED

    fun describe(): String = when (this) {
        is Result -> "status=${statusText() ?: "keiner"}"
        is Error -> "$errorCode: $errorDescription"
        is Timeout -> "keine Antwort innerhalb des Timeouts"
        is NotConnected -> "Charger nicht verbunden"
    }

    private fun statusText(): String? = (this as? Result)?.payload?.path("status")?.takeIf { it.isTextual }?.asText()
}
