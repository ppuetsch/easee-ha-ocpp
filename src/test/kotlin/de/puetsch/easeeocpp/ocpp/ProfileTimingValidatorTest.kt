package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.config.OcppProperties
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

@Epic("OCPP")
@Feature("Profil-Zeiten")
class ProfileTimingValidatorTest {

    @Test
    @Story("Defaults")
    fun defaultTimingsAreValid() {
        assertDoesNotThrow { ProfileTimingValidator(AppProperties()).validate() }
    }

    @Test
    @Story("Gültige Kombination")
    fun renewalShorterThanProfileIsValid() {
        assertDoesNotThrow { ProfileTimingValidator(timings(refreshSeconds = 60, durationSeconds = 120)).validate() }
    }

    @Test
    @Story("Ungültige Kombination")
    fun renewalEqualToProfileDurationIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            ProfileTimingValidator(timings(refreshSeconds = 120, durationSeconds = 120)).validate()
        }
    }

    @Test
    @Story("Ungültige Kombination")
    fun renewalLongerThanProfileDurationIsRejectedWithClearMessage() {
        val error = assertThrows(IllegalStateException::class.java) {
            ProfileTimingValidator(timings(refreshSeconds = 180, durationSeconds = 120)).validate()
        }

        assertTrue(error.message.orEmpty().contains("profile-refresh-interval (180 s)"))
        assertTrue(error.message.orEmpty().contains("profile-duration (120 s)"))
    }

    private fun timings(refreshSeconds: Long, durationSeconds: Long): AppProperties = AppProperties(
        control = ControlProperties(profileRefreshInterval = Duration.ofSeconds(refreshSeconds)),
        ocpp = OcppProperties(profileDuration = Duration.ofSeconds(durationSeconds)),
    )
}
