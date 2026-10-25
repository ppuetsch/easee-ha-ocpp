package de.puetsch.easeeocpp.ha

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.EntityId
import de.puetsch.easeeocpp.ports.HaEntityState
import de.puetsch.easeeocpp.ports.HomeAssistantStates
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val CALL_SERVICE_TIMEOUT: Duration = Duration.ofSeconds(10)
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
private val PING_INTERVAL: Duration = Duration.ofSeconds(20)
private const val SCHEDULER_THREAD_NAME = "ha-websocket"
private const val MISSED_PONGS_REASON = "keine Antwort auf Ping"
private const val UNANSWERED_CALLS_REASON = "Service-Aufrufe ohne Antwort"

@Component
class HaWebSocketClient(
    private val properties: AppProperties,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val commandTimeout: Duration = CALL_SERVICE_TIMEOUT,
    private val pingInterval: Duration = PING_INTERVAL,
) : HomeAssistantStates, HaConnectionProblemSource, SmartLifecycle {
    private val log = LoggerFactory.getLogger(HaWebSocketClient::class.java)
    private val connectionObserver = object : HaConnectionObserver {
        override fun connectionReady(connection: HaConnection) = onConnectionReady(connection)
        override fun connectionLost(connection: HaConnection, reason: String) = onConnectionLost(connection, reason)
        override fun authRejected(connection: HaConnection, reason: String) = onAuthRejected(connection, reason)
    }
    private val stateCache = HaStateCache()
    private val commands = HaCommandRegistry()
    private val liveness = HaLivenessMonitor()
    private val reconnectBackoff = HaReconnectBackoff(
        minimumDelay = properties.ha.reconnectBackoffMin,
        maximumDelay = properties.ha.reconnectBackoffMax,
    )

    @Volatile
    private var running = false

    @Volatile
    private var activeConnection: HaConnection? = null

    @Volatile
    private var authRejectedReason: String? = null
    private var scheduler: ScheduledExecutorService? = null

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        reconnectBackoff.reset()
        authRejectedReason = null
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, SCHEDULER_THREAD_NAME).apply { isDaemon = true }
        }
        scheduler = executor
        val intervalMillis = pingInterval.toMillis()
        executor.scheduleAtFixedRate(::pingActiveConnectionSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS)
        connect()
    }

    @Synchronized
    override fun stop() {
        running = false
        activeConnection?.close()
        activeConnection = null
        commands.failAll()
        scheduler?.shutdownNow()
        scheduler = null
    }

    override fun isRunning(): Boolean = running

    override fun isConnected(): Boolean = activeConnection?.isReady() == true

    override fun connectionProblem(): String? = authRejectedReason?.let { reason -> "Token abgelehnt: $reason" }

    override fun state(entityId: EntityId): HaEntityState? = stateCache.get(entityId)

    fun callService(domain: String, service: String, data: Map<String, Any?>): Boolean {
        val connection = activeConnection?.takeIf { it.isReady() } ?: return false
        val commandId = commands.reserveId()
        val pendingResult = commands.expectResult(commandId)
        return try {
            connection.send(HaMessageCodec.callServiceMessage(commandId, domain, service, data))
            val delivered = pendingResult.get(commandTimeout.toMillis(), TimeUnit.MILLISECONDS)
            liveness.callAnswered()
            delivered
        } catch (timeout: TimeoutException) {
            log.warn("call_service {}.{} ohne Antwort nach {}", domain, service, commandTimeout)
            recordUnansweredCall(connection)
        } catch (failure: ExecutionException) {
            log.warn("call_service {}.{} fehlgeschlagen: {}", domain, service, failure.message)
            recordUnansweredCall(connection)
        } catch (failure: IllegalStateException) {
            log.warn("call_service {}.{} nicht gesendet: {}", domain, service, failure.message)
            recordUnansweredCall(connection)
        } finally {
            commands.cancel(commandId)
        }
    }

    private fun recordUnansweredCall(connection: HaConnection): Boolean {
        if (liveness.callUnanswered()) connection.abort(UNANSWERED_CALLS_REASON)
        return false
    }

    private fun pingActiveConnectionSafely() {
        runCatching { pingActiveConnection() }
            .onFailure { failure -> log.warn("Ping-Prüfung fehlgeschlagen: {}", failure.message) }
    }

    private fun pingActiveConnection() {
        val connection = activeConnection?.takeIf { it.isReady() } ?: return
        if (liveness.registerPingTick()) {
            connection.abort(MISSED_PONGS_REASON)
            return
        }
        val pingId = commands.reserveId()
        liveness.pingSent()
        runCatching { connection.send(HaMessageCodec.pingMessage(pingId)) }
            .onFailure { failure -> connection.abort("Ping nicht gesendet: ${failure.message}") }
    }

    @Synchronized
    private fun onConnectionReady(connection: HaConnection) {
        if (connection !== activeConnection) return
        reconnectBackoff.reset()
        log.info("Mit Home Assistant verbunden, Zustand geladen und Events abonniert")
    }

    @Synchronized
    private fun onConnectionLost(connection: HaConnection, reason: String) {
        if (connection !== activeConnection) return
        activeConnection = null
        commands.failAll()
        log.warn("Verbindung zu Home Assistant verloren: {}", reason)
        scheduleReconnect()
    }

    /** Ein abgelehnter Token wird mit jedem Versuch nur wiederholt, bis HA die IP sperrt. Daher kein Reconnect. */
    @Synchronized
    private fun onAuthRejected(connection: HaConnection, reason: String) {
        if (connection !== activeConnection) return
        activeConnection = null
        commands.failAll()
        authRejectedReason = reason
        log.error(
            "Home Assistant hat den Token abgelehnt ({}). Verbindungsversuche sind eingestellt; Token korrigieren und Anwendung neu starten.",
            reason,
        )
    }

    @Synchronized
    private fun connect() {
        if (!running) return
        liveness.reset()
        val connection = HaConnection(properties.ha.token, stateCache, commands, liveness, connectionObserver)
        activeConnection = connection
        httpClient.newWebSocketBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .buildAsync(URI.create(properties.ha.url), connection)
            .whenComplete { _, failure ->
                if (failure != null) onConnectionLost(connection, "Verbindungsaufbau fehlgeschlagen: ${failure.message}")
            }
    }

    private fun scheduleReconnect() {
        if (!running) return
        val delay = reconnectBackoff.takeNextDelay()
        log.info("Neuer Verbindungsversuch zu Home Assistant in {}", delay)
        scheduler?.schedule({ reconnectNow() }, delay.toMillis(), TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun reconnectNow() {
        connect()
    }
}
