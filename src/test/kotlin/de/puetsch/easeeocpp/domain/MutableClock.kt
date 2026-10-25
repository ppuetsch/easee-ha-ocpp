package de.puetsch.easeeocpp.domain

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(private var currentInstant: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {

    override fun getZone(): ZoneId = zone

    override fun withZone(newZone: ZoneId): Clock = MutableClock(currentInstant, newZone)

    override fun instant(): Instant = currentInstant

    fun advance(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }
}
