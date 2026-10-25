package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.ocpp.messages.SetChargingProfileRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

private const val CHARGER_ID = "EHDFQLBX"
private const val BASE_STACK_LEVEL = 0
private const val LIMIT_STACK_LEVEL = 1

@Epic("OCPP")
@Feature("ChargingProfileService")
class ChargingProfileServiceTest {
    private val registry = OcppSessionRegistry()
    private val clock = MutableTestClock()
    private val session: OcppChargerSession = mockk(relaxed = true)
    private lateinit var service: ChargingProfileService

    @BeforeEach
    fun createService() {
        every { session.chargePointId } returns CHARGER_ID
        service = ChargingProfileService(registry, AppProperties(), clock)
    }

    @Test
    @Story("Limit setzen")
    fun applyLimitSendsBaseThenLimitProfileAndSucceedsWhenBothAccepted() {
        registry.register(session)
        answerEverythingWith(OcppResponse.Result(acceptedPayload()))

        val result = service.applyLimit(CHARGER_ID, 16)

        assertTrue(result.success)
        assertEquals(16, service.desiredCurrentFor(CHARGER_ID))
        val profiles = capturedProfiles()
        assertEquals(listOf(BASE_STACK_LEVEL, LIMIT_STACK_LEVEL), profiles.map { it.csChargingProfiles.stackLevel })
        assertEquals(16.0, profiles[1].csChargingProfiles.chargingSchedule.chargingSchedulePeriod.single().limit)
    }

    @Test
    @Story("Basisprofil pro Sitzung")
    fun baseProfileIsSentOnlyOncePerSession() {
        registry.register(session)
        answerEverythingWith(OcppResponse.Result(acceptedPayload()))
        service.applyLimit(CHARGER_ID, 16)

        service.applyLimit(CHARGER_ID, 10)

        verify(exactly = 3) { session.call(OcppActions.SET_CHARGING_PROFILE, any()) }
        verify(exactly = 1) { session.call(OcppActions.SET_CHARGING_PROFILE, match<SetChargingProfileRequest> { it.csChargingProfiles.stackLevel == BASE_STACK_LEVEL }) }
    }

    @Test
    @Story("Basisprofil pro Sitzung")
    fun newSessionOfSameChargerReceivesBaseProfileAgain() {
        registry.register(session)
        answerEverythingWith(OcppResponse.Result(acceptedPayload()))
        service.applyLimit(CHARGER_ID, 16)
        val reconnected: OcppChargerSession = mockk(relaxed = true)
        every { reconnected.chargePointId } returns CHARGER_ID
        every { reconnected.call(any(), any()) } returns CompletableFuture.completedFuture(OcppResponse.Result(acceptedPayload()))
        registry.register(reconnected)

        service.applyLimit(CHARGER_ID, 10)

        verify(exactly = 2) { reconnected.call(OcppActions.SET_CHARGING_PROFILE, any()) }
    }

    @Test
    @Story("Basisprofil pro Sitzung")
    fun rejectedBaseProfileIsRetriedOnNextLimit() {
        registry.register(session)
        every { session.call(OcppActions.SET_CHARGING_PROFILE, any()) } returns
            CompletableFuture.completedFuture(OcppResponse.Result(rejectedPayload()))
        service.applyLimit(CHARGER_ID, 16)
        answerEverythingWith(OcppResponse.Result(acceptedPayload()))

        val result = service.applyLimit(CHARGER_ID, 16)

        assertTrue(result.success)
        verify(exactly = 2) { session.call(OcppActions.SET_CHARGING_PROFILE, match<SetChargingProfileRequest> { it.csChargingProfiles.stackLevel == BASE_STACK_LEVEL }) }
    }

    @Test
    @Story("Limit setzen")
    fun rejectedBaseProfileStopsAfterFirstCall() {
        registry.register(session)
        every { session.call(OcppActions.SET_CHARGING_PROFILE, any()) } returns
            CompletableFuture.completedFuture(OcppResponse.Result(rejectedPayload()))

        val result = service.applyLimit(CHARGER_ID, 16)

        assertFalse(result.success)
        verify(exactly = 1) { session.call(OcppActions.SET_CHARGING_PROFILE, any()) }
    }

    @Test
    @Story("Limit setzen")
    fun rejectedLimitProfileFailsTheActuation() {
        registry.register(session)
        every { session.call(OcppActions.SET_CHARGING_PROFILE, match<SetChargingProfileRequest> { it.csChargingProfiles.stackLevel == BASE_STACK_LEVEL }) } returns
            CompletableFuture.completedFuture(OcppResponse.Result(acceptedPayload()))
        every { session.call(OcppActions.SET_CHARGING_PROFILE, match<SetChargingProfileRequest> { it.csChargingProfiles.stackLevel == LIMIT_STACK_LEVEL }) } returns
            CompletableFuture.completedFuture(OcppResponse.Error("NotSupported", "nein"))

        val result = service.applyLimit(CHARGER_ID, 10)

        assertFalse(result.success)
    }

    @Test
    @Story("Nicht verbunden")
    fun unconnectedChargerFailsButRemembersDesiredCurrent() {
        val result = service.applyLimit(CHARGER_ID, 6)

        assertFalse(result.success)
        assertEquals(6, service.desiredCurrentFor(CHARGER_ID))
    }

    @Test
    @Story("Reconnect")
    fun resendWithoutDesiredCurrentDoesNothing() {
        registry.register(session)

        assertNull(service.resendDesiredLimit(CHARGER_ID))
        verify(exactly = 0) { session.call(any(), any()) }
    }

    private fun answerEverythingWith(response: OcppResponse) {
        every { session.call(any(), any()) } returns CompletableFuture.completedFuture(response)
    }

    private fun capturedProfiles(): List<SetChargingProfileRequest> {
        val payloads = mutableListOf<Any>()
        verify(exactly = 2) { session.call(OcppActions.SET_CHARGING_PROFILE, capture(payloads)) }
        return payloads.map { it as SetChargingProfileRequest }
    }

    private fun acceptedPayload() = OcppJson.mapper.createObjectNode().put("status", "Accepted")

    private fun rejectedPayload() = OcppJson.mapper.createObjectNode().put("status", "Rejected")
}
