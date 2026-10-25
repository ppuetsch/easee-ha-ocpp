package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.domain.model.ChargerStatus
import de.puetsch.easeeocpp.domain.model.Watt
import de.puetsch.easeeocpp.ocpp.messages.MeterValue
import de.puetsch.easeeocpp.ocpp.messages.SampledValue
import java.time.Duration
import java.time.Instant

const val MEASURAND_POWER_ACTIVE_IMPORT = "Power.Active.Import"
const val MEASURAND_CURRENT_IMPORT = "Current.Import"
const val MEASURAND_VOLTAGE = "Voltage"
const val UNIT_KILOWATT = "kW"
const val UNIT_WATT = "W"
private const val WATT_PER_KILOWATT = 1000.0
private const val PHASE_L1 = "L1"
private const val PHASE_L2 = "L2"
private const val PHASE_L3 = "L3"

object ChargerStatusMapping {
    private val chargerStatusByOcppName: Map<String, ChargerStatus> = mapOf(
        "Available" to ChargerStatus.AVAILABLE,
        "Preparing" to ChargerStatus.PREPARING,
        "Charging" to ChargerStatus.CHARGING,
        "SuspendedEV" to ChargerStatus.SUSPENDED_EV,
        "SuspendedEVSE" to ChargerStatus.SUSPENDED_EVSE,
        "Finishing" to ChargerStatus.FINISHING,
        "Reserved" to ChargerStatus.RESERVED,
        "Unavailable" to ChargerStatus.UNAVAILABLE,
        "Faulted" to ChargerStatus.FAULTED,
    )

    fun fromOcpp(ocppStatus: String): ChargerStatus? = chargerStatusByOcppName[ocppStatus]
}

data class PhaseValues(
    val l1: Double? = null,
    val l2: Double? = null,
    val l3: Double? = null,
) {
    fun withPhase(phase: String, value: Double): PhaseValues = when (phase) {
        PHASE_L1 -> copy(l1 = value)
        PHASE_L2 -> copy(l2 = value)
        PHASE_L3 -> copy(l3 = value)
        else -> this
    }

    companion object {
        val NONE = PhaseValues()
    }
}

data class MeterReading(
    val powerWatt: Watt?,
    val currents: PhaseValues,
    val voltages: PhaseValues,
)

object MeterReadingParser {
    private data class Sample(
        val measurand: String,
        val phase: String?,
        val value: Double,
        val unit: String?,
    ) {
        fun wattOrNull(): Watt? = when (unit) {
            null, UNIT_WATT -> value
            UNIT_KILOWATT -> value * WATT_PER_KILOWATT
            else -> null
        }
    }

    /** Ein SampledValue ohne Measurand hat in OCPP den Default `Energy.Active.Import.Register` und wird hier ignoriert. */
    fun parse(meterValues: List<MeterValue>): MeterReading {
        val samples = meterValues.flatMap { it.sampledValue }.mapNotNull(::toSampleOrNull)
        return MeterReading(
            powerWatt = powerWattOf(samples),
            currents = phaseValuesOf(samples, MEASURAND_CURRENT_IMPORT),
            voltages = phaseValuesOf(samples, MEASURAND_VOLTAGE),
        )
    }

    private fun toSampleOrNull(sampledValue: SampledValue): Sample? {
        val measurand = sampledValue.measurand ?: return null
        val number = sampledValue.value.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val phase = sampledValue.phase?.let(::normalizePhase)
        return Sample(measurand, phase, number, sampledValue.unit)
    }

    /** Easee/OCPP liefern Phasen als `L1` oder `L1-N`; nur der Leiter zählt. */
    private fun normalizePhase(rawPhase: String): String = rawPhase.substringBefore('-').trim()

    private fun powerWattOf(samples: List<Sample>): Watt? {
        val powerSamples = samples.filter { it.measurand == MEASURAND_POWER_ACTIVE_IMPORT }
        val totalSample = powerSamples.lastOrNull { it.phase == null }
        val totalWatt = totalSample?.wattOrNull()
        if (totalWatt != null) return totalWatt
        val phaseWatts = powerSamples.filter { it.phase != null }.mapNotNull { it.wattOrNull() }
        return phaseWatts.takeIf { it.isNotEmpty() }?.sum()
    }

    private fun phaseValuesOf(samples: List<Sample>, measurand: String): PhaseValues =
        samples
            .filter { it.measurand == measurand }
            .fold(PhaseValues.NONE) { values, sample ->
                sample.phase?.let { values.withPhase(it, sample.value) } ?: values
            }
}

/** Zustand eines Chargers. Veraltete Messwerte werden nicht gelöscht, sondern beim Lesen verworfen. */
class ChargerTelemetry {
    var status: ChargerStatus? = null
        private set
    var statusSince: Instant? = null
        private set
    var lastMeterValueAt: Instant? = null
        private set
    private var reading: MeterReading? = null

    fun changeStatus(newStatus: ChargerStatus, at: Instant) {
        if (newStatus == status) return
        status = newStatus
        statusSince = at
    }

    fun recordMeterValues(newReading: MeterReading, at: Instant) {
        reading = newReading
        lastMeterValueAt = at
    }

    fun clearMeterValues() {
        reading = null
        lastMeterValueAt = null
    }

    fun freshReading(now: Instant, maxAge: Duration): MeterReading? =
        reading?.takeIf { isFresh(now, maxAge) }

    private fun isFresh(now: Instant, maxAge: Duration): Boolean {
        val measuredAt = lastMeterValueAt ?: return false
        return Duration.between(measuredAt, now) <= maxAge
    }
}
