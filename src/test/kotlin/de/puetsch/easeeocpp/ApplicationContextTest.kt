package de.puetsch.easeeocpp

import de.puetsch.easeeocpp.hacharger.HaChargerFleet
import de.puetsch.easeeocpp.ocpp.OcppChargerFleet
import de.puetsch.easeeocpp.ports.ChargerFleet
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertIs

class ApplicationContextTest {
    @Nested
    @SpringBootTest
    inner class StandardSteuertUeberHomeAssistant {
        @Autowired
        private lateinit var fleet: ChargerFleet

        @Test
        fun `Charger werden über Home Assistant gesteuert`() {
            assertIs<HaChargerFleet>(fleet)
        }
    }

    @Nested
    @SpringBootTest(properties = ["easee.ocpp.enabled=true", "easee.ocpp.ssl.enabled=false", "easee.ocpp.port=0"])
    inner class EigenerOcppServerBleibtMoeglich {
        @Autowired
        private lateinit var fleet: ChargerFleet

        @Test
        fun `Charger werden über den eigenen OCPP-Server gesteuert`() {
            assertIs<OcppChargerFleet>(fleet)
        }
    }
}
