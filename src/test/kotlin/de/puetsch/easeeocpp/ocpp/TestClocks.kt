package de.puetsch.easeeocpp.ocpp

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

val TEST_START_INSTANT: Instant = Instant.parse("2026-10-25T12:00:00Z")

class MutableTestClock(private var now: Instant = TEST_START_INSTANT) : Clock() {
    fun advance(by: Duration) {
        now = now.plus(by)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}
