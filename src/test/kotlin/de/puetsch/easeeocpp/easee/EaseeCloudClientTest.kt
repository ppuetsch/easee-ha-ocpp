package de.puetsch.easeeocpp.easee

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.config.EaseeCloudProperties
import de.puetsch.easeeocpp.domain.model.PhaseMode
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CHARGER_ID = "EHDFQLBX"
private const val USERNAME = "installer@example.com"
private const val PASSWORD = "geheim-passwort-42"
private const val BLANK = ""
private const val FIRST_TOKEN = "Bearer token-1"
private const val SECOND_TOKEN = "Bearer token-2"
private const val SETTINGS_PATH = "/api/chargers/EHDFQLBX/settings"
private const val LOGIN_PATH = "/api/accounts/login"
private const val TOKEN_LIFETIME_SECONDS = 3600L
private const val TOKEN_REFRESH_MARGIN_SECONDS = 60L
private const val START_INSTANT = "2026-10-25T10:00:00Z"
private const val MSG_CREDENTIALS_MISSING = "Easee-Cloud-Zugangsdaten fehlen"
private const val MSG_LOGIN_INVALID = "Easee-Cloud-Login: ungültige Antwort"

@Epic("Easee Cloud")
@Feature("Phasenmodus")
class EaseeCloudClientTest {
    private lateinit var fake: FakeEaseeCloud
    private lateinit var clock: MutableClock

    @BeforeEach
    fun setUp() {
        fake = FakeEaseeCloud()
        clock = MutableClock(Instant.parse(START_INSTANT))
    }

    @AfterEach
    fun tearDown() {
        fake.close()
    }

    @Test
    @Story("Login und Settings")
    fun `login and settings call succeed with bearer token`() {
        val result = clientWith().setPhaseMode(CHARGER_ID, PhaseMode.THREE)

        assertTrue(result.success)
        assertEquals(1, fake.loginRequests.size)
        val settings = fake.settingsRequests.single()
        assertEquals("POST", settings.method)
        assertEquals(SETTINGS_PATH, settings.path)
        assertEquals(FIRST_TOKEN, settings.authorization)
    }

    @Test
    @Story("Request-Bodys")
    fun `login request carries credentials as json with json headers`() {
        clientWith().setPhaseMode(CHARGER_ID, PhaseMode.THREE)

        val login = fake.loginRequests.single()
        assertEquals("POST", login.method)
        assertEquals(LOGIN_PATH, login.path)
        assertEquals("""{"userName":"$USERNAME","password":"$PASSWORD"}""", login.body)
        assertEquals("application/json", login.accept)
        assertEquals("application/json", login.contentType)
        assertEquals(null, login.authorization)
    }

    @Test
    @Story("Request-Bodys")
    fun `settings request carries phase mode one`() {
        clientWith().setPhaseMode(CHARGER_ID, PhaseMode.ONE)

        val settings = fake.settingsRequests.single()
        assertEquals("""{"phaseMode":1}""", settings.body)
        assertEquals("application/json", settings.contentType)
        assertEquals("application/json", settings.accept)
    }

    @Test
    @Story("Token-Wiederverwendung")
    fun `second call reuses token without second login`() {
        val client = clientWith()

        client.setPhaseMode(CHARGER_ID, PhaseMode.THREE)
        val second = client.setPhaseMode(CHARGER_ID, PhaseMode.ONE)

        assertTrue(second.success)
        assertEquals(1, fake.loginRequests.size)
        assertEquals(2, fake.settingsRequests.size)
        assertTrue(fake.settingsRequests.all { it.authorization == FIRST_TOKEN })
    }

    @Test
    @Story("Token-Ablauf")
    fun `token is renewed exactly when the refresh margin is reached`() {
        val client = clientWith()
        client.setPhaseMode(CHARGER_ID, PhaseMode.THREE)
        val timeUntilRenewal = TOKEN_LIFETIME_SECONDS - TOKEN_REFRESH_MARGIN_SECONDS

        clock.advance(Duration.ofSeconds(timeUntilRenewal - 1))
        client.setPhaseMode(CHARGER_ID, PhaseMode.THREE)
        assertEquals(1, fake.loginRequests.size)

        clock.advance(Duration.ofSeconds(1))
        val afterRenewal = client.setPhaseMode(CHARGER_ID, PhaseMode.THREE)

        assertTrue(afterRenewal.success)
        assertEquals(2, fake.loginRequests.size)
        assertEquals(SECOND_TOKEN, fake.settingsRequests.last().authorization)
    }

    @Test
    @Story("401 und Re-Login")
    fun `unauthorized settings triggers exactly one relogin and retry`() {
        fake.queueSettingsReply(Reply(401))
        val client = clientWith()

        val result = client.setPhaseMode(CHARGER_ID, PhaseMode.THREE)

        assertTrue(result.success)
        assertEquals(2, fake.loginRequests.size)
        assertEquals(listOf(FIRST_TOKEN, SECOND_TOKEN), fake.settingsRequests.map { it.authorization })
    }

    @Test
    @Story("401 und Re-Login")
    fun `repeated unauthorized settings gives up after one retry`() {
        fake.queueSettingsReply(Reply(401))
        fake.queueSettingsReply(Reply(401))

        val result = clientWith().setPhaseMode(CHARGER_ID, PhaseMode.THREE)

        assertFalse(result.success)
        assertContains(result.message, "HTTP 401")
        assertEquals(2, fake.loginRequests.size)
        assertEquals(2, fake.settingsRequests.size)
    }

    @Test
    @Story("Fehlerfälle")
    fun `server error reports status code without retry`() {
        fake.queueSettingsReply(Reply(500, "interner Fehler"))

        val result = clientWith().setPhaseMode(CHARGER_ID, PhaseMode.THREE)

        assertFalse(result.success)
        assertEquals("Easee-Cloud antwortete mit HTTP 500", result.message)
        assertEquals(1, fake.settingsRequests.size)
    }

    @Test
    @Story("Fehlerfälle")
    fun `failed login is reported and settings are not called`() {
        fake.queueLoginReply(Reply(401))

        val result = clientWith().setPhaseMode(CHARGER_ID, PhaseMode.THREE)

        assertFalse(result.success)
        assertEquals("Easee-Cloud-Login fehlgeschlagen: HTTP 401", result.message)
        assertEquals(0, fake.settingsRequests.size)
    }

    @Test
    @Story("Fehlerfälle")
    fun `login response without access token is reported`() {
        fake.queueLoginReply(Reply(200, """{"expiresIn":3600}"""))

        val result = clientWith().setPhaseMode(CHARGER_ID, PhaseMode.THREE)

        assertFalse(result.success)
        assertEquals(MSG_LOGIN_INVALID, result.message)
        assertEquals(0, fake.settingsRequests.size)
    }

    @Test
    @Story("Fehlerfälle")
    fun `login response that is not json is reported`() {
        fake.queueLoginReply(Reply(200, "kein json"))

        val result = clientWith().setPhaseMode(CHARGER_ID, PhaseMode.THREE)

        assertFalse(result.success)
        assertEquals(MSG_LOGIN_INVALID, result.message)
    }

    @Test
    @Story("Netzwerkfehler")
    fun `connection error is returned as failed result instead of exception`() {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val client = clientWith(baseUrl = "http://127.0.0.1:$unusedPort/api")

        val result = client.setPhaseMode(CHARGER_ID, PhaseMode.THREE)

        assertFalse(result.success)
        assertContains(result.message, "nicht erreichbar")
    }

    @Test
    @Story("Zugangsdaten")
    fun `missing username fails without network access`() {
        val result = clientWith(username = BLANK).setPhaseMode(CHARGER_ID, PhaseMode.THREE)

        assertEquals(false, result.success)
        assertEquals(MSG_CREDENTIALS_MISSING, result.message)
        assertEquals(0, fake.loginRequests.size + fake.settingsRequests.size)
    }

    @Test
    @Story("Zugangsdaten")
    fun `missing password fails without network access`() {
        val result = clientWith(password = BLANK).setPhaseMode(CHARGER_ID, PhaseMode.THREE)

        assertEquals(false, result.success)
        assertEquals(MSG_CREDENTIALS_MISSING, result.message)
        assertEquals(0, fake.loginRequests.size + fake.settingsRequests.size)
    }

    @Test
    @Story("Geheimnisse")
    fun `messages never contain password or token`() {
        val messages = mutableListOf<String>()
        fake.queueSettingsReply(Reply(401))
        fake.queueSettingsReply(Reply(401))
        messages += clientWith().setPhaseMode(CHARGER_ID, PhaseMode.THREE).message
        fake.queueLoginReply(Reply(500, """{"accessToken":"leak-me"}"""))
        messages += clientWith().setPhaseMode(CHARGER_ID, PhaseMode.ONE).message

        messages.forEach { message ->
            assertFalse(message.contains(PASSWORD), message)
            assertFalse(message.contains("token-"), message)
            assertFalse(message.contains("leak-me"), message)
            assertFalse(message.contains(USERNAME), message)
        }
    }

    private fun clientWith(
        username: String = USERNAME,
        password: String = PASSWORD,
        baseUrl: String = fake.baseUrl,
    ): EaseeCloudClient {
        val properties = AppProperties(
            cloud = EaseeCloudProperties(baseUrl = baseUrl, username = username, password = password),
        )
        return EaseeCloudClient(properties, clock)
    }
}
