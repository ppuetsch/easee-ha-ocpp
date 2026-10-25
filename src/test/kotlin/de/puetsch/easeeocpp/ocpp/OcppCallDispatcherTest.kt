package de.puetsch.easeeocpp.ocpp

import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private val TIMEOUT: Duration = Duration.ofMillis(150)
private const val WAIT_LIMIT_MILLIS = 2_000L

@Epic("OCPP")
@Feature("CALL-Dispatcher")
class OcppCallDispatcherTest {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val sent = CopyOnWriteArrayList<OcppFrame.Call>()
    private var uniqueIdCounter = 0

    @AfterEach
    fun shutdownScheduler() {
        scheduler.shutdownNow()
    }

    @Test
    @Story("Ein offener CALL")
    fun secondCallIsSentOnlyAfterFirstIsAnswered() {
        val dispatcher = dispatcher(sink = recordingSink())

        val first = dispatcher.submit("Heartbeat", emptyPayload())
        val second = dispatcher.submit("Authorize", emptyPayload())

        assertEquals(1, sent.size)
        assertFalse(second.isDone)

        dispatcher.onCallResult(sent[0].uniqueId, OcppJson.mapper.createObjectNode().put("status", "Accepted"))

        assertInstanceOf(OcppResponse.Result::class.java, first.join())
        assertEquals(2, sent.size)
        assertEquals("Authorize", sent[1].action)
    }

    @Test
    @Story("Ein offener CALL")
    fun callErrorAlsoReleasesTheQueue() {
        val dispatcher = dispatcher(sink = recordingSink())
        val first = dispatcher.submit("Heartbeat", emptyPayload())
        dispatcher.submit("Authorize", emptyPayload())

        dispatcher.onCallError(sent[0].uniqueId, "NotSupported", "nope")

        val error = assertInstanceOf(OcppResponse.Error::class.java, first.join())
        assertEquals("NotSupported", error.errorCode)
        assertEquals(2, sent.size)
    }

    @Test
    @Story("Korrelation")
    fun answerWithUnknownUniqueIdIsIgnored() {
        val dispatcher = dispatcher(sink = recordingSink())
        val pending = dispatcher.submit("Heartbeat", emptyPayload())

        dispatcher.onCallResult("unbekannt", emptyPayload())

        assertFalse(pending.isDone)
        dispatcher.cancelAll()
    }

    @Test
    @Story("Timeout")
    fun unansweredCallTimesOutAndReleasesQueue() {
        val dispatcher = dispatcher(sink = recordingSink())
        val timedOut = dispatcher.submit("Heartbeat", emptyPayload())
        val next = dispatcher.submit("Authorize", emptyPayload())

        assertEquals(OcppResponse.Timeout, timedOut.get(WAIT_LIMIT_MILLIS, TimeUnit.MILLISECONDS))
        assertEquals(2, sent.size, "nach dem Timeout muss der nächste CALL gesendet werden")
        assertFalse(next.isDone)
    }

    @Test
    @Story("Verbindungsfehler")
    fun failedSendCompletesWithNotConnected() {
        val failingSink = OcppFrameSink { throw IOException("Socket zu") }
        val dispatcher = dispatcher(sink = failingSink)

        val response = dispatcher.submit("Heartbeat", emptyPayload())

        assertEquals(OcppResponse.NotConnected, response.join())
    }

    @Test
    @Story("Verbindungsfehler")
    fun cancelAllCompletesInFlightAndQueuedCallsWithNotConnected() {
        val dispatcher = dispatcher(sink = recordingSink())
        val inFlight = dispatcher.submit("Heartbeat", emptyPayload())
        val queued = dispatcher.submit("Authorize", emptyPayload())

        dispatcher.cancelAll()

        assertEquals(OcppResponse.NotConnected, inFlight.join())
        assertEquals(OcppResponse.NotConnected, queued.join())
        assertTrue(sent.size == 1)
    }

    @Test
    @Story("Ein offener CALL")
    fun responseOfTimedOutCallIsNotDeliveredTwice() {
        val dispatcher = dispatcher(sink = recordingSink())
        val timedOut = dispatcher.submit("Heartbeat", emptyPayload())
        timedOut.get(WAIT_LIMIT_MILLIS, TimeUnit.MILLISECONDS)

        dispatcher.onCallResult(sent[0].uniqueId, emptyPayload())

        assertEquals(OcppResponse.Timeout, timedOut.join())
    }

    private fun dispatcher(sink: OcppFrameSink): OcppCallDispatcher =
        OcppCallDispatcher(
            sink = sink,
            timeoutScheduler = scheduler,
            requestTimeout = TIMEOUT,
            newUniqueId = { "id-${++uniqueIdCounter}" },
        )

    private fun recordingSink(): OcppFrameSink = OcppFrameSink { frame ->
        sent.add(frame as OcppFrame.Call)
    }

    private fun emptyPayload() = OcppJson.mapper.createObjectNode()
}
