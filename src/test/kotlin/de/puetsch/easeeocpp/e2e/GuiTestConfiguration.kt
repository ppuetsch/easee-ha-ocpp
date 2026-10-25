package de.puetsch.easeeocpp.e2e

import de.puetsch.easeeocpp.control.ControlStateStore
import de.puetsch.easeeocpp.web.StateFixtures
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant

@TestConfiguration(proxyBeanMethods = false)
class GuiTestConfiguration {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    @Primary
    fun fakeControlStateStore(): FakeControlStateStore = FakeControlStateStore(StateFixtures.fullState(Instant.now()))
}
