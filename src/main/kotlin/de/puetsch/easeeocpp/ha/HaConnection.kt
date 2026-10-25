package de.puetsch.easeeocpp.ha

import de.puetsch.easeeocpp.domain.model.EntityId
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

internal interface HaConnectionObserver {
    fun connectionReady(connection: HaConnection)
    fun connectionLost(connection: HaConnection, reason: String)
}

/**
 * Eine WebSocket-Verbindung zu HA: Handshake (auth, get_states, subscribe_events) und Nachrichtenverarbeitung.
 * Der JDK-Listener wird seriell aufgerufen; jede Teilnachricht fordert genau einen Frame nach.
 */
internal class HaConnection(
    private val accessToken: String,
    private val stateCache: HaStateCache,
    private val commands: HaCommandRegistry,
    private val observer: HaConnectionObserver,
) : WebSocket.Listener {
    private val textFragments = StringBuilder()
    private val sendLock = Any()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var ready = false
    private var lossReported = false
    private var statesRequestId = NO_REQUEST
    private var subscribeRequestId = NO_REQUEST

    fun isReady(): Boolean = ready

    fun send(text: String) {
        val socket = webSocket ?: throw IllegalStateException("Keine offene Verbindung zu Home Assistant")
        synchronized(sendLock) {
            socket.sendText(text, true).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    fun close() {
        webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, CLOSE_REASON_SHUTDOWN)
    }

    override fun onOpen(webSocket: WebSocket) {
        this.webSocket = webSocket
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        textFragments.append(data)
        if (last) processCompleteText()
        webSocket.request(1)
        return null
    }

    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
        webSocket.request(1)
        return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        reportLost("Verbindung geschlossen (Status $statusCode, $reason)")
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        reportLost("Verbindungsfehler: ${error.message}")
    }

    private fun processCompleteText() {
        val completeText = textFragments.toString()
        textFragments.setLength(0)
        runCatching { handleText(completeText) }
            .onFailure { failure -> reportLost("Nachricht nicht verarbeitbar: ${failure.message}") }
    }

    private fun handleText(text: String) {
        when (val message = HaMessageCodec.parse(text)) {
            HaInboundMessage.AuthRequired -> send(HaMessageCodec.authMessage(accessToken))
            HaInboundMessage.AuthOk -> requestStates()
            is HaInboundMessage.AuthInvalid -> reportLost("Token abgelehnt: ${message.reason}")
            is HaInboundMessage.Result -> handleResult(message)
            is HaInboundMessage.StateChanged -> applyStateChange(message)
            HaInboundMessage.Unknown -> Unit
        }
    }

    private fun requestStates() {
        statesRequestId = commands.reserveId()
        send(HaMessageCodec.getStatesMessage(statesRequestId))
    }

    private fun handleResult(result: HaInboundMessage.Result) {
        when (result.id) {
            statesRequestId -> handleStatesResult(result)
            subscribeRequestId -> handleSubscribeResult(result)
            else -> commands.completeResult(result.id, result.success)
        }
    }

    private fun handleStatesResult(result: HaInboundMessage.Result) {
        if (!result.success) {
            reportLost("get_states fehlgeschlagen: ${result.errorMessage}")
            return
        }
        stateCache.replaceAll(HaMessageCodec.parseStates(result.payload))
        subscribeRequestId = commands.reserveId()
        send(HaMessageCodec.subscribeStateChangedMessage(subscribeRequestId))
    }

    private fun handleSubscribeResult(result: HaInboundMessage.Result) {
        if (!result.success) {
            reportLost("subscribe_events fehlgeschlagen: ${result.errorMessage}")
            return
        }
        ready = true
        observer.connectionReady(this)
    }

    private fun applyStateChange(change: HaInboundMessage.StateChanged) {
        val newState = change.newState
        if (newState == null) {
            stateCache.remove(change.entityId)
        } else {
            stateCache.put(newState)
        }
    }

    private fun reportLost(reason: String) {
        if (lossReported) return
        lossReported = true
        ready = false
        webSocket?.abort()
        observer.connectionLost(this, reason)
    }

    private companion object {
        val SEND_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val NO_REQUEST = 0L
        const val CLOSE_REASON_SHUTDOWN = "shutdown"
    }
}
