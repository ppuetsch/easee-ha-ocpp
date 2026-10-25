package de.puetsch.easeeocpp.web

import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.PhaseMode
import de.puetsch.easeeocpp.domain.model.Watt
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val FALLBACK_TEXT = "–"

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val WATT_PATTERN = "#,##0"
private const val CLOCK_PATTERN = "HH:mm:ss"
private val BERLIN_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
private val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern(CLOCK_PATTERN).withZone(BERLIN_ZONE)

fun formatWatt(value: Watt?): String =
    value?.let { "${DecimalFormat(WATT_PATTERN, germanSymbols()).format(it)} W" } ?: FALLBACK_TEXT

fun formatAmpere(value: Ampere?): String = value?.let { "$it A" } ?: FALLBACK_TEXT

fun formatPhaseMode(phaseMode: PhaseMode?): String = phaseMode?.let { "${it.phaseCount}-phasig" } ?: FALLBACK_TEXT

fun formatPhaseLabel(phase: Int?): String = phase?.let { "L$it" } ?: FALLBACK_TEXT

fun formatClock(instant: Instant?): String = instant?.let { CLOCK_FORMATTER.format(it) } ?: FALLBACK_TEXT

fun formatAge(instant: Instant?, now: Instant): String {
    if (instant == null) return FALLBACK_TEXT
    val seconds = Duration.between(instant, now).seconds.coerceAtLeast(0)
    return when {
        seconds < SECONDS_PER_MINUTE -> "vor $seconds s"
        seconds < SECONDS_PER_HOUR -> "vor ${seconds / SECONDS_PER_MINUTE} min"
        else -> "vor ${seconds / SECONDS_PER_HOUR} h"
    }
}

fun formatClockWithAge(instant: Instant?, now: Instant): String =
    if (instant == null) FALLBACK_TEXT else "${formatClock(instant)} (${formatAge(instant, now)})"

fun formatSettleRemaining(settleUntil: Instant?, now: Instant): String {
    if (settleUntil == null) return FALLBACK_TEXT
    val remainingSeconds = Duration.between(now, settleUntil).seconds
    return if (remainingSeconds > 0) "noch $remainingSeconds s" else "keine Sperre"
}

fun formatOrFallback(text: String?): String = text?.takeIf { it.isNotBlank() } ?: FALLBACK_TEXT

private fun germanSymbols(): DecimalFormatSymbols = DecimalFormatSymbols(Locale.GERMANY)
