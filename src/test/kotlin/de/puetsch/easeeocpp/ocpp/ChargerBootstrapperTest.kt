package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.ocpp.messages.ChangeConfigurationRequest
import de.puetsch.easeeocpp.ocpp.messages.OcppConfigurationKeys
import de.puetsch.easeeocpp.ocpp.messages.OcppRequestedMessages
import de.puetsch.easeeocpp.ocpp.messages.TriggerMessageRequest
import io.mockk.every
import io.mockk.mockk
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture

private const val CHARGER_ID = "EHDFQLBX"
private const val EXPECTED_SAMPLED_DATA =
    "Power.Active.Import,Current.Import,Voltage,Current.Offered,Energy.Active.Import.Register"
private const val CONFIGURATION_CHANGES_WITH_ACTUATION = 4
private const val CONFIGURATION_CHANGES_IN_SHADOW_MODE = 2
private const val TELEMETRY_TRIGGERS = 2
private const val CONFIGURATION_READS = 1
private const val ALL_INTERVAL_CANDIDATES_REJECTED = 5
private const val CHANGES_WITHOUT_INTERVAL_WITH_ACTUATION = 3
private const val SET_PROFILES_FOR_BASE_AND_LIMIT = 2
private const val SET_PROFILES_FOR_LIMIT_ONLY = 1
private const val BOOTSTRAP_CALLS_WITH_ACTUATION =
    CONFIGURATION_CHANGES_WITH_ACTUATION + CONFIGURATION_READS + TELEMETRY_TRIGGERS
private val AWAIT_TIMEOUT: Duration = Duration.ofSeconds(5)

@Epic("OCPP")
@Feature("ChargerBootstrapper")
class ChargerBootstrapperTest {
    private val registry = OcppSessionRegistry()
    private val clock = MutableTestClock()
    private val session: OcppChargerSession = mockk(relaxed = true)
    private val actions = mutableListOf<String>()
    private val payloads = mutableListOf<Any>()
    private lateinit var profiles: ChargingProfileService
    private lateinit var bootstrapper: ChargerBootstrapper

    @BeforeEach
    fun connectAndAcceptEverything() {
        every { session.chargePointId } returns CHARGER_ID
        registry.register(session)
        acceptEverything(session)
        createBootstrapper(actuationEnabled = true)
    }

    @Test
    @Story("Konfigurationsfolge")
    fun sendsConfigurationThenTelemetryTriggers() {
        bootstrapper.bootstrap(CHARGER_ID)

        assertEquals(
            List(CONFIGURATION_CHANGES_WITH_ACTUATION) { OcppActions.CHANGE_CONFIGURATION } +
                List(CONFIGURATION_READS) { OcppActions.GET_CONFIGURATION } +
                List(TELEMETRY_TRIGGERS) { OcppActions.TRIGGER_MESSAGE },
            actions,
        )
    }

    @Test
    @Story("Konfigurationsfolge")
    fun freeChargingAndSampleSettingsAreSentWithExpectedValues() {
        bootstrapper.bootstrap(CHARGER_ID)

        val changes = configurationChanges().associateBy { it.key }
        assertEquals(EXPECTED_SAMPLED_DATA, changes[OcppConfigurationKeys.METER_VALUES_SAMPLED_DATA]?.value)
        assertEquals("5", changes[OcppConfigurationKeys.METER_VALUE_SAMPLE_INTERVAL]?.value)
        assertEquals("true", changes[OcppConfigurationKeys.FREE_CHARGING]?.value)
        assertEquals("FREECHARGING", changes[OcppConfigurationKeys.FREE_CHARGING_ID_TAG]?.value)
    }

    @Test
    @Story("Schattenmodus")
    fun shadowModeSendsNoFreeChargingChange() {
        createBootstrapper(actuationEnabled = false)

        bootstrapper.bootstrap(CHARGER_ID)

        assertEquals(
            listOf(OcppConfigurationKeys.METER_VALUES_SAMPLED_DATA, OcppConfigurationKeys.METER_VALUE_SAMPLE_INTERVAL),
            configurationChanges().map { it.key },
        )
    }

    @Test
    @Story("Schattenmodus")
    fun shadowModeStillRequestsTelemetry() {
        createBootstrapper(actuationEnabled = false)

        bootstrapper.bootstrap(CHARGER_ID)

        assertEquals(
            List(CONFIGURATION_CHANGES_IN_SHADOW_MODE) { OcppActions.CHANGE_CONFIGURATION } +
                List(CONFIGURATION_READS) { OcppActions.GET_CONFIGURATION } +
                List(TELEMETRY_TRIGGERS) { OcppActions.TRIGGER_MESSAGE },
            actions,
        )
    }

    @Test
    @Story("Telemetrie")
    fun statusNotificationIsTriggeredBeforeMeterValues() {
        bootstrapper.bootstrap(CHARGER_ID)

        val triggers = payloads.filterIsInstance<TriggerMessageRequest>()
        assertEquals(
            listOf(OcppRequestedMessages.STATUS_NOTIFICATION, OcppRequestedMessages.METER_VALUES),
            triggers.map { it.requestedMessage },
        )
        assertEquals(CONNECTOR_ID_VEHICLE, triggers.first().connectorId)
    }

    @Test
    @Story("Fehlerantworten")
    fun rejectedConfigurationDoesNotAbortSequence() {
        every { session.call(capture(actions), capture(payloads)) } answers {
            val action = firstArg<String>()
            val response = if (action == OcppActions.CHANGE_CONFIGURATION) {
                OcppResponse.Error("NotSupported", "nein")
            } else {
                acceptedResponse()
            }
            CompletableFuture.completedFuture(response)
        }

        bootstrapper.bootstrap(CHARGER_ID)

        assertEquals(
            CHANGES_WITHOUT_INTERVAL_WITH_ACTUATION + ALL_INTERVAL_CANDIDATES_REJECTED +
                CONFIGURATION_READS + TELEMETRY_TRIGGERS,
            actions.size,
        )
        assertEquals(OcppActions.TRIGGER_MESSAGE, actions.last())
    }

    @Test
    @Story("Reconnect")
    fun sameSessionResendsOnlyLimitProfile() {
        profiles.applyLimit(CHARGER_ID, 10)
        clearRecordedCalls()

        bootstrapper.bootstrap(CHARGER_ID)

        assertEquals(SET_PROFILES_FOR_LIMIT_ONLY, actions.count { it == OcppActions.SET_CHARGING_PROFILE })
        assertEquals(OcppActions.SET_CHARGING_PROFILE, actions.last())
    }

    @Test
    @Story("Reconnect")
    fun newSessionReceivesBaseAndLimitProfileAfterBootstrap() {
        profiles.applyLimit(CHARGER_ID, 10)
        reconnect()
        clearRecordedCalls()

        bootstrapper.bootstrap(CHARGER_ID)

        assertEquals(SET_PROFILES_FOR_BASE_AND_LIMIT, actions.count { it == OcppActions.SET_CHARGING_PROFILE })
    }

    @Test
    @Story("Einmal pro Sitzung")
    fun bootstrapOnceAsyncRunsOncePerSession() {
        assertTrue(bootstrapper.bootstrapOnceAsync(CHARGER_ID))
        assertFalse(bootstrapper.bootstrapOnceAsync(CHARGER_ID))

        awaitActions(BOOTSTRAP_CALLS_WITH_ACTUATION)
    }

    @Test
    @Story("Einmal pro Sitzung")
    fun reconnectStartsBootstrapAgain() {
        assertTrue(bootstrapper.bootstrapOnceAsync(CHARGER_ID))
        awaitActions(BOOTSTRAP_CALLS_WITH_ACTUATION)
        reconnect()

        assertTrue(bootstrapper.bootstrapOnceAsync(CHARGER_ID))

        awaitActions(2 * BOOTSTRAP_CALLS_WITH_ACTUATION)
    }

    @Test
    @Story("Nicht verbunden")
    fun bootstrapWithoutSessionDoesNothing() {
        registry.unregister(session)

        bootstrapper.bootstrap(CHARGER_ID)

        assertEquals(emptyList<String>(), actions)
    }

    @Test
    @Story("Nicht verbunden")
    fun bootstrapOnceAsyncWithoutSessionReturnsFalse() {
        registry.unregister(session)

        assertFalse(bootstrapper.bootstrapOnceAsync(CHARGER_ID))
    }

    private fun createBootstrapper(actuationEnabled: Boolean) {
        val settings = AppProperties(control = ControlProperties(actuationEnabled = actuationEnabled))
        profiles = ChargingProfileService(registry, settings, clock)
        bootstrapper = ChargerBootstrapper(registry, profiles, settings)
    }

    private fun acceptEverything(target: OcppChargerSession) {
        every { target.call(capture(actions), capture(payloads)) } returns CompletableFuture.completedFuture(acceptedResponse())
    }

    private fun reconnect(): OcppChargerSession {
        val reconnected: OcppChargerSession = mockk(relaxed = true)
        every { reconnected.chargePointId } returns CHARGER_ID
        acceptEverything(reconnected)
        registry.unregister(session)
        registry.register(reconnected)
        return reconnected
    }

    private fun clearRecordedCalls() {
        actions.clear()
        payloads.clear()
    }

    private fun awaitActions(expectedCount: Int) {
        await().atMost(AWAIT_TIMEOUT).until { actions.size == expectedCount }
    }

    private fun configurationChanges(): List<ChangeConfigurationRequest> =
        payloads.filterIsInstance<ChangeConfigurationRequest>()

    private fun acceptedResponse(): OcppResponse =
        OcppResponse.Result(OcppJson.mapper.createObjectNode().put("status", "Accepted"))
}
