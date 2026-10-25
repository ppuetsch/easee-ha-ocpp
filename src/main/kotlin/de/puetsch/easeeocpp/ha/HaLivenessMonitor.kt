package de.puetsch.easeeocpp.ha

private const val MAX_MISSED_PONGS = 2
private const val MAX_UNANSWERED_CALLS = 3

/**
 * Erkennt eine halboffene Verbindung: bleiben Pong-Antworten wiederholt aus, oder laufen Service-Aufrufe
 * wiederholt in ein Timeout, gilt die Verbindung als tot, obwohl der Socket noch offen wirkt.
 * Jede Antwort von HA (Pong oder Ergebnis) belegt, dass die Gegenstelle lebt, und setzt die Zählung zurück.
 */
internal class HaLivenessMonitor(
    private val maxMissedPongs: Int = MAX_MISSED_PONGS,
    private val maxUnansweredCalls: Int = MAX_UNANSWERED_CALLS,
) {
    private var pingPending = false
    private var missedPongs = 0
    private var unansweredCalls = 0

    /** Wird pro Ping-Intervall vor dem nächsten Ping aufgerufen; true, wenn die Verbindung tot ist. */
    @Synchronized
    fun registerPingTick(): Boolean {
        if (pingPending) missedPongs++
        return missedPongs >= maxMissedPongs
    }

    @Synchronized
    fun pingSent() {
        pingPending = true
    }

    @Synchronized
    fun pongReceived() {
        pingPending = false
        missedPongs = 0
    }

    @Synchronized
    fun callAnswered() {
        unansweredCalls = 0
    }

    /** true, wenn so viele Aufrufe in Folge ohne Antwort geblieben sind, dass die Verbindung tot ist. */
    @Synchronized
    fun callUnanswered(): Boolean {
        unansweredCalls++
        return unansweredCalls >= maxUnansweredCalls
    }

    @Synchronized
    fun reset() {
        pingPending = false
        missedPongs = 0
        unansweredCalls = 0
    }
}
