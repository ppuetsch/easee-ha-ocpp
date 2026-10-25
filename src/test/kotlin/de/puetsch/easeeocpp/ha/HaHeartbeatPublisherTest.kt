package de.puetsch.easeeocpp.ha

import de.puetsch.easeeocpp.testsupport.FakeHomeAssistantServer
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private val FIXED_NOW: Instant = Instant.parse("2026-10-25T10:00:00Z")
private val FAST_HEARTBEAT_INTERVAL: Duration = Duration.ofMillis(50)
private const val HEARTBEAT_ENTITY = "input_datetime.easee_service_heartbeat"

@Epic("Home Assistant")
@Feature("Heartbeat")
class HaHeartbeatPublisherTest {
    private val clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
    private val servers = mutableListOf<FakeHomeAssistantServer>()
    private val clients = mutableListOf<HaWebSocketClient>()
    private val publishers = mutableListOf<HaHeartbeatPublisher>()

    @AfterEach
    fun tearDown() {
        publishers.forEach { publisher -> publisher.stop() }
        clients.forEach { client -> client.stop() }
        servers.forEach { server -> server.close() }
    }

    @Test
    @Story("Heartbeat-Aufruf")
    fun `publishHeartbeat sendet Epoch-Sekunden an das input_datetime`() {
        val server = startServer()
        val client = startClient(server)
        val publisher = publisherFor(client, server)
        awaitCondition { client.isConnected() }

        assertTrue(publisher.publishHeartbeat())

        val call = server.receivedServiceCalls.single()
        assertEquals("input_datetime", call.domain)
        assertEquals("set_datetime", call.service)
        assertEquals(HEARTBEAT_ENTITY, call.data["entity_id"])
        assertEquals(FIXED_NOW.epochSecond, (call.data["timestamp"] as Number).toLong())
        assertEquals(FIXED_NOW, publisher.lastSuccessfulHeartbeatAt())
    }

    @Test
    @Story("Heartbeat-Aufruf")
    fun `ohne Verbindung gilt der Heartbeat nicht als erfolgreich`() {
        val server = startServer()
        val client = HaWebSocketClient(haProperties(server.port))
        clients.add(client)
        val publisher = publisherFor(client, server)

        assertFalse(publisher.publishHeartbeat())

        assertNull(publisher.lastSuccessfulHeartbeatAt())
    }

    @Test
    @Story("Scheduling")
    fun `start sendet periodisch Heartbeats`() {
        val server = startServer()
        val client = startClient(server)
        awaitCondition { client.isConnected() }
        val publisher = HaHeartbeatPublisher(client, haProperties(server.port, heartbeatInterval = FAST_HEARTBEAT_INTERVAL), clock)
        publishers.add(publisher)

        publisher.start()

        awaitCondition { server.receivedServiceCalls.size >= 2 }
        assertTrue(publisher.isRunning())
    }

    private fun startServer(): FakeHomeAssistantServer {
        val server = FakeHomeAssistantServer().startAndAwait()
        servers.add(server)
        return server
    }

    private fun startClient(server: FakeHomeAssistantServer): HaWebSocketClient {
        val client = HaWebSocketClient(haProperties(server.port))
        clients.add(client)
        client.start()
        return client
    }

    private fun publisherFor(client: HaWebSocketClient, server: FakeHomeAssistantServer): HaHeartbeatPublisher {
        val publisher = HaHeartbeatPublisher(client, haProperties(server.port), clock)
        publishers.add(publisher)
        return publisher
    }
}
