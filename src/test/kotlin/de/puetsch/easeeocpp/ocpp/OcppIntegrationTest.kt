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
private const val CONFIGURATION_CHANGES_WITH_ACTUATION = 4
private const val TELEMETRY_TRIGGERS = 2
private const val FIRST_LIMIT_AMPERE = 16
private const val SECOND_LIMIT_AMPERE = 10

@Epic("OCPP")
@Feature("Komponententest über echte WebSocket-Verbindung")
@SpringBootTest(
    classes = [OcppIntegrationTest.OcppTestContext::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "easee.ocpp.basic-auth-password=$PASSWORD",
        "easee.control.actuation-enabled=true",
    ],
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
        awaitCondition { charger.callsFor(OcppActions.CHANGE_CONFIGURATION).size == CONFIGURATION_CHANGES_WITH_ACTUATION }
        val changedKeys = charger.callsFor(OcppActions.CHANGE_CONFIGURATION).map { it.payload.path("key").asText() }
        assertEquals(
            listOf(
                OcppConfigurationKeys.METER_VALUES_SAMPLED_DATA,
                OcppConfigurationKeys.FREE_CHARGING,
                OcppConfigurationKeys.FREE_CHARGING_ID_TAG,
                OcppConfigurationKeys.METER_VALUE_SAMPLE_INTERVAL,
            ),
            changedKeys,
        )
        awaitCondition { charger.callsFor(OcppActions.TRIGGER_MESSAGE).size == TELEMETRY_TRIGGERS }

        charger.call(OcppActions.STATUS_NOTIFICATION, mapOf("connectorId" to 1, "errorCode" to "NoError", "status" to "Charging"))
        charger.call(OcppActions.METER_VALUES, meterValuesPowerKilowatt("3.5"))
        val result = fleet.applyLimit(CHARGER_ID, FIRST_LIMIT_AMPERE)

        assertTrue(result.success, result.message)
        awaitCondition { limitCalls(charger, FIRST_LIMIT_AMPERE).isNotEmpty() }
        val limitProfile = limitCalls(charger, FIRST_LIMIT_AMPERE).first()
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

        assertTrue(fleet.applyLimit(CHARGER_ID, SECOND_LIMIT_AMPERE).success)
        awaitCondition { limitCalls(new, SECOND_LIMIT_AMPERE).isNotEmpty() }
        assertTrue(limitCalls(old, SECOND_LIMIT_AMPERE).isEmpty())
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

    private fun limitCalls(charger: FakeCharger, ampere: Int): List<OcppFrame.Call> =
        charger.callsFor(OcppActions.SET_CHARGING_PROFILE).filter { limitOf(it) == ampere.toDouble() }

    private fun limitOf(call: OcppFrame.Call): Double =
        call.payload.path("csChargingProfiles").path("chargingSchedule").path("chargingSchedulePeriod").path(0)
            .path("limit").asDouble()

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
