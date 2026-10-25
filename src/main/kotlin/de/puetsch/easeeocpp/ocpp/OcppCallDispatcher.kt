package de.puetsch.easeeocpp.ocpp

import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

fun interface OcppFrameSink {
    @Throws(IOException::class)
    fun sendFrame(frame: OcppFrame)
}

/**
 * OCPP-Regel: pro Verbindung höchstens ein offener ausgehender CALL. Weitere Aufrufe warten in einer Queue,
 * bis die Antwort (oder das Timeout) des laufenden CALLs eingetroffen ist.
 */
class OcppCallDispatcher(
    private val sink: OcppFrameSink,
    private val timeoutScheduler: ScheduledExecutorService,
    private val requestTimeout: Duration,
    private val newUniqueId: () -> String = { UUID.randomUUID().toString() },
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val lock = Any()
    private val waitingCalls = ArrayDeque<PendingCall>()
    private var inFlight: PendingCall? = null

    fun submit(action: String, payload: JsonNode): CompletableFuture<OcppResponse> {
        val pendingCall = PendingCall(newUniqueId(), action, payload)
        synchronized(lock) {
            waitingCalls.addLast(pendingCall)
            sendNextWaitingCall()
        }
        return pendingCall.response
    }

    fun onCallResult(uniqueId: String, payload: JsonNode) {
        completeInFlight(uniqueId, OcppResponse.Result(payload))
    }

    fun onCallError(uniqueId: String, errorCode: String, errorDescription: String) {
        completeInFlight(uniqueId, OcppResponse.Error(errorCode, errorDescription))
    }

    fun cancelAll() {
        synchronized(lock) {
            val allPending = listOfNotNull(inFlight) + waitingCalls
            allPending.forEach { cancelTimeout(it) }
            inFlight = null
            waitingCalls.clear()
            allPending.forEach { it.response.complete(OcppResponse.NotConnected) }
        }
    }

    private fun completeInFlight(uniqueId: String, response: OcppResponse) {
        synchronized(lock) {
            val current = inFlight
            if (current == null || current.uniqueId != uniqueId) {
                logger.warn("Antwort auf unbekannte oder nicht offene UniqueId {} verworfen", uniqueId)
                return
            }
            cancelTimeout(current)
            inFlight = null
            sendNextWaitingCall()
            current.response.complete(response)
        }
    }

    private fun sendNextWaitingCall() {
        if (inFlight != null) return
        val next = waitingCalls.removeFirstOrNull() ?: return
        inFlight = next
        next.timeout = timeoutScheduler.schedule(
            Runnable { onTimeout(next.uniqueId) },
            requestTimeout.toMillis(),
            TimeUnit.MILLISECONDS,
        )
        sendOrFail(next)
    }

    private fun sendOrFail(pendingCall: PendingCall) {
        try {
            sink.sendFrame(OcppFrame.Call(pendingCall.uniqueId, pendingCall.action, pendingCall.payload))
        } catch (connectionFailure: IOException) {
            logger.warn("CALL {} konnte nicht gesendet werden", pendingCall.action, connectionFailure)
            completeInFlight(pendingCall.uniqueId, OcppResponse.NotConnected)
        } catch (sessionClosed: IllegalStateException) {
            logger.warn("CALL {} auf geschlossener Verbindung", pendingCall.action)
            completeInFlight(pendingCall.uniqueId, OcppResponse.NotConnected)
        }
    }

    private fun onTimeout(uniqueId: String) {
        logger.warn("Keine Antwort auf CALL mit UniqueId {} innerhalb {}", uniqueId, requestTimeout)
        completeInFlight(uniqueId, OcppResponse.Timeout)
    }

    private fun cancelTimeout(pendingCall: PendingCall) {
        pendingCall.timeout?.cancel(false)
    }

    private class PendingCall(
        val uniqueId: String,
        val action: String,
        val payload: JsonNode,
    ) {
        val response: CompletableFuture<OcppResponse> = CompletableFuture()
        var timeout: ScheduledFuture<*>? = null
    }
}
