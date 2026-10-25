package de.puetsch.easeeocpp.ha

/**
 * Meldet einen dauerhaften Verbindungsfehler, der ein Wiederverbinden nicht lohnt (z.B. abgelehnter Token).
 * Null bedeutet: kein solcher Fehler.
 */
interface HaConnectionProblemSource {
    fun connectionProblem(): String?
}
