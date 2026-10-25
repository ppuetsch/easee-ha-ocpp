package de.puetsch.easeeocpp.ha

import java.time.Duration

/**
 * Verdoppelt die Wartezeit zwischen Verbindungsversuchen bis zur Obergrenze, damit ein
 * dauerhaft ausgefallenes HA nicht im Sekundentakt angesprochen wird.
 */
internal class HaReconnectBackoff(
    private val minimumDelay: Duration,
    private val maximumDelay: Duration,
) {
    private var upcomingDelay: Duration = minimumDelay

    fun takeNextDelay(): Duration {
        val delay = upcomingDelay
        upcomingDelay = minOf(delay.multipliedBy(GROWTH_FACTOR), maximumDelay)
        return delay
    }

    fun reset() {
        upcomingDelay = minimumDelay
    }

    private companion object {
        const val GROWTH_FACTOR = 2L
    }
}
