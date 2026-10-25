package de.puetsch.easeeocpp.ha

import de.puetsch.easeeocpp.domain.model.EntityId
import de.puetsch.easeeocpp.ports.HaEntityState
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

private const val NO_ID = -1L
private const val TYPE_AUTH_REQUIRED = "auth_required"
private const val TYPE_AUTH = "auth"
private const val TYPE_AUTH_OK = "auth_ok"
private const val TYPE_AUTH_INVALID = "auth_invalid"
private const val TYPE_RESULT = "result"
private const val TYPE_EVENT = "event"
private const val TYPE_GET_STATES = "get_states"
private const val TYPE_SUBSCRIBE_EVENTS = "subscribe_events"
private const val TYPE_CALL_SERVICE = "call_service"
private const val EVENT_STATE_CHANGED = "state_changed"

private const val FIELD_TYPE = "type"
private const val FIELD_ID = "id"
private const val FIELD_SUCCESS = "success"
private const val FIELD_RESULT = "result"
private const val FIELD_ERROR = "error"
private const val FIELD_MESSAGE = "message"
private const val FIELD_EVENT = "event"
private const val FIELD_EVENT_TYPE = "event_type"
private const val FIELD_DATA = "data"
private const val FIELD_ENTITY_ID = "entity_id"
private const val FIELD_NEW_STATE = "new_state"
private const val FIELD_STATE = "state"
private const val FIELD_LAST_REPORTED = "last_reported"
private const val FIELD_LAST_UPDATED = "last_updated"
private const val FIELD_ACCESS_TOKEN = "access_token"
private const val FIELD_DOMAIN = "domain"
private const val FIELD_SERVICE = "service"
private const val FIELD_SERVICE_DATA = "service_data"

internal sealed interface HaInboundMessage {
    data object AuthRequired : HaInboundMessage
    data object AuthOk : HaInboundMessage
    data class AuthInvalid(val reason: String) : HaInboundMessage
    data class Result(
        val id: Long,
        val success: Boolean,
        val payload: JsonNode?,
        val errorMessage: String?,
    ) : HaInboundMessage

    /** newState == null bedeutet: Entity wurde aus HA entfernt. */
    data class StateChanged(val entityId: EntityId, val newState: HaEntityState?) : HaInboundMessage
    data object Unknown : HaInboundMessage
}

internal object HaMessageCodec {
    private val mapper: JsonMapper = JsonMapper.builder().build()

    fun parse(text: String): HaInboundMessage {
        val node = mapper.readTree(text)
        return when (node.textOf(FIELD_TYPE)) {
            TYPE_AUTH_REQUIRED -> HaInboundMessage.AuthRequired
            TYPE_AUTH_OK -> HaInboundMessage.AuthOk
            TYPE_AUTH_INVALID -> HaInboundMessage.AuthInvalid(node.textOf(FIELD_MESSAGE).orEmpty())
            TYPE_RESULT -> parseResult(node)
            TYPE_EVENT -> parseEvent(node)
            else -> HaInboundMessage.Unknown
        }
    }

    fun parseStates(payload: JsonNode?): List<HaEntityState> =
        payload?.takeIf { it.isArray }?.mapNotNull(::parseEntityState).orEmpty()

    fun authMessage(accessToken: String): String =
        write(mapper.createObjectNode().put(FIELD_TYPE, TYPE_AUTH).put(FIELD_ACCESS_TOKEN, accessToken))

    fun getStatesMessage(id: Long): String = write(command(id, TYPE_GET_STATES))

    fun subscribeStateChangedMessage(id: Long): String =
        write(command(id, TYPE_SUBSCRIBE_EVENTS).put(FIELD_EVENT_TYPE, EVENT_STATE_CHANGED))

    fun callServiceMessage(id: Long, domain: String, service: String, data: Map<String, Any?>): String =
        write(
            command(id, TYPE_CALL_SERVICE)
                .put(FIELD_DOMAIN, domain)
                .put(FIELD_SERVICE, service)
                .set(FIELD_SERVICE_DATA, mapper.valueToTree<JsonNode>(data)),
        )

    private fun parseResult(node: JsonNode): HaInboundMessage.Result =
        HaInboundMessage.Result(
            id = node.longOf(FIELD_ID) ?: NO_ID,
            success = node.get(FIELD_SUCCESS)?.asBoolean() == true,
            payload = node.field(FIELD_RESULT),
            errorMessage = node.field(FIELD_ERROR)?.textOf(FIELD_MESSAGE),
        )

    private fun parseEvent(node: JsonNode): HaInboundMessage {
        val event = node.field(FIELD_EVENT) ?: return HaInboundMessage.Unknown
        if (event.textOf(FIELD_EVENT_TYPE) != EVENT_STATE_CHANGED) return HaInboundMessage.Unknown
        val data = event.field(FIELD_DATA) ?: return HaInboundMessage.Unknown
        val entityId = data.textOf(FIELD_ENTITY_ID) ?: return HaInboundMessage.Unknown
        return parseStateChange(entityId, data.field(FIELD_NEW_STATE))
    }

    private fun parseStateChange(entityId: EntityId, newStateNode: JsonNode?): HaInboundMessage {
        if (newStateNode == null) return HaInboundMessage.StateChanged(entityId, null)
        val newState = parseEntityState(newStateNode) ?: return HaInboundMessage.Unknown
        return HaInboundMessage.StateChanged(entityId, newState)
    }

    private fun parseEntityState(node: JsonNode): HaEntityState? {
        val entityId = node.textOf(FIELD_ENTITY_ID) ?: return null
        val state = node.textOf(FIELD_STATE) ?: return null
        val reportedText = node.textOf(FIELD_LAST_REPORTED) ?: node.textOf(FIELD_LAST_UPDATED)
        return HaEntityState(entityId, state, parseTimestamp(reportedText))
    }

    private fun parseTimestamp(text: String?): Instant? {
        if (text == null) return null
        return try {
            OffsetDateTime.parse(text).toInstant()
        } catch (unparseable: DateTimeParseException) {
            null
        }
    }

    private fun command(id: Long, type: String): ObjectNode =
        mapper.createObjectNode().put(FIELD_ID, id).put(FIELD_TYPE, type)

    private fun write(node: JsonNode): String = mapper.writeValueAsString(node)

    private fun JsonNode.field(name: String): JsonNode? = get(name)?.takeUnless { it.isNull }

    private fun JsonNode.textOf(name: String): String? = field(name)?.takeIf { it.isString }?.asString()

    private fun JsonNode.longOf(name: String): Long? = field(name)?.takeIf { it.isIntegralNumber }?.asLong()
}
