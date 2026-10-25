package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.ocpp.messages.ChangeConfigurationRequest
import de.puetsch.easeeocpp.ocpp.messages.OcppConfigurationKeys
import io.mockk.every
import io.mockk.mockk
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

private const val CHARGER_ID = "EHDFQLBX"
private const val EXPECTED_SAMPLED_DATA =
    "Power.Active.Import,Current.Import,Voltage,Current.Offered,Energy.Active.Import.Register"

@Epic("OCPP")
@Feature("ChargerBootstrapper")
class ChargerBootstrapperTest {
    private val registry = OcppSessionRegistry()
    private val session: OcppChargerSession = mockk(relaxed = true)
    private val settings = AppProperties()
    private val profiles = ChargingProfileService(registry, settings)
    private val bootstrapper = ChargerBootstrapper(registry, profiles, settings)
    private val actions = mutableListOf<String>()
    private val payloads = mutableListOf<Any>()

    @BeforeEach
    fun connectAndAcceptEverything() {
        every { session.chargePointId } returns CHARGER_ID
        registry.register(session)
        every { session.call(capture(actions), capture(payloads)) } returns
            CompletableFuture.completedFuture(OcppResponse.Result(OcppJson.mapper.createObjectNode().put("status", "Accepted")))
    }

    @Test
    @Story("Konfigurationsfolge")
    fun sendsConfigurationThenMeterValueTrigger() {
        bootstrapper.bootstrap(CHARGER_ID)

        assertEquals(
            listOf(
                OcppActions.CHANGE_CONFIGURATION,
                OcppActions.CHANGE_CONFIGURATION,
                OcppActions.CHANGE_CONFIGURATION,
                OcppActions.CHANGE_CONFIGURATION,
                OcppActions.TRIGGER_MESSAGE,
            ),
            actions,
        )
    }

    @Test
    @Story("Konfigurationsfolge")
    fun freeChargingAndSampleSettingsAreSentWithExpectedValues() {
        bootstrapper.bootstrap(CHARGER_ID)

        val changes = payloads.take(4).map { it as ChangeConfigurationRequest }.associateBy { it.key }
        assertEquals(EXPECTED_SAMPLED_DATA, changes[OcppConfigurationKeys.METER_VALUES_SAMPLED_DATA]?.value)
        assertEquals("5", changes[OcppConfigurationKeys.METER_VALUE_SAMPLE_INTERVAL]?.value)
        assertEquals("true", changes[OcppConfigurationKeys.FREE_CHARGING]?.value)
        assertEquals("FREECHARGING", changes[OcppConfigurationKeys.FREE_CHARGING_ID_TAG]?.value)
    }

    @Test
    @Story("Fehlerantworten")
    fun rejectedConfigurationDoesNotAbortSequence() {
        every { session.call(capture(actions), capture(payloads)) } answers {
            val action = firstArg<String>()
            val response = if (action == OcppActions.CHANGE_CONFIGURATION) {
                OcppResponse.Error("NotSupported", "nein")
            } else {
                OcppResponse.Result(OcppJson.mapper.createObjectNode().put("status", "Accepted"))
            }
            CompletableFuture.completedFuture(response)
        }

        bootstrapper.bootstrap(CHARGER_ID)

        assertEquals(5, actions.size)
        assertEquals(OcppActions.TRIGGER_MESSAGE, actions.last())
    }

    @Test
    @Story("Reconnect")
    fun desiredLimitIsResentAfterBootstrap() {
        profiles.applyLimit(CHARGER_ID, 10)
        actions.clear()
        payloads.clear()

        bootstrapper.bootstrap(CHARGER_ID)

        assertEquals(OcppActions.SET_CHARGING_PROFILE, actions.last())
        assertEquals(2, actions.count { it == OcppActions.SET_CHARGING_PROFILE })
    }

    @Test
    @Story("Nicht verbunden")
    fun bootstrapWithoutSessionDoesNothing() {
        registry.unregister(session)

        bootstrapper.bootstrap(CHARGER_ID)

        assertEquals(emptyList<String>(), actions)
    }
}
