package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

private const val OCPP_SESSION_ATTRIBUTE = "ocppSession"

/**
 * Die Konfiguration startet bei jeder neuen Verbindung, nicht erst nach BootNotification: ein Charger, der nach einem
 * Netzwerkabbruch nur wieder verbindet, sendet keine neue BootNotification.
 */
@Component
class OcppWebSocketHandler(
    private val sessions: OcppSessionRegistry,
    private val router: OcppInboundRouter,
    private val bootstrapper: ChargerBootstrapper,
    private val settings: AppProperties,
) : TextWebSocketHandler() {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val timeoutScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    override fun afterConnectionEstablished(webSocket: WebSocketSession) {
        val chargePointId = chargePointIdOf(webSocket)
        val ocppSession = OcppChargerSession(chargePointId, webSocket, timeoutScheduler, settings.ocpp.requestTimeout)
        webSocket.attributes[OCPP_SESSION_ATTRIBUTE] = ocppSession
        sessions.register(ocppSession)?.closeReplaced()
        logger.info("Charger {} verbunden", chargePointId)
        bootstrapper.bootstrapOnceAsync(chargePointId)
    }

    override fun afterConnectionClosed(webSocket: WebSocketSession, status: CloseStatus) {
        val ocppSession = ocppSessionOf(webSocket) ?: return
        if (sessions.unregister(ocppSession)) {
            ocppSession.dispatcher.cancelAll()
            logger.info("Charger {} getrennt: {}", ocppSession.chargePointId, status)
        }
    }

    override fun handleTextMessage(webSocket: WebSocketSession, message: TextMessage) {
        val ocppSession = ocppSessionOf(webSocket) ?: return
        when (val outcome = OcppFrameCodec.parse(message.payload)) {
            is ParseOutcome.Accepted -> dispatchFrame(ocppSession, outcome.frame)
            is ParseOutcome.Rejected -> outcome.errorReply?.let { ocppSession.sendFrame(it) }
        }
    }

    @PreDestroy
    fun shutdown() {
        timeoutScheduler.shutdownNow()
    }

    private fun dispatchFrame(ocppSession: OcppChargerSession, frame: OcppFrame) {
        when (frame) {
            is OcppFrame.Call -> answerCall(ocppSession, frame)
            is OcppFrame.CallResult -> ocppSession.dispatcher.onCallResult(frame.uniqueId, frame.payload)
            is OcppFrame.CallError -> ocppSession.dispatcher.onCallError(
                frame.uniqueId,
                frame.errorCode,
                frame.errorDescription,
            )
        }
    }

    private fun answerCall(ocppSession: OcppChargerSession, call: OcppFrame.Call) {
        val reply = router.route(ocppSession.chargePointId, call)
        ocppSession.sendFrame(reply)
        if (isAcceptedBootNotification(call, reply)) {
            bootstrapper.bootstrapOnceAsync(ocppSession.chargePointId)
        }
    }

    private fun isAcceptedBootNotification(call: OcppFrame.Call, reply: OcppFrame): Boolean =
        call.action == OcppActions.BOOT_NOTIFICATION &&
            reply is OcppFrame.CallResult &&
            OcppResponse.Result(reply.payload).isAccepted()

    private fun chargePointIdOf(webSocket: WebSocketSession): String =
        webSocket.attributes[CHARGE_POINT_ID_ATTRIBUTE] as String

    private fun ocppSessionOf(webSocket: WebSocketSession): OcppChargerSession? =
        webSocket.attributes[OCPP_SESSION_ATTRIBUTE] as? OcppChargerSession
}
