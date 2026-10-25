package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import de.puetsch.easeeocpp.ocpp.messages.ChargingProfileValues
import de.puetsch.easeeocpp.ocpp.messages.OcppConfigurationKeys
import de.puetsch.easeeocpp.testsupport.FakeCharger
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import java.util.concurrent.TimeUnit

private const val CHARGER_ID = "EHDFQLBX"
private const val OTHER_CHARGER_ID = "EH2NSSVK"
private const val PASSWORD = "geheim"
private const val WAIT_LIMIT_SECONDS = 5L
private const val POLL_INTERVAL_MILLIS = 20L
private const val CLOSED_BY_REPLACEMENT = 1008

@Epic("OCPP")
@Feature("Komponententest über echte WebSocket-Verbindung")
@SpringBootTest(
    classes = [OcppIntegrationTest.OcppTestContext::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easee.ocpp.basic-auth-password=$PASSWORD"],
)
class OcppIntegrationTest {
    @field:LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var fleet: OcppChargerFleet

    @Autowired
    private lateinit var sessions: OcppSessionRegistry

    private val openChargers = mutableListOf<FakeCharger>()

    @AfterEach
    fun closeChargers() {
        openChargers.forEach { it.closeBlocking() }
    }

    @Test
    @Story("Boot, Konfiguration, Limit")
    fun bootConfigureMeterValuesAndApplyLimit() {
        val charger = connectedCharger(CHARGER_ID, PASSWORD)

        assertEquals("Accepted", charger.boot().payloadStatus())
        awaitCondition { charger.callsFor(OcppActions.CHANGE_CONFIGURATION).size == 4 }
        val changedKeys = charger.callsFor(OcppActions.CHANGE_CONFIGURATION).map { it.payload.path("key").asText() }
        assertEquals(
            listOf(
                OcppConfigurationKeys.METER_VALUES_SAMPLED_DATA,
                OcppConfigurationKeys.METER_VALUE_SAMPLE_INTERVAL,
                OcppConfigurationKeys.FREE_CHARGING,
                OcppConfigurationKeys.FREE_CHARGING_ID_TAG,
            ),
            changedKeys,
        )
        awaitCondition { charger.callsFor(OcppActions.TRIGGER_MESSAGE).size == 1 }

        charger.call(OcppActions.STATUS_NOTIFICATION, mapOf("connectorId" to 1, "errorCode" to "NoError", "status" to "Charging"))
        charger.call(OcppActions.METER_VALUES, meterValuesPowerKilowatt("3.5"))
        val result = fleet.applyLimit(CHARGER_ID, 16)

        assertTrue(result.success, result.message)
        awaitCondition { charger.callsFor(OcppActions.SET_CHARGING_PROFILE).size == 2 }
        val limitProfile = charger.callsFor(OcppActions.SET_CHARGING_PROFILE)
            .first { it.payload.path("csChargingProfiles").path("stackLevel").asInt() == 1 }
        assertEquals(
            ChargingProfileValues.PURPOSE_CHARGE_POINT_MAX,
            limitProfile.payload.path("csChargingProfiles").path("chargingProfilePurpose").asText(),
        )
        val snapshot = fleet.snapshots().first { it.chargerId == CHARGER_ID }
        assertTrue(snapshot.connected)
        assertEquals(ChargerStatus.CHARGING, snapshot.status)
        assertEquals(3500.0, snapshot.power)
    }

    @Test
    @Story("Ein Charger, eine Sitzung")
    fun reconnectClosesOldChargerAndNewOneKeepsStateAndReceivesProfiles() {
        val sessionBeforeTest = sessions.find(CHARGER_ID)
        val old = connectedCharger(CHARGER_ID, PASSWORD)
        val oldSession = awaitRegisteredSession(previous = sessionBeforeTest)
        old.call(OcppActions.STATUS_NOTIFICATION, mapOf("connectorId" to 1, "errorCode" to "NoError", "status" to "Charging"))

        val new = connectedCharger(CHARGER_ID, PASSWORD)
        awaitRegisteredSession(previous = oldSession)

        awaitCondition { old.closeCode == CLOSED_BY_REPLACEMENT }
        assertTrue(new.isOpen)
        assertEquals("Accepted", new.boot().payloadStatus())
        val snapshot = fleet.snapshots().first { it.chargerId == CHARGER_ID }
        assertTrue(snapshot.connected, "ersetzte Sitzung darf den Charger nicht als getrennt markieren")
        assertEquals(ChargerStatus.CHARGING, snapshot.status)

        assertTrue(fleet.applyLimit(CHARGER_ID, 10).success)
        awaitCondition { new.callsFor(OcppActions.SET_CHARGING_PROFILE).size == 2 }
        assertTrue(old.callsFor(OcppActions.SET_CHARGING_PROFILE).isEmpty())
    }

    @Test
    @Story("Authentifizierung")
    fun wrongPasswordIsRejected() {
        val charger = FakeCharger(serverUri(CHARGER_ID), CHARGER_ID, "falsch")

        assertFalse(charger.connectAndWait())
    }

    @Test
    @Story("Authentifizierung")
    fun missingAuthorizationIsRejected() {
        val charger = FakeCharger(serverUri(CHARGER_ID), CHARGER_ID, null)

        assertFalse(charger.connectAndWait())
    }

    @Test
    @Story("Authentifizierung")
    fun unknownChargePointIdIsRejected() {
        val charger = FakeCharger(serverUri("UNBEKANNT"), "UNBEKANNT", PASSWORD)

        assertFalse(charger.connectAndWait())
    }

    @Test
    @Story("Unkonfigurierte Charger")
    fun unconfiguredChargerStaysDisconnectedInSnapshots() {
        connectedCharger(CHARGER_ID, PASSWORD)

        val other = fleet.snapshots().first { it.chargerId == OTHER_CHARGER_ID }

        assertFalse(other.connected)
        assertEquals(ChargerStatus.UNAVAILABLE, other.status)
        assertEquals(0.0, other.power)
    }

    private fun connectedCharger(chargePointId: String, password: String): FakeCharger {
        val charger = FakeCharger(serverUri(chargePointId), chargePointId, password)
        assertTrue(charger.connectAndWait(), "Verbindung für $chargePointId muss stehen")
        openChargers.add(charger)
        return charger
    }

    /** Wartet, bis der Server die neue Sitzung registriert hat; sonst wäre die Reihenfolge der Ersetzung nicht festgelegt. */
    private fun awaitRegisteredSession(previous: OcppChargerSession?): OcppChargerSession {
        awaitCondition { sessions.find(CHARGER_ID).let { it != null && it !== previous } }
        return checkNotNull(sessions.find(CHARGER_ID))
    }

    private fun serverUri(chargePointId: String): URI = URI.create("ws://localhost:$port/ocpp/$chargePointId")

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_LIMIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Bedingung nicht innerhalb $WAIT_LIMIT_SECONDS s erfüllt" }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    private fun meterValuesPowerKilowatt(kilowatt: String): Map<String, Any> = mapOf(
        "connectorId" to 1,
        "meterValue" to listOf(
            mapOf(
                "timestamp" to "2026-10-25T12:00:00Z",
                "sampledValue" to listOf(
                    mapOf("value" to kilowatt, "measurand" to "Power.Active.Import", "unit" to "kW"),
                ),
            ),
        ),
    )

    private fun OcppFrame.payloadStatus(): String =
        (this as OcppFrame.CallResult).payload.path("status").asText()

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(AppProperties::class)
    @Import(
        OcppClockConfiguration::class,
        OcppConfiguration::class,
        OcppWebSocketHandler::class,
        OcppHandshakeInterceptor::class,
        OcppSessionRegistry::class,
        OcppInboundRouter::class,
        ChargerStateTracker::class,
        ChargingProfileService::class,
        ChargerBootstrapper::class,
        OcppChargerFleet::class,
    )
    class OcppTestContext
}
