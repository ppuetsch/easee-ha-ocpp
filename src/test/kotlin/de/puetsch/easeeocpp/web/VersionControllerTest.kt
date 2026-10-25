package de.puetsch.easeeocpp.web

import io.mockk.every
import io.mockk.mockk
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Properties

@Epic("GUI")
@Feature("Version")
class VersionControllerTest {

    @Test
    fun `api version liefert den Short-SHA aus den Build-Informationen`() {
        val buildInfo = Properties().apply { setProperty("version", "abc1234") }
        val provider = mockk<ObjectProvider<BuildProperties>> { every { ifAvailable } returns BuildProperties(buildInfo) }

        mockMvc(provider).get("/api/version").andExpect {
            status { isOk() }
            jsonPath("$.version") { value("abc1234") }
        }
    }

    @Test
    fun `ohne Build-Informationen wird unbekannt geliefert`() {
        val provider = mockk<ObjectProvider<BuildProperties>> { every { ifAvailable } returns null }

        mockMvc(provider).get("/api/version").andExpect {
            status { isOk() }
            jsonPath("$.version") { value(UNKNOWN_VERSION) }
        }
    }

    private fun mockMvc(provider: ObjectProvider<BuildProperties>): MockMvc =
        MockMvcBuilders.standaloneSetup(VersionController(VersionProvider(provider))).build()
}
