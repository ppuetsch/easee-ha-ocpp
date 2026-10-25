package de.puetsch.easeeocpp.easee

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

private const val STATUS_OK = 200
private const val LOGIN_SUFFIX = "/accounts/login"
private const val SETTINGS_SUFFIX = "/settings"
private const val DEFAULT_EXPIRES_IN_SECONDS = 3600
private const val NO_BODY_LENGTH = -1L
private const val NO_DELAY_SECONDS = 0

data class RecordedRequest(
    val method: String,
    val path: String,
    val authorization: String?,
    val contentType: String?,
    val accept: String?,
    val body: String,
)

data class Reply(val status: Int, val body: String = "")

class FakeEaseeCloud : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()
    private val queuedLoginReplies = CopyOnWriteArrayList<Reply>()
    private val queuedSettingsReplies = CopyOnWriteArrayList<Reply>()
    private var issuedTokenCount = 0

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}/api"

    val loginRequests: List<RecordedRequest>
        get() = recordedRequests.filter { it.path.endsWith(LOGIN_SUFFIX) }

    val settingsRequests: List<RecordedRequest>
        get() = recordedRequests.filter { it.path.endsWith(SETTINGS_SUFFIX) }

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    fun queueLoginReply(reply: Reply) {
        queuedLoginReplies.add(reply)
    }

    fun queueSettingsReply(reply: Reply) {
        queuedSettingsReplies.add(reply)
    }

    override fun close() {
        server.stop(NO_DELAY_SECONDS)
    }

    private fun handle(exchange: HttpExchange) {
        val request = recordRequest(exchange)
        val reply = if (request.path.endsWith(LOGIN_SUFFIX)) nextLoginReply() else nextSettingsReply()
        respond(exchange, reply)
    }

    private fun recordRequest(exchange: HttpExchange): RecordedRequest {
        val request = RecordedRequest(
            method = exchange.requestMethod,
            path = exchange.requestURI.path,
            authorization = exchange.requestHeaders.getFirst("Authorization"),
            contentType = exchange.requestHeaders.getFirst("Content-Type"),
            accept = exchange.requestHeaders.getFirst("Accept"),
            body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
        )
        recordedRequests.add(request)
        return request
    }

    private fun nextLoginReply(): Reply =
        queuedLoginReplies.removeFirstOrNull() ?: defaultLoginReply()

    private fun nextSettingsReply(): Reply =
        queuedSettingsReplies.removeFirstOrNull() ?: Reply(STATUS_OK)

    private fun defaultLoginReply(): Reply {
        issuedTokenCount++
        return Reply(STATUS_OK, """{"accessToken":"token-$issuedTokenCount","expiresIn":$DEFAULT_EXPIRES_IN_SECONDS}""")
    }

    private fun respond(exchange: HttpExchange, reply: Reply) {
        val bytes = reply.body.toByteArray(Charsets.UTF_8)
        val length = if (bytes.isEmpty()) NO_BODY_LENGTH else bytes.size.toLong()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(reply.status, length)
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}
