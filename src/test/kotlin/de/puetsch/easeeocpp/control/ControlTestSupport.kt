package de.puetsch.easeeocpp.control

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.config.ControlProperties
import de.puetsch.easeeocpp.domain.CurrentAllocator
import de.puetsch.easeeocpp.domain.EligibilityPolicy
import de.puetsch.easeeocpp.domain.MutableClock
import de.puetsch.easeeocpp.domain.PauseHysteresis
import de.puetsch.easeeocpp.domain.PhaseVoltageEvaluator
import de.puetsch.easeeocpp.domain.SurplusCalculator
import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import de.puetsch.easeeocpp.domain.model.EntityId
import de.puetsch.easeeocpp.domain.model.PhaseMode
import de.puetsch.easeeocpp.domain.model.Watt
import de.puetsch.easeeocpp.ha.HaConnectionProblemSource
import de.puetsch.easeeocpp.ha.HaHeartbeatPublisher
import de.puetsch.easeeocpp.ports.ActuationResult
import de.puetsch.easeeocpp.ports.ChargerFleet
import de.puetsch.easeeocpp.ports.HaEntityState
import de.puetsch.easeeocpp.ports.HomeAssistantStates
import de.puetsch.easeeocpp.ports.PhaseModeGateway
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import java.time.Instant

const val MULTONNEN_ID: ChargerId = "EHDFQLBX"
const val EINGANGSSEITE_ID: ChargerId = "EH2NSSVK"
val CONTROL_START: Instant = Instant.parse("2026-10-25T10:00:00Z")

/** Stellt die Home-Assistant-Eingänge in einem Test ein. Fehlende Einträge gelten als fehlend. */
class FakeHomeAssistantStates : HomeAssistantStates, HaConnectionProblemSource {
    var connected: Boolean = true
    var problem: String? = null
    private val entries = mutableMapOf<EntityId, HaEntityState>()

    override fun isConnected(): Boolean = connected

    override fun connectionProblem(): String? = problem

    override fun state(entityId: EntityId): HaEntityState? = entries[entityId]

    fun set(entityId: EntityId, value: String) {
        entries[entityId] = HaEntityState(entityId, value, lastReported = null)
    }

    fun clear(entityId: EntityId) {
        entries.remove(entityId)
    }
}

/**
 * Zeichnet alle Aufrufe an Charger in einer gemeinsamen Liste auf, damit die Reihenfolge prüfbar ist.
 * Format: "phase:<id>:<phasen>" bzw. "limit:<id>:<ampere>".
 */
class FakeCharging : ChargerFleet, PhaseModeGateway {
    val calls: MutableList<String> = mutableListOf()
    var snapshots: List<ChargerSnapshot> = emptyList()
    var snapshotFailure: Throwable? = null
    var failingPhaseMode: Boolean = false

    override fun snapshots(): List<ChargerSnapshot> {
        snapshotFailure?.let { throw it }
        return snapshots
    }

    override fun applyLimit(chargerId: ChargerId, current: Ampere): ActuationResult {
        calls += "limit:$chargerId:$current"
        return ActuationResult(success = true, message = "ok")
    }

    override fun setPhaseMode(chargerId: ChargerId, phaseMode: PhaseMode): ActuationResult {
        calls += "phase:$chargerId:${phaseMode.phaseCount}"
        return if (failingPhaseMode) {
            ActuationResult(success = false, message = "Cloud nicht erreichbar")
        } else {
            ActuationResult(success = true, message = "ok")
        }
    }
}

fun chargerSnapshot(
    chargerId: ChargerId,
    status: ChargerStatus = ChargerStatus.CHARGING,
    power: Watt = 0.0,
    connected: Boolean = true,
    statusSince: Instant? = CONTROL_START,
    voltages: Triple<Double?, Double?, Double?> = Triple(null, null, null),
    currents: Triple<Double?, Double?, Double?> = Triple(null, null, null),
): ChargerSnapshot = ChargerSnapshot(
    chargerId = chargerId,
    connected = connected,
    status = status,
    power = power,
    currentL1 = currents.first,
    currentL2 = currents.second,
    currentL3 = currents.third,
    voltageL1 = voltages.first,
    voltageL2 = voltages.second,
    voltageL3 = voltages.third,
    lastMeterValueAt = CONTROL_START,
    statusSince = statusSince,
)

fun defaultControlProperties(historyFile: Path): ControlProperties = ControlProperties(
    actuationEnabled = true,
    historyFile = historyFile.toString(),
)

/**
 * Verdrahtet die echten Domain-Klassen mit Fakes für die Ports und einer festen Uhr.
 * Standardwerte: Überschuss an, 3 Phasen, 16 A Ziel, Mindeststrom 6 A, PV 5000 W, Prio Mülltonnen.
 */
class ControlHarness(historyFile: Path, control: ControlProperties = defaultControlProperties(historyFile)) {
    val clock = MutableClock(CONTROL_START)
    val properties = AppProperties(control = control)
    val states = FakeHomeAssistantStates()
    val charging = FakeCharging()
    val history = ControlHistory(properties)
    val stateHolder = ControlStateHolder(heartbeatPublisher(), properties)
    private val loop = buildLoop()

    init {
        populateDefaultStates()
        charging.snapshots = listOf(
            chargerSnapshot(MULTONNEN_ID, power = 5000.0),
            chargerSnapshot(EINGANGSSEITE_ID),
        )
    }

    fun runCycle() = loop.runCycle()

    fun state(): ControlState = stateHolder.current()

    fun setEntity(entityId: EntityId, value: String) = states.set(entityId, value)

    private fun heartbeatPublisher(): HaHeartbeatPublisher = mockk {
        every { lastSuccessfulHeartbeatAt() } returns null
    }

    private fun buildLoop(): ControlLoop {
        val cycle = ControlCycle(
            surplusCalculator = SurplusCalculator(properties.control),
            currentAllocator = CurrentAllocator(properties.control),
            eligibilityPolicy = EligibilityPolicy(properties.control),
            phaseVoltageEvaluator = PhaseVoltageEvaluator(),
            pauseHysteresis = PauseHysteresis(clock, properties.control),
            properties = properties,
        )
        return ControlLoop(
            settingsReader = HaSettingsReader(states, properties),
            haStates = states,
            connectionProblems = states,
            chargerFleet = charging,
            phaseModeGateway = charging,
            cycle = cycle,
            history = history,
            stateHolder = stateHolder,
            properties = properties,
            clock = clock,
        )
    }

    private fun populateDefaultStates() {
        val entities = properties.ha.entities
        states.set(entities.pvPower, "5000.0")
        states.set(entities.gridPower, "0.0")
        states.set(entities.batteryPower, "0.0")
        states.set(entities.securityBuffer, "0.0")
        states.set(entities.preferCarOverBattery, "off")
        states.set(entities.pvSurplusCharge, "on")
        states.set(entities.targetPhases, "3")
        states.set(entities.targetCurrent, "16.0")
        states.set(entities.minimumEvCurrent, "6.0")
        states.set(entities.priorityCharger, "Mülltonnen")
    }
}
