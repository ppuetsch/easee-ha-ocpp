package de.puetsch.easeeocpp.domain

import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.model.Ampere
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Ein kurzes Aussetzen des Überschusses (Wolke, Batteriewechsel) soll das Fahrzeug nicht sofort pausieren.
 * Null A wird erst ausgegeben, wenn der Bedarf ununterbrochen länger als die Pausenverzögerung anliegt.
 */
class PauseHysteresis(private val clock: Clock, private val properties: ControlProperties) {

    private var lastOutput: Ampere = 0
    private var zeroDemandSince: Instant? = null

    fun filter(desired: Ampere): Ampere {
        val output = if (desired > 0) chargeWith(desired) else pauseOrHold()
        lastOutput = output
        return output
    }

    private fun chargeWith(desired: Ampere): Ampere {
        zeroDemandSince = null
        return desired
    }

    private fun pauseOrHold(): Ampere {
        if (lastOutput <= 0) return 0
        val demandStart = zeroDemandSince ?: startZeroDemand()
        return if (pauseDelayExceeded(demandStart)) 0 else properties.minChargeCurrent
    }

    private fun startZeroDemand(): Instant {
        val now = clock.instant()
        zeroDemandSince = now
        return now
    }

    private fun pauseDelayExceeded(demandStart: Instant): Boolean =
        Duration.between(demandStart, clock.instant()) > properties.pauseDelay
}
