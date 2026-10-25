package de.puetsch.easeeocpp.ha

import de.puetsch.easeeocpp.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val HEARTBEAT_DOMAIN = "input_datetime"
private const val HEARTBEAT_SERVICE = "set_datetime"
private const val HEARTBEAT_THREAD_NAME = "ha-heartbeat"
private const val KEY_ENTITY_ID = "entity_id"
private const val KEY_TIMESTAMP = "timestamp"

/**
 * Schreibt periodisch einen Zeitstempel in ein input_datetime. Das Template-Sensor auf HA-Seite
 * meldet "offline", wenn der Zeitstempel veraltet. Der Epoch-Wert vermeidet Zeitzonenfragen.
 */
@Component
class HaHeartbeatPublisher(
    private val client: HaWebSocketClient,
    private val properties: AppProperties,
    private val clock: Clock = Clock.systemUTC(),
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(HaHeartbeatPublisher::class.java)

    @Volatile
    private var lastSuccessfulAt: Instant? = null

    @Volatile
    private var running = false
    private var scheduler: ScheduledExecutorService? = null

    fun publishHeartbeat(): Boolean {
        val now = clock.instant()
        val delivered = client.callService(HEARTBEAT_DOMAIN, HEARTBEAT_SERVICE, heartbeatData(now))
        if (delivered) lastSuccessfulAt = now
        return delivered
    }

    fun lastSuccessfulHeartbeatAt(): Instant? = lastSuccessfulAt

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        val interval = properties.ha.heartbeatInterval.toMillis()
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, HEARTBEAT_THREAD_NAME).apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleAtFixedRate(::publishHeartbeatSafely, interval, interval, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    override fun stop() {
        running = false
        scheduler?.shutdownNow()
        scheduler = null
    }

    override fun isRunning(): Boolean = running

    private fun heartbeatData(now: Instant): Map<String, Any?> = mapOf(
        KEY_ENTITY_ID to properties.ha.heartbeatEntity,
        KEY_TIMESTAMP to now.epochSecond,
    )

    private fun publishHeartbeatSafely() {
        runCatching { publishHeartbeat() }
            .onFailure { failure -> log.warn("Heartbeat fehlgeschlagen: {}", failure.message) }
    }
}
