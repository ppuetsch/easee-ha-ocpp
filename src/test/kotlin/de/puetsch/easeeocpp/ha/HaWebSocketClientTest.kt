package de.puetsch.easeeocpp.ha

import de.puetsch.easeeocpp.testsupport.FAKE_HA_DEFAULT_TOKEN
import de.puetsch.easeeocpp.testsupport.FakeEntity
import de.puetsch.easeeocpp.testsupport.FakeHomeAssistantServer
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

private const val SHORT_COMMAND_TIMEOUT_MILLIS = 200L
private const val SHORT_PING_INTERVAL_MILLIS = 50L
private const val PINGS_OBSERVED = 5
private const val UNANSWERED_CALLS_UNTIL_DROP = 3
private const val LARGE_STATES_COUNT = 500
private const val STATES_FRAGMENT_BYTES = 64
private val REJECTION_OBSERVATION: Duration = Duration.ofMillis(600)
private val AWAIT_MAX: Duration = Duration.ofSeconds(5)

@Epic("Home Assistant")
@Feature("WebSocket-Client")
class HaWebSocketClientTest {
    private val servers = mutableListOf<FakeHomeAssistantServer>()
    private val clients = mutableListOf<HaWebSocketClient>()

    @AfterEach
    fun tearDown() {
        clients.forEach { client -> client.stop() }
        servers.forEach { server -> server.close() }
    }

    @Test
    @Story("Auth")
    fun `authentifiziert und laedt den Zustand in den Cache`() {
        val server = startServer(initialEntities = listOf(FakeEntity("sensor.pv", "1200")))
        val client = startClient(server)

        awaitCondition { client.isConnected() }

        assertEquals("1200", client.state("sensor.pv")?.state)
        assertEquals(1, server.authSuccessCount.get())
    }

    @Test
    @Story("Auth")
    fun `abgelehnter Token fuehrt nicht zu einer Verbindung und keinem Wiederholversuch`() {
        val server = startServer(acceptedToken = "richtiger-token")
        val client = startClient(server, token = "falscher-token")

        awaitCondition { client.connectionProblem() != null }
        await().during(REJECTION_OBSERVATION).atMost(AWAIT_MAX).until { server.rejectedAuthCount.get() == 1 }

        assertFalse(client.isConnected())
        assertTrue(client.connectionProblem()!!.startsWith("Token abgelehnt"))
    }

    @Test
    @Story("Heartbeat")
    fun `ausbleibende Pongs bauen die halboffene Verbindung ab und verbinden neu`() {
        val server = startServer()
        server.answerPings = false
        startClient(server, pingInterval = Duration.ofMillis(SHORT_PING_INTERVAL_MILLIS))

        awaitCondition { server.authSuccessCount.get() >= 2 }

        assertTrue(server.receivedPingCount.get() >= 2)
    }

    @Test
    @Story("Heartbeat")
    fun `Pongs halten die Verbindung am Leben`() {
        val server = startServer()
        val client = startClient(server, pingInterval = Duration.ofMillis(SHORT_PING_INTERVAL_MILLIS))
        awaitCondition { client.isConnected() }

        awaitCondition { server.receivedPingCount.get() >= PINGS_OBSERVED }

        assertTrue(client.isConnected())
        assertEquals(1, server.authSuccessCount.get())
    }

    @Test
    @Story("call_service")
    fun `drei Service-Aufrufe ohne Antwort in Folge bauen die Verbindung ab`() {
        val server = startServer()
        server.answerServiceCalls = false
        val client = startClient(server, commandTimeout = Duration.ofMillis(SHORT_COMMAND_TIMEOUT_MILLIS))
        awaitCondition { client.isConnected() }

        repeat(UNANSWERED_CALLS_UNTIL_DROP) { client.callService("input_boolean", "turn_on", emptyMap()) }

        awaitCondition { server.authSuccessCount.get() >= 2 }
    }

    @Test
    @Story("get_states")
    fun `grosse fragmentierte get_states-Antwort wird vollstaendig uebernommen`() {
        val entities = (1..LARGE_STATES_COUNT).map { number -> FakeEntity("sensor.test_$number", "wert $number") }
        val server = startServer(initialEntities = entities, statesFragmentBytes = STATES_FRAGMENT_BYTES)
        val client = startClient(server)

        awaitCondition { client.isConnected() }

        assertEquals("wert 1", client.state("sensor.test_1")?.state)
        assertEquals("wert 250", client.state("sensor.test_250")?.state)
        assertEquals("wert $LARGE_STATES_COUNT", client.state("sensor.test_$LARGE_STATES_COUNT")?.state)
    }

    @Test
    @Story("state_changed")
    fun `state_changed aktualisiert bestehende und neue Entities`() {
        val server = startServer(initialEntities = listOf(FakeEntity("sensor.pv", "1200")))
        val client = startClient(server)
        awaitCondition { client.isConnected() }

        server.pushStateChange("sensor.pv", "1500")
        server.pushStateChange("sensor.neu", "7")

        awaitCondition { client.state("sensor.pv")?.state == "1500" }
        awaitCondition { client.state("sensor.neu")?.state == "7" }
    }

    @Test
    @Story("state_changed")
    fun `unavailable ist nicht nutzbar`() {
        val server = startServer(initialEntities = listOf(FakeEntity("sensor.pv", "1200")))
        val client = startClient(server)
        awaitCondition { client.isConnected() }

        server.pushStateChange("sensor.pv", "unavailable")

        awaitCondition { client.state("sensor.pv")?.state == "unavailable" }
        assertFalse(client.state("sensor.pv")!!.isUsable())
    }

    @Test
    @Story("Reconnect")
    fun `verbindet nach Abbruch neu, authentifiziert und abonniert erneut`() {
        val server = startServer(initialEntities = listOf(FakeEntity("sensor.pv", "1200")))
        val client = startClient(server)
        awaitCondition { client.isConnected() }

        server.dropAllConnections()
        awaitCondition { server.authSuccessCount.get() >= 2 && client.isConnected() }
        server.pushStateChange("sensor.pv", "900")

        awaitCondition { client.state("sensor.pv")?.state == "900" }
    }

    @Test
    @Story("call_service")
    fun `call_service mit Erfolg liefert true und kommt beim Server an`() {
        val server = startServer()
        val client = startClient(server)
        awaitCondition { client.isConnected() }

        val delivered = client.callService("input_boolean", "turn_on", mapOf("entity_id" to "input_boolean.x"))

        assertTrue(delivered)
        val call = server.receivedServiceCalls.single()
        assertEquals("input_boolean", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("input_boolean.x", call.data["entity_id"])
    }

    @Test
    @Story("call_service")
    fun `call_service mit Fehlerantwort liefert false`() {
        val server = startServer()
        server.serviceCallSucceeds = false
        val client = startClient(server)
        awaitCondition { client.isConnected() }

        assertFalse(client.callService("input_boolean", "turn_on", mapOf("entity_id" to "input_boolean.x")))
    }

    @Test
    @Story("call_service")
    fun `call_service ohne Antwort liefert nach Timeout false`() {
        val server = startServer()
        server.answerServiceCalls = false
        val client = startClient(server, commandTimeout = Duration.ofMillis(SHORT_COMMAND_TIMEOUT_MILLIS))
        awaitCondition { client.isConnected() }

        assertFalse(client.callService("input_boolean", "turn_on", mapOf("entity_id" to "input_boolean.x")))
    }

    @Test
    @Story("call_service")
    fun `call_service ohne Verbindung liefert false`() {
        val client = HaWebSocketClient(haProperties(port = 1))
        clients.add(client)

        assertFalse(client.callService("input_boolean", "turn_on", emptyMap()))
    }

    private fun startServer(
        acceptedToken: String = FAKE_HA_DEFAULT_TOKEN,
        initialEntities: List<FakeEntity> = emptyList(),
        statesFragmentBytes: Int? = null,
    ): FakeHomeAssistantServer {
        val server = FakeHomeAssistantServer(acceptedToken, initialEntities, statesFragmentBytes).startAndAwait()
        servers.add(server)
        return server
    }

    private fun startClient(
        server: FakeHomeAssistantServer,
        token: String = FAKE_HA_DEFAULT_TOKEN,
        commandTimeout: Duration = Duration.ofSeconds(10),
        pingInterval: Duration = Duration.ofSeconds(20),
    ): HaWebSocketClient {
        val client = HaWebSocketClient(
            haProperties(server.port, token),
            commandTimeout = commandTimeout,
            pingInterval = pingInterval,
        )
        clients.add(client)
        client.start()
        return client
    }
}
