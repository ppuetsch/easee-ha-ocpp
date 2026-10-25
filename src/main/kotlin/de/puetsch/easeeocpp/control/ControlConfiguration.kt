package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.CurrentAllocator
import de.puetsch.easeeocpp.domain.EligibilityPolicy
import de.puetsch.easeeocpp.domain.PauseHysteresis
import de.puetsch.easeeocpp.domain.PhaseVoltageEvaluator
import de.puetsch.easeeocpp.domain.SurplusCalculator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class ControlConfiguration {

    @Bean
    fun surplusCalculator(properties: AppProperties): SurplusCalculator = SurplusCalculator(properties.control)

    @Bean
    fun currentAllocator(properties: AppProperties): CurrentAllocator = CurrentAllocator(properties.control)

    @Bean
    fun eligibilityPolicy(properties: AppProperties): EligibilityPolicy = EligibilityPolicy(properties.control)

    @Bean
    fun phaseVoltageEvaluator(): PhaseVoltageEvaluator = PhaseVoltageEvaluator()

    @Bean
    fun pauseHysteresis(clock: Clock, properties: AppProperties): PauseHysteresis =
        PauseHysteresis(clock, properties.control)
}
