package de.puetsch.easeeocpp.server

import de.puetsch.easeeocpp.testsupport.TestCertificates
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.TimeUnit

private const val CONNECT_TIMEOUT_SECONDS = 10L
private const val TEST_CHARGER_ID = "EHDFQLBX"
const val TEST_OCPP_SUBPROTOCOL = "ocpp1.6"

fun freeTcpPort(): Int = ServerSocket(0).use { it.localPort }

fun trustingHttpClient(): HttpClient =
    HttpClient.newBuilder().sslContext(TestCertificates.trustingSslContext()).build()

fun defaultTrustHttpClient(): HttpClient = HttpClient.newHttpClient()

fun chargerWebSocketUri(scheme: String, port: Int): URI =
    URI.create("$scheme://localhost:$port/ocpp/$TEST_CHARGER_ID")

fun connectWebSocket(client: HttpClient, uri: URI): WebSocket =
    client.newWebSocketBuilder()
        .subprotocols(TEST_OCPP_SUBPROTOCOL)
        .buildAsync(uri, object : WebSocket.Listener {})
        .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

fun httpStatus(client: HttpClient, uri: URI): Int =
    client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()
