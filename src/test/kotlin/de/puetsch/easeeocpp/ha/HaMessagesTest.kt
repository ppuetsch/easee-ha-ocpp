package de.puetsch.easeeocpp.ha

import de.puetsch.easeeocpp.ports.HaEntityState
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

@Epic("Home Assistant")
@Feature("Nachrichten-Codec")
class HaMessagesTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    @Story("Auth")
    fun `auth_required, auth_ok und auth_invalid werden erkannt`() {
        assertEquals(
            HaInboundMessage.AuthRequired,
            HaMessageCodec.parse("""{"type":"auth_required","ha_version":"2026.8.3"}"""),
        )
        assertEquals(HaInboundMessage.AuthOk, HaMessageCodec.parse("""{"type":"auth_ok"}"""))
        assertEquals(
            HaInboundMessage.AuthInvalid("bad token"),
            HaMessageCodec.parse("""{"type":"auth_invalid","message":"bad token"}"""),
        )
    }

    @Test
    @Story("result")
    fun `result mit Erfolg und mit Fehler`() {
        val success = HaMessageCodec.parse("""{"id":3,"type":"result","success":true,"result":null}""")
        val failure = HaMessageCodec.parse(
            """{"id":4,"type":"result","success":false,"error":{"code":"not_found","message":"Service not found"}}""",
        )

        assertEquals(HaInboundMessage.Result(3, true, null, null), success)
        assertEquals(HaInboundMessage.Result(4, false, null, "Service not found"), failure)
    }

    @Test
    @Story("state_changed")
    fun `state_changed liefert den neuen Zustand mit Zeitstempel`() {
        val message = HaMessageCodec.parse(
            """{"id":2,"type":"event","event":{"event_type":"state_changed","data":{"entity_id":"sensor.pv",
              "new_state":{"entity_id":"sensor.pv","state":"1500","last_reported":"2026-10-25T10:00:00.123456+00:00"}}}}""",
        )

        val expected = HaInboundMessage.StateChanged(
            "sensor.pv",
            HaEntityState("sensor.pv", "1500", Instant.parse("2026-10-25T10:00:00.123456Z")),
        )
        assertEquals(expected, message)
    }

    @Test
    @Story("state_changed")
    fun `state_changed ohne new_state bedeutet Entfernen`() {
        val message = HaMessageCodec.parse(
            """{"id":2,"type":"event","event":{"event_type":"state_changed","data":{"entity_id":"sensor.alt","new_state":null}}}""",
        )

        assertEquals(HaInboundMessage.StateChanged("sensor.alt", null), message)
    }

    @Test
    @Story("state_changed")
    fun `fremde Event-Typen und unbekannte Nachrichten werden ignoriert`() {
        val otherEvent = HaMessageCodec.parse(
            """{"id":2,"type":"event","event":{"event_type":"call_service","data":{}}}""",
        )
        val unknown = HaMessageCodec.parse("""{"type":"something_new"}""")

        assertEquals(HaInboundMessage.Unknown, otherEvent)
        assertEquals(HaInboundMessage.Unknown, unknown)
    }

    @Test
    @Story("Heartbeat")
    fun `pong wird als Antwort auf den Ping erkannt`() {
        assertEquals(HaInboundMessage.Pong, HaMessageCodec.parse("""{"id":5,"type":"pong"}"""))
    }

    @Test
    @Story("Heartbeat")
    fun `ping wird mit Typ ping und Id gesendet`() {
        val sent = mapper.readTree(HaMessageCodec.pingMessage(7))

        assertEquals("ping", sent.get("type").asString())
        assertEquals(7L, sent.get("id").asLong())
    }

    @Test
    @Story("Zeitstempel")
    fun `unlesbarer Zeitstempel ergibt null statt Fehler`() {
        val states = HaMessageCodec.parseStates(
            mapper.readTree("""[{"entity_id":"sensor.pv","state":"1","last_reported":"kaputt"}]"""),
        )

        assertEquals(listOf(HaEntityState("sensor.pv", "1", null)), states)
    }

    @Test
    @Story("Zeitstempel")
    fun `ohne last_reported wird last_updated verwendet`() {
        val states = HaMessageCodec.parseStates(
            mapper.readTree("""[{"entity_id":"sensor.pv","state":"1","last_updated":"2026-10-25T10:00:00+00:00"}]"""),
        )

        assertEquals(Instant.parse("2026-10-25T10:00:00Z"), states.single().lastReported)
    }

    @Test
    @Story("get_states")
    fun `get_states-Payload wird zu Liste von Zustaenden und ueberspringt defekte Eintraege`() {
        val payload = mapper.readTree(
            """[{"entity_id":"sensor.a","state":"on"},{"state":"ohne id"},{"entity_id":"sensor.b","state":"off"}]""",
        )

        val states = HaMessageCodec.parseStates(payload)

        assertEquals(listOf("sensor.a", "sensor.b"), states.map { it.entityId })
        assertTrue(states.all { it.isUsable() })
        assertTrue(HaMessageCodec.parseStates(null).isEmpty())
    }

    @Test
    @Story("Frames")
    fun `call_service-Frame enthaelt id, Domain, Service und service_data`() {
        val frame = mapper.readTree(
            HaMessageCodec.callServiceMessage(7, "input_datetime", "set_datetime", mapOf("entity_id" to "input_datetime.hb", "timestamp" to 1234L)),
        )

        assertEquals(7L, frame.get("id").asLong())
        assertEquals("call_service", frame.get("type").asString())
        assertEquals("input_datetime", frame.get("domain").asString())
        assertEquals("set_datetime", frame.get("service").asString())
        assertEquals(1234L, frame.get("service_data").get("timestamp").asLong())
    }

    @Test
    @Story("Frames")
    fun `auth-, get_states- und subscribe-Frames haben das HA-Format`() {
        val auth = mapper.readTree(HaMessageCodec.authMessage("geheim"))
        val states = mapper.readTree(HaMessageCodec.getStatesMessage(2))
        val subscribe = mapper.readTree(HaMessageCodec.subscribeStateChangedMessage(3))

        assertEquals("auth", auth.get("type").asString())
        assertEquals("geheim", auth.get("access_token").asString())
        assertEquals("get_states", states.get("type").asString())
        assertEquals(2L, states.get("id").asLong())
        assertEquals("subscribe_events", subscribe.get("type").asString())
        assertEquals("state_changed", subscribe.get("event_type").asString())
        assertFalse(subscribe.has("access_token"))
    }
}
