package de.puetsch.easeeocpp

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["easee.ocpp.ssl.enabled=false", "easee.ocpp.port=0"])
class ApplicationContextTest {
    @Test
    fun contextLoads() = Unit
}
