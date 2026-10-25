package de.puetsch.easeeocpp.e2e

import de.puetsch.easeeocpp.web.StateFixtures
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

private const val HTTP_OK = 200
private const val EXPECTED_PV_POWER = 4200.0

@Feature("JSON-API")
class GuiApiTest : AbstractGuiTest() {

    private val httpClient = HttpClient.newHttpClient()
    private val jsonMapper = JsonMapper.builder().build()

    @Test
    @Story("/api/state")
    fun `api state liefert den aktuellen Zustand als JSON`() {
        stateStore.state = StateFixtures.fullState(Instant.now())

        val response = httpClient.send(
            HttpRequest.newBuilder(URI.create("${baseUrl()}api/state")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val body = jsonMapper.readTree(response.body())

        assertEquals(HTTP_OK, response.statusCode())
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
        assertTrue(body.get("haConnected").asBoolean())
        assertEquals(EXPECTED_PV_POWER, body.get("inputs").get("pvPower").asDouble())
        assertEquals(3, body.get("history").size())
    }
}
