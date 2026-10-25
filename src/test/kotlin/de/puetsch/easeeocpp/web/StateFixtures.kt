package de.puetsch.easeeocpp.web

import de.puetsch.easeeocpp.control.ControlState
import de.puetsch.easeeocpp.domain.model.AllocationResult
import de.puetsch.easeeocpp.domain.model.ChargeSettings
import de.puetsch.easeeocpp.domain.model.ChargerLimit
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import de.puetsch.easeeocpp.domain.model.ControlAction
import de.puetsch.easeeocpp.domain.model.ControlDecision
import de.puetsch.easeeocpp.domain.model.PhaseMode
import de.puetsch.easeeocpp.domain.model.SurplusInputs
import de.puetsch.easeeocpp.domain.model.SurplusResult
import java.time.Duration
import java.time.Instant

object StateFixtures {
    const val PRIO_CHARGER = "EHDFQLBX"
    const val SECOND_CHARGER = "EH2NSSVK"
    const val SKIPPED_REASON = "HA-Eingang sensor.solaredge_modbus_ac_power ist unavailable"

    fun fullState(now: Instant): ControlState = ControlState(
        evaluatedAt = now.minusSeconds(4),
        haConnected = true,
        actuationEnabled = true,
        inputs = surplusInputs(),
        settings = chargeSettings(),
        chargers = listOf(priorityChargerSnapshot(now), secondChargerSnapshot()),
        decision = controlDecision(now),
        skippedReason = null,
        lastAction = lastAction(now),
        history = history(now),
        settleUntil = now.plusSeconds(20),
        lastHeartbeatAt = now.minusSeconds(12),
    )

    fun shadowModeState(now: Instant): ControlState = fullState(now).copy(actuationEnabled = false)

    fun skippedState(now: Instant): ControlState = fullState(now).copy(
        haConnected = false,
        chargers = listOf(priorityChargerSnapshot(now).copy(connected = false)),
        decision = null,
        skippedReason = SKIPPED_REASON,
        settleUntil = null,
    )

    fun emptyState(): ControlState = ControlState(
        evaluatedAt = null,
        haConnected = false,
        actuationEnabled = false,
        inputs = null,
        settings = null,
        chargers = emptyList(),
        decision = null,
        skippedReason = null,
        lastAction = null,
        history = emptyList(),
        settleUntil = null,
        lastHeartbeatAt = null,
    )

    private fun surplusInputs(): SurplusInputs = SurplusInputs(
        pvPower = 4200.0,
        gridPower = -350.0,
        batteryPower = -120.0,
        evPower = 3100.0,
        securityBuffer = 200.0,
        preferCarOverBattery = true,
        phaseMode = PhaseMode.THREE,
    )

    private fun chargeSettings(): ChargeSettings = ChargeSettings(
        surplusChargeEnabled = true,
        surplusPhaseMode = PhaseMode.THREE,
        fixedPhaseMode = PhaseMode.ONE,
        fixedCurrent = 10,
        minimumCurrent = 6,
        priorityCharger = PRIO_CHARGER,
    )

    private fun priorityChargerSnapshot(now: Instant): ChargerSnapshot = ChargerSnapshot(
        chargerId = PRIO_CHARGER,
        connected = true,
        status = ChargerStatus.CHARGING,
        power = 2300.0,
        currentL1 = 6.0,
        currentL2 = 0.0,
        currentL3 = 0.0,
        voltageL1 = 231.0,
        voltageL2 = 232.5,
        voltageL3 = 230.0,
        lastMeterValueAt = now.minusSeconds(3),
        statusSince = now.minus(Duration.ofMinutes(5)),
    )

    private fun secondChargerSnapshot(): ChargerSnapshot = ChargerSnapshot(
        chargerId = SECOND_CHARGER,
        connected = false,
        status = ChargerStatus.AVAILABLE,
        power = 0.0,
        currentL1 = null,
        currentL2 = null,
        currentL3 = null,
        voltageL1 = null,
        voltageL2 = null,
        voltageL3 = null,
        lastMeterValueAt = null,
        statusSince = null,
    )

    private fun controlDecision(now: Instant): ControlDecision = ControlDecision(
        decidedAt = now.minusSeconds(4),
        phaseMode = PhaseMode.THREE,
        allocation = AllocationResult(
            limits = listOf(ChargerLimit(PRIO_CHARGER, 6), ChargerLimit(SECOND_CHARGER, 0)),
            totalCurrent = 6,
        ),
        surplus = SurplusResult(
            enoughPvPower = true,
            minRequiredPvPower = 1740.0,
            rawTargetPower = 3910.0,
            targetPower = 3710.0,
            targetCurrent = 6,
        ),
        phaseWithHighestVoltage = 2,
        reason = "Überschuss reicht für 6 A",
    )

    private fun lastAction(now: Instant): ControlAction = ControlAction(
        at = now.minusSeconds(10),
        chargerId = PRIO_CHARGER,
        kind = "SET_CURRENT",
        phaseMode = PhaseMode.THREE,
        current = 6,
        outcome = "OK",
    )

    private fun history(now: Instant): List<ControlAction> = listOf(
        ControlAction(now.minusSeconds(120), PRIO_CHARGER, "SET_PHASES", PhaseMode.ONE, null, "OK"),
        ControlAction(now.minusSeconds(60), PRIO_CHARGER, "SET_CURRENT", PhaseMode.ONE, 0, "OK"),
        lastAction(now),
    )
}
