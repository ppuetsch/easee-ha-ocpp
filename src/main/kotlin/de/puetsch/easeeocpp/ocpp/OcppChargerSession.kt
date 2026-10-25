package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.domain.model.ChargerId
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

/** Eine aktive OCPP-Verbindung eines Chargers. Schreibzugriffe werden serialisiert, da WebSocket-Sends nicht nebenläufig erlaubt sind. */
class OcppChargerSession(
    val chargePointId: ChargerId,
    private val webSocket: WebSocketSession,
    timeoutScheduler: ScheduledExecutorService,
    requestTimeout: Duration,
) : OcppFrameSink {
    val dispatcher: OcppCallDispatcher = OcppCallDispatcher(this, timeoutScheduler, requestTimeout)

    @Synchronized
    override fun sendFrame(frame: OcppFrame) {
        webSocket.sendMessage(TextMessage(OcppFrameCodec.serialize(frame)))
    }

    fun call(action: String, payload: Any): CompletableFuture<OcppResponse> =
        dispatcher.submit(action, OcppJson.mapper.valueToTree(payload))

    fun closeReplaced() {
        dispatcher.cancelAll()
        close(CloseStatus.POLICY_VIOLATION.withReason(REPLACED_BY_NEW_CONNECTION))
    }

    fun close(status: CloseStatus) {
        if (webSocket.isOpen) webSocket.close(status)
    }

    private companion object {
        const val REPLACED_BY_NEW_CONNECTION = "replaced by newer connection"
    }
}
