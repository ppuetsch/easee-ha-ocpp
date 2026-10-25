package de.puetsch.easeeocpp.testsupport

import de.puetsch.easeeocpp.ocpp.OCPP_SUBPROTOCOL
import de.puetsch.easeeocpp.ocpp.OcppActions
import de.puetsch.easeeocpp.ocpp.OcppFrame
import de.puetsch.easeeocpp.ocpp.OcppFrameCodec
import de.puetsch.easeeocpp.ocpp.OcppJson
import de.puetsch.easeeocpp.ocpp.ParseOutcome
import de.puetsch.easeeocpp.ocpp.callErrorFrame
import de.puetsch.easeeocpp.ocpp.OcppErrorCode
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.protocols.Protocol
import tools.jackson.databind.JsonNode
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private const val REPLY_TIMEOUT_SECONDS = 10L
private const val CONNECT_TIMEOUT_SECONDS = 5L
private const val STATUS_ACCEPTED = "Accepted"
private const val STATUS_REJECTED = "Rejected"
private val ANSWERED_ACTIONS = setOf(
    OcppActions.CHANGE_CONFIGURATION,
    OcppActions.TRIGGER_MESSAGE,
    OcppActions.SET_CHARGING_PROFILE,
)

/**
 * Simulierter Easee-Charger als OCPP-1.6J-Client ohne TLS. Beantwortet ChangeConfiguration, TriggerMessage und
 * SetChargingProfile; [rejectedActions] steuert, welche Aufrufe mit `Rejected` beantwortet werden.
 */
class FakeCharger(
    serverUri: URI,
    private val chargePointId: String,
    password: String?,
) : WebSocketClient(
    serverUri,
    Draft_6455(emptyList(), listOf(Protocol(OCPP_SUBPROTOCOL))),
    basicAuthHeaders(chargePointId, password),
) {
    val receivedCalls: MutableList<OcppFrame.Call> = CopyOnWriteArrayList()
    val rejectedActions: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingReplies = ConcurrentHashMap<String, CompletableFuture<OcppFrame>>()

    fun connectAndWait(): Boolean = connectBlocking(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun callsFor(action: String): List<OcppFrame.Call> = receivedCalls.filter { it.action == action }

    fun boot(): OcppFrame = call(
        OcppActions.BOOT_NOTIFICATION,
        mapOf("chargePointVendor" to "Easee", "chargePointModel" to "Charge"),
    )

    /** Sendet einen CALL des Chargers und wartet auf die Antwort des Servers. */
    fun call(action: String, payload: Any): OcppFrame {
        val uniqueId = UUID.randomUUID().toString()
        val reply = CompletableFuture<OcppFrame>()
        pendingReplies[uniqueId] = reply
        val payloadNode: JsonNode = OcppJson.mapper.valueToTree(payload)
        send(OcppFrameCodec.serialize(OcppFrame.Call(uniqueId, action, payloadNode)))
        return reply.get(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override fun onOpen(handshake: ServerHandshake?) = Unit

    override fun onMessage(message: String?) {
        val text = message ?: return
        when (val outcome = OcppFrameCodec.parse(text)) {
            is ParseOutcome.Accepted -> handleIncoming(outcome.frame)
            is ParseOutcome.Rejected -> Unit
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) = Unit

    override fun onError(ex: Exception?) = Unit

    private fun handleIncoming(frame: OcppFrame) {
        when (frame) {
            is OcppFrame.Call -> answer(frame)
            is OcppFrame.CallResult -> pendingReplies.remove(frame.uniqueId)?.complete(frame)
            is OcppFrame.CallError -> pendingReplies.remove(frame.uniqueId)?.complete(frame)
        }
    }

    private fun answer(call: OcppFrame.Call) {
        receivedCalls.add(call)
        val reply: OcppFrame = if (call.action in ANSWERED_ACTIONS) {
            OcppFrame.CallResult(call.uniqueId, statusPayload(call.action))
        } else {
            callErrorFrame(call.uniqueId, OcppErrorCode.NOT_SUPPORTED, "Action ${call.action} nicht implementiert")
        }
        send(OcppFrameCodec.serialize(reply))
    }

    private fun statusPayload(action: String): JsonNode {
        val status = if (action in rejectedActions) STATUS_REJECTED else STATUS_ACCEPTED
        return OcppJson.mapper.valueToTree(mapOf("status" to status))
    }

    private companion object {
        fun basicAuthHeaders(chargePointId: String, password: String?): Map<String, String> {
            if (password == null) return emptyMap()
            val credentials = Base64.getEncoder().encodeToString("$chargePointId:$password".toByteArray(Charsets.UTF_8))
            return mapOf("Authorization" to "Basic $credentials")
        }
    }
}
