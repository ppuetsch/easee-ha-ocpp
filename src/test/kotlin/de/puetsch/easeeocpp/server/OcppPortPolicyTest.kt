package de.puetsch.easeeocpp.server

import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val OCPP_PORT = 8443
private const val GUI_PORT = 8080
private const val OCPP_PATH = "/ocpp"

@Epic("OCPP")
@Feature("TLS")
class OcppPortPolicyTest {
    private val policy = OcppPortPolicy(ocppPort = OCPP_PORT, ocppPath = OCPP_PATH)

    @Test
    @Story("OCPP-Port liefert nur OCPP-Pfade")
    fun ocppPortPermitsOcppPath() {
        assertTrue(policy.permits(OCPP_PORT, "/ocpp/EHDFQLBX"))
    }

    @Test
    @Story("OCPP-Port liefert nur OCPP-Pfade")
    fun ocppPortRejectsActuatorPath() {
        assertFalse(policy.permits(OCPP_PORT, "/actuator/health"))
    }

    @Test
    @Story("OCPP-Port liefert nur OCPP-Pfade")
    fun ocppPortRejectsRootPath() {
        assertFalse(policy.permits(OCPP_PORT, "/"))
    }

    @Test
    @Story("OCPP-Port liefert nur OCPP-Pfade")
    fun ocppPortRejectsPathThatOnlyStartsWithOcppPrefix() {
        assertFalse(policy.permits(OCPP_PORT, "/ocppfoo"))
    }

    @Test
    @Story("GUI-Port liefert keine OCPP-Pfade")
    fun guiPortRejectsOcppPath() {
        assertFalse(policy.permits(GUI_PORT, "/ocpp/EHDFQLBX"))
    }

    @Test
    @Story("GUI-Port liefert keine OCPP-Pfade")
    fun guiPortPermitsActuatorAndGui() {
        assertTrue(policy.permits(GUI_PORT, "/actuator/health"))
        assertTrue(policy.permits(GUI_PORT, "/"))
    }

    @Test
    @Story("Pfad mit Slash am Ende wird gleich behandelt")
    fun trailingSlashInConfiguredPathIsIgnored() {
        val slashPolicy = OcppPortPolicy(ocppPort = OCPP_PORT, ocppPath = "/ocpp/")
        assertTrue(slashPolicy.permits(OCPP_PORT, "/ocpp/EHDFQLBX"))
        assertFalse(slashPolicy.permits(GUI_PORT, "/ocpp/EHDFQLBX"))
    }
}
