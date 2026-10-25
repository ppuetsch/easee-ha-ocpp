package de.puetsch.easeeocpp.ha

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.config.HaProperties
import de.puetsch.easeeocpp.testsupport.FAKE_HA_DEFAULT_TOKEN
import org.awaitility.Awaitility.await
import java.time.Duration

private val AWAIT_TIMEOUT: Duration = Duration.ofSeconds(10)
private val AWAIT_POLL_INTERVAL: Duration = Duration.ofMillis(20)
private val TEST_BACKOFF_MIN: Duration = Duration.ofMillis(50)
private val TEST_BACKOFF_MAX: Duration = Duration.ofMillis(200)

internal fun haProperties(
    port: Int,
    token: String = FAKE_HA_DEFAULT_TOKEN,
    heartbeatInterval: Duration = Duration.ofSeconds(30),
): AppProperties = AppProperties(
    ha = HaProperties(
        url = "ws://127.0.0.1:$port/api/websocket",
        token = token,
        heartbeatInterval = heartbeatInterval,
        reconnectBackoffMin = TEST_BACKOFF_MIN,
        reconnectBackoffMax = TEST_BACKOFF_MAX,
    ),
)

internal fun awaitCondition(condition: () -> Boolean) {
    await().atMost(AWAIT_TIMEOUT).pollInterval(AWAIT_POLL_INTERVAL).until { condition() }
}
