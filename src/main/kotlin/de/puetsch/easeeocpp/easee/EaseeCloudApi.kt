package de.puetsch.easeeocpp.easee

import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.PhaseMode
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val CONNECT_TIMEOUT_SECONDS = 5L
private const val REQUEST_TIMEOUT_SECONDS = 15L
private const val MEDIA_TYPE_JSON = "application/json"
private const val HEADER_ACCEPT = "Accept"
private const val HEADER_AUTHORIZATION = "Authorization"
private const val HEADER_CONTENT_TYPE = "Content-Type"
private const val LOGIN_PATH = "/accounts/login"
private const val MSG_LOGIN_INVALID_RESPONSE = "Easee-Cloud-Login: ungültige Antwort"
private const val SUCCESS_STATUS_MIN = 200
private const val SUCCESS_STATUS_MAX = 299

internal data class LoginRequest(val userName: String, val password: String)

internal data class LoginResponse(val accessToken: String? = null, val expiresIn: Long? = null)

internal data class PhaseModeRequest(val phaseMode: Int)

internal data class LoginToken(val value: String, val expiresInSeconds: Long)

internal class EaseeCloudException(message: String) : RuntimeException(message)

internal fun isSuccessfulStatus(statusCode: Int): Boolean =
    statusCode in SUCCESS_STATUS_MIN..SUCCESS_STATUS_MAX

internal class EaseeCloudApi(
    private val baseUrl: String,
    private val json: ObjectMapper = jacksonObjectMapper(),
    private val httpClient: HttpClient = newHttpClient(),
) {
    fun login(username: String, password: String): LoginToken {
        val requestBody = json.writeValueAsString(LoginRequest(username, password))
        val request = jsonRequestBuilder(loginUri()).POST(HttpRequest.BodyPublishers.ofString(requestBody)).build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        ensureSuccessful(response, "Easee-Cloud-Login")
        return parseLoginToken(response.body())
    }

    fun setPhaseMode(bearerToken: String, chargerId: ChargerId, phaseMode: PhaseMode): HttpResponse<String> {
        val requestBody = json.writeValueAsString(PhaseModeRequest(phaseMode.phaseCount))
        val request = jsonRequestBuilder(settingsUri(chargerId))
            .header(HEADER_AUTHORIZATION, "Bearer $bearerToken")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun loginUri(): URI = URI.create(baseUrl + LOGIN_PATH)

    private fun settingsUri(chargerId: ChargerId): URI = URI.create("$baseUrl/chargers/$chargerId/settings")

    private fun parseLoginToken(responseBody: String): LoginToken {
        val parsed: LoginResponse? = try {
            json.readValue(responseBody, LoginResponse::class.java)
        } catch (exception: JacksonException) {
            throw EaseeCloudException(MSG_LOGIN_INVALID_RESPONSE)
        }
        val accessToken = parsed?.accessToken?.takeIf { it.isNotBlank() } ?: throw EaseeCloudException(MSG_LOGIN_INVALID_RESPONSE)
        val expiresIn = parsed.expiresIn ?: throw EaseeCloudException(MSG_LOGIN_INVALID_RESPONSE)
        return LoginToken(accessToken, expiresIn)
    }

    private fun ensureSuccessful(response: HttpResponse<String>, operation: String) {
        if (!isSuccessfulStatus(response.statusCode())) {
            throw EaseeCloudException("$operation fehlgeschlagen: HTTP ${response.statusCode()}")
        }
    }

    private fun jsonRequestBuilder(uri: URI): HttpRequest.Builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header(HEADER_ACCEPT, MEDIA_TYPE_JSON)
            .header(HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON)

    private companion object {
        fun newHttpClient(): HttpClient =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build()
    }
}
