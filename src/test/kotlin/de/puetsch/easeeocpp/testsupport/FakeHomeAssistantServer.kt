package de.puetsch.easeeocpp.testsupport

import org.java_websocket.WebSocket
import org.java_websocket.enums.Opcode
import org.java_websocket.framing.Framedata
import org.java_websocket.framing.FramedataImpl1
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

const val FAKE_HA_DEFAULT_TOKEN = "test-token"
const val FAKE_HA_LAST_REPORTED = "2026-10-25T10:00:00.123456+00:00"

private const val LOOPBACK_HOST = "127.0.0.1"
private const val CLOSE_POLICY_VIOLATION = 1008
private const val CLOSE_ABNORMAL = 1006
private const val SERVER_START_TIMEOUT_SECONDS = 5L
private const val SERVER_STOP_TIMEOUT_MS = 0
private const val HA_VERSION = "2026.8.3"

data class FakeEntity(
    val entityId: String,
    val state: String,
    val lastReported: String = FAKE_HA_LAST_REPORTED,
)

data class FakeServiceCall(
    val id: Long,
    val domain: String,
    val service: String,
    val data: Map<String, Any?>,
)

/**
 * Minimaler Home-Assistant-WebSocket-Server für Tests. Spricht das echte Protokoll über
 * Java-WebSocket, damit Framing, Fragmentierung und Verbindungsabbrüche echt sind.
 */
class FakeHomeAssistantServer(
    private val acceptedToken: String = FAKE_HA_DEFAULT_TOKEN,
    initialEntities: List<FakeEntity> = emptyList(),
    private val statesFragmentBytes: Int? = null,
) : WebSocketServer(InetSocketAddress(LOOPBACK_HOST, 0)), AutoCloseable {
    private val mapper = JsonMapper.builder().build()
    private val entities = ConcurrentHashMap<String, FakeEntity>().apply {
        initialEntities.forEach { entity -> put(entity.entityId, entity) }
    }
    private val subscriptions = ConcurrentHashMap<WebSocket, Long>()
    private val startedSignal = CountDownLatch(1)

    val receivedServiceCalls: MutableList<FakeServiceCall> = CopyOnWriteArrayList()
    val authSuccessCount = AtomicInteger()
    val rejectedAuthCount = AtomicInteger()

    @Volatile
    var serviceCallSucceeds = true

    @Volatile
    var answerServiceCalls = true

    @get:JvmName("listeningPort")
    val port: Int
        get() = getPort()

    fun startAndAwait(): FakeHomeAssistantServer {
        start()
        check(startedSignal.await(SERVER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Fake-HA startet nicht" }
        return this
    }

    fun pushStateChange(entityId: String, state: String) {
        val entity = FakeEntity(entityId, state)
        entities[entityId] = entity
        subscriptions.entries.forEach { subscription ->
            send(subscription.key, eventJson(subscription.value, entity))
        }
    }

    fun dropAllConnections() {
        connections.toList().forEach { connection ->
            connection.closeConnection(CLOSE_ABNORMAL, "dropped by test")
        }
    }

    override fun close() {
        stop(SERVER_STOP_TIMEOUT_MS)
    }

    override fun onStart() {
        startedSignal.countDown()
    }

    override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
        send(connection, mapOf("type" to "auth_required", "ha_version" to HA_VERSION))
    }

    override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) {
        subscriptions.remove(connection)
    }

    override fun onMessage(connection: WebSocket, message: String) {
        val request = mapper.readTree(message)
        when (request.get("type").asString()) {
            "auth" -> handleAuth(connection, request.get("access_token").asString())
            "get_states" -> sendStates(connection, request.get("id").asLong())
            "subscribe_events" -> subscribe(connection, request.get("id").asLong())
            "call_service" -> recordServiceCall(connection, request)
        }
    }

    override fun onError(connection: WebSocket?, exception: Exception?) = Unit

    private fun handleAuth(connection: WebSocket, token: String) {
        if (token == acceptedToken) {
            authSuccessCount.incrementAndGet()
            send(connection, mapOf("type" to "auth_ok", "ha_version" to HA_VERSION))
            return
        }
        rejectedAuthCount.incrementAndGet()
        send(connection, mapOf("type" to "auth_invalid", "message" to "Invalid access token or password"))
        connection.close(CLOSE_POLICY_VIOLATION, "invalid token")
    }

    private fun sendStates(connection: WebSocket, id: Long) {
        val json = mapper.writeValueAsString(resultJson(id, payload = entities.values.map(::stateJson)))
        val fragmentSize = statesFragmentBytes
        if (fragmentSize == null) {
            connection.send(json)
        } else {
            sendFragmented(connection, json, fragmentSize)
        }
    }

    private fun subscribe(connection: WebSocket, id: Long) {
        subscriptions[connection] = id
        send(connection, resultJson(id))
    }

    private fun recordServiceCall(connection: WebSocket, request: JsonNode) {
        val id = request.get("id").asLong()
        receivedServiceCalls.add(
            FakeServiceCall(
                id = id,
                domain = request.get("domain").asString(),
                service = request.get("service").asString(),
                data = parseServiceData(request.get("service_data")),
            ),
        )
        if (answerServiceCalls) {
            send(connection, serviceCallResult(id))
        }
    }

    private fun serviceCallResult(id: Long): Map<String, Any?> =
        if (serviceCallSucceeds) {
            resultJson(id)
        } else {
            resultJson(id, success = false, errorMessage = "service failed on purpose")
        }

    private fun parseServiceData(node: JsonNode?): Map<String, Any?> =
        node?.let { mapper.convertValue(it, SERVICE_DATA_TYPE) }.orEmpty()

    private fun send(connection: WebSocket, message: Map<String, Any?>) {
        connection.send(mapper.writeValueAsString(message))
    }

    private fun sendFragmented(connection: WebSocket, text: String, chunkBytes: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        bytes.indices.step(chunkBytes).forEachIndexed { index, start ->
            val end = minOf(start + chunkBytes, bytes.size)
            val opcode = if (index == 0) Opcode.TEXT else Opcode.CONTINUOUS
            connection.sendFrame(textFragmentFrame(opcode, bytes.copyOfRange(start, end), isLast = end == bytes.size))
        }
    }

    private fun textFragmentFrame(opcode: Opcode, payload: ByteArray, isLast: Boolean): Framedata =
        FramedataImpl1.get(opcode).apply {
            setFin(isLast)
            setPayload(ByteBuffer.wrap(payload))
        }

    private fun resultJson(
        id: Long,
        success: Boolean = true,
        payload: Any? = null,
        errorMessage: String? = null,
    ): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to "result",
        "success" to success,
        "result" to payload,
        "error" to errorMessage?.let { mapOf("code" to "failed", "message" to it) },
    )

    private fun eventJson(subscriptionId: Long, entity: FakeEntity): Map<String, Any?> = mapOf(
        "id" to subscriptionId,
        "type" to "event",
        "event" to mapOf(
            "event_type" to "state_changed",
            "data" to mapOf(
                "entity_id" to entity.entityId,
                "new_state" to stateJson(entity),
                "old_state" to null,
            ),
            "origin" to "LOCAL",
            "time_fired" to entity.lastReported,
        ),
    )

    private fun stateJson(entity: FakeEntity): Map<String, Any?> = mapOf(
        "entity_id" to entity.entityId,
        "state" to entity.state,
        "attributes" to mapOf("friendly_name" to "Küche ${entity.entityId}"),
        "last_changed" to entity.lastReported,
        "last_reported" to entity.lastReported,
        "last_updated" to entity.lastReported,
    )

    private companion object {
        val SERVICE_DATA_TYPE = object : TypeReference<Map<String, Any?>>() {}
    }
}
