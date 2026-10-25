package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.OcppProperties
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.Duration

private const val SCHEDULE_START = "2026-10-25T11:59:50Z"

private const val BASE_PROFILE_GOLDEN = """
{"connectorId":0,"csChargingProfiles":{"chargingProfileId":1,"stackLevel":0,
 "chargingProfilePurpose":"ChargePointMaxProfile","chargingProfileKind":"Absolute",
 "chargingSchedule":{"startSchedule":"$SCHEDULE_START","chargingRateUnit":"A",
 "chargingSchedulePeriod":[{"startPeriod":0,"limit":0.0}]}}}
"""

private const val LIMIT_PROFILE_GOLDEN = """
{"connectorId":0,"csChargingProfiles":{"chargingProfileId":2,"stackLevel":1,
 "chargingProfilePurpose":"ChargePointMaxProfile","chargingProfileKind":"Absolute",
 "chargingSchedule":{"duration":300,"startSchedule":"$SCHEDULE_START","chargingRateUnit":"A",
 "chargingSchedulePeriod":[{"startPeriod":0,"limit":16.0}]}}}
"""

@Epic("OCPP")
@Feature("Ladeprofile")
class ChargingProfileFactoryTest {
    private val clock = MutableTestClock()
    private lateinit var factory: ChargingProfileFactory

    @BeforeEach
    fun createFactory() {
        factory = ChargingProfileFactory(OcppProperties(), clock)
    }

    @Test
    @Story("Basisprofil")
    fun baseProfileMatchesGoldenJson() {
        assertEquals(golden(BASE_PROFILE_GOLDEN), OcppJson.mapper.valueToTree(factory.baseRequest()))
    }

    @Test
    @Story("Regelprofil")
    fun limitProfileMatchesGoldenJsonWithoutNumberPhases() {
        assertEquals(golden(LIMIT_PROFILE_GOLDEN), OcppJson.mapper.valueToTree(factory.limitRequest(16)))
    }

    @Test
    @Story("Startzeitpunkt")
    fun absoluteProfilesStartTenSecondsBeforeNowInUtc() {
        val schedule = OcppJson.mapper.valueToTree<JsonNode>(factory.limitRequest(16))
            .path("csChargingProfiles").path("chargingSchedule")

        assertEquals(SCHEDULE_START, schedule.path("startSchedule").asText())
        assertTrue(schedule.path("startSchedule").asText().endsWith("Z"))
    }

    @Test
    @Story("Startzeitpunkt")
    fun startScheduleFollowsInjectedClock() {
        clock.advance(Duration.ofHours(1))

        val schedule = OcppJson.mapper.valueToTree<JsonNode>(factory.baseRequest())
            .path("csChargingProfiles").path("chargingSchedule")

        assertEquals("2026-10-25T12:59:50Z", schedule.path("startSchedule").asText())
    }

    @Test
    @Story("Regelprofil")
    fun numberPhasesIsAddedOnlyWhenFeatureFlagIsOn() {
        val withFlag = ChargingProfileFactory(OcppProperties(useNumberPhases = true), clock)

        val period = OcppJson.mapper.valueToTree<JsonNode>(withFlag.limitRequest(16))
            .path("csChargingProfiles").path("chargingSchedule").path("chargingSchedulePeriod").path(0)

        assertEquals(3, period.path("numberPhases").asInt())
    }

    private fun golden(json: String) = OcppJson.mapper.readTree(json.trimIndent().replace("\n", ""))
}
