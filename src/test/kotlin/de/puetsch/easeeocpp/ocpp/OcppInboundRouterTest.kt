package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import io.mockk.mockk
import io.mockk.verify
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

private const val CHARGER_ID = "EHDFQLBX"

@Epic("OCPP")
@Feature("Eingehende Nachrichten")
class OcppInboundRouterTest {
    private val tracker: ChargerStateTracker = mockk(relaxed = true)
    private val clock = MutableTestClock()
    private lateinit var router: OcppInboundRouter

    @BeforeEach
    fun createRouter() {
        router = OcppInboundRouter(tracker, clock, AppProperties())
    }

    @Test
    @Story("BootNotification")
    fun bootNotificationIsAcceptedWithUtcTimeAndHeartbeatInterval() {
        val result = routeResult("BootNotification", """{"chargePointVendor":"Easee","chargePointModel":"Charge"}""")

        assertEquals("Accepted", result.path("status").asText())
        assertEquals("2026-10-25T12:00:00Z", result.path("currentTime").asText())
        assertEquals(300, result.path("interval").asInt())
    }

    @Test
    @Story("Heartbeat")
    fun heartbeatAnswersWithCurrentUtcTime() {
        val result = routeResult("Heartbeat", "{}")

        assertEquals("2026-10-25T12:00:00Z", result.path("currentTime").asText())
    }

    @Test
    @Story("StatusNotification")
    fun validStatusIsForwardedToTracker() {
        val result = routeResult(
            "StatusNotification",
            """{"connectorId":1,"errorCode":"NoError","status":"Charging"}""",
        )

        verify { tracker.onStatusChanged(CHARGER_ID, ChargerStatus.CHARGING) }
        assertEquals(0, result.size())
    }

    @Test
    @Story("StatusNotification")
    fun unknownStatusGetsPropertyConstraintViolation() {
        val error = routeError("StatusNotification", """{"connectorId":1,"errorCode":"NoError","status":"Tanzen"}""")

        assertEquals(OcppErrorCode.PROPERTY_CONSTRAINT_VIOLATION.wireValue, error.errorCode)
    }

    @Test
    @Story("MeterValues")
    fun meterValuesAreForwardedToTracker() {
        routeResult(
            "MeterValues",
            """{"connectorId":1,"meterValue":[{"timestamp":"2026-10-25T12:00:00Z",
               "sampledValue":[{"value":"3.5","measurand":"Power.Active.Import","unit":"kW"}]}]}""",
        )

        verify { tracker.onMeterValues(CHARGER_ID, match { it.size == 1 }) }
    }

    @Test
    @Story("Authorize")
    fun authorizeIsAlwaysAccepted() {
        val result = routeResult("Authorize", """{"idTag":"IRGENDEINER"}""")

        assertEquals("Accepted", result.path("idTagInfo").path("status").asText())
    }

    @Test
    @Story("StartTransaction")
    fun startTransactionGetsIncreasingTransactionIds() {
        val payload = """{"connectorId":1,"idTag":"FREECHARGING","meterStart":0,"timestamp":"2026-10-25T12:00:00Z"}"""

        val first = routeResult("StartTransaction", payload)
        val second = routeResult("StartTransaction", payload)

        val seedFromClock = TEST_START_INSTANT.epochSecond.toInt()
        assertEquals(seedFromClock, first.path("transactionId").asInt())
        assertEquals(seedFromClock + 1, second.path("transactionId").asInt())
        assertEquals("Accepted", first.path("idTagInfo").path("status").asText())
    }

    @Test
    @Story("StatusNotification")
    fun connectorZeroStatusDoesNotOverwriteVehicleStatus() {
        val result = routeResult(
            "StatusNotification",
            """{"connectorId":0,"errorCode":"NoError","status":"Faulted"}""",
        )

        verify(exactly = 0) { tracker.onStatusChanged(any(), any()) }
        assertEquals(0, result.size())
    }

    @Test
    @Story("StopTransaction")
    fun stopTransactionIsAccepted() {
        val result = routeResult(
            "StopTransaction",
            """{"meterStop":1200,"timestamp":"2026-10-25T12:00:00Z","transactionId":1}""",
        )

        assertEquals("Accepted", result.path("idTagInfo").path("status").asText())
    }

    @Test
    @Story("Fehlerfälle")
    fun unknownActionGetsNotSupported() {
        val error = routeError("DataTransfer", "{}")

        assertEquals(OcppErrorCode.NOT_SUPPORTED.wireValue, error.errorCode)
    }

    @Test
    @Story("Fehlerfälle")
    fun payloadMissingRequiredFieldGetsFormatViolation() {
        val error = routeError("StatusNotification", """{"connectorId":1}""")

        assertEquals(OcppErrorCode.FORMAT_VIOLATION.wireValue, error.errorCode)
    }

    @Test
    @Story("Fehlerfälle")
    fun callErrorKeepsUniqueIdOfCall() {
        val error = routeError("Authorize", """{"wrong":true}""")

        assertEquals("uid-7", error.uniqueId)
    }

    private fun routeResult(action: String, payloadJson: String): JsonNode {
        val frame = route(action, payloadJson)
        return assertInstanceOf(OcppFrame.CallResult::class.java, frame).payload
    }

    private fun routeError(action: String, payloadJson: String): OcppFrame.CallError =
        assertInstanceOf(OcppFrame.CallError::class.java, route(action, payloadJson))

    private fun route(action: String, payloadJson: String): OcppFrame {
        val payload = OcppJson.mapper.readTree(payloadJson)
        return router.route(CHARGER_ID, OcppFrame.Call("uid-7", action, payload))
    }
}
