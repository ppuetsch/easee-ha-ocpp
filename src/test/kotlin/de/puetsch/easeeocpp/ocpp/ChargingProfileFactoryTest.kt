package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.OcppProperties
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

private const val BASE_PROFILE_GOLDEN = """
{"connectorId":0,"csChargingProfiles":{"chargingProfileId":1,"stackLevel":0,
 "chargingProfilePurpose":"ChargePointMaxProfile","chargingProfileKind":"Absolute",
 "chargingSchedule":{"chargingRateUnit":"A","chargingSchedulePeriod":[{"startPeriod":0,"limit":0.0}]}}}
"""

private const val LIMIT_PROFILE_GOLDEN = """
{"connectorId":0,"csChargingProfiles":{"chargingProfileId":2,"stackLevel":1,
 "chargingProfilePurpose":"ChargePointMaxProfile","chargingProfileKind":"Absolute",
 "chargingSchedule":{"duration":300,"chargingRateUnit":"A","chargingSchedulePeriod":[{"startPeriod":0,"limit":16.0}]}}}
"""

@Epic("OCPP")
@Feature("Ladeprofile")
class ChargingProfileFactoryTest {
    private val factory = ChargingProfileFactory(OcppProperties())

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
    @Story("Regelprofil")
    fun numberPhasesIsAddedOnlyWhenFeatureFlagIsOn() {
        val withFlag = ChargingProfileFactory(OcppProperties(useNumberPhases = true))

        val period = OcppJson.mapper.valueToTree<JsonNode>(withFlag.limitRequest(16))
            .path("csChargingProfiles").path("chargingSchedule").path("chargingSchedulePeriod").path(0)

        assertEquals(3, period.path("numberPhases").asInt())
    }

    private fun golden(json: String) = OcppJson.mapper.readTree(json.trimIndent().replace("\n", ""))
}
