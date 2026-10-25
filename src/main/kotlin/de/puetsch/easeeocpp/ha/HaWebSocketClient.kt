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
private const val SCHEDULER_THREAD_NAME = "ha-websocket"

@Component
class HaWebSocketClient(
    private val properties: AppProperties,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val commandTimeout: Duration = CALL_SERVICE_TIMEOUT,
) : HomeAssistantStates, SmartLifecycle {
    private val log = LoggerFactory.getLogger(HaWebSocketClient::class.java)
    private val connectionObserver = object : HaConnectionObserver {
        override fun connectionReady(connection: HaConnection) = onConnectionReady(connection)
        override fun connectionLost(connection: HaConnection, reason: String) = onConnectionLost(connection, reason)
    }
    private val stateCache = HaStateCache()
    private val commands = HaCommandRegistry()
    private val reconnectBackoff = HaReconnectBackoff(
        minimumDelay = properties.ha.reconnectBackoffMin,
        maximumDelay = properties.ha.reconnectBackoffMax,
    )

    @Volatile
    private var running = false

    @Volatile
    private var activeConnection: HaConnection? = null
    private var scheduler: ScheduledExecutorService? = null

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        reconnectBackoff.reset()
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, SCHEDULER_THREAD_NAME).apply { isDaemon = true }
        }
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

    override fun state(entityId: EntityId): HaEntityState? = stateCache.get(entityId)

    fun callService(domain: String, service: String, data: Map<String, Any?>): Boolean {
        val connection = activeConnection?.takeIf { it.isReady() } ?: return false
        val commandId = commands.reserveId()
        val pendingResult = commands.expectResult(commandId)
        return try {
            connection.send(HaMessageCodec.callServiceMessage(commandId, domain, service, data))
            pendingResult.get(commandTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            log.warn("call_service {}.{} ohne Antwort nach {}", domain, service, commandTimeout)
            false
        } catch (failure: ExecutionException) {
            log.warn("call_service {}.{} fehlgeschlagen: {}", domain, service, failure.message)
            false
        } catch (failure: IllegalStateException) {
            log.warn("call_service {}.{} nicht gesendet: {}", domain, service, failure.message)
            false
        } finally {
            commands.cancel(commandId)
        }
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

    @Synchronized
    private fun connect() {
        if (!running) return
        val connection = HaConnection(properties.ha.token, stateCache, commands, connectionObserver)
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
