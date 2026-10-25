package de.puetsch.easeeocpp.web

import de.puetsch.easeeocpp.control.AllocationMode
import de.puetsch.easeeocpp.control.ControlState
import de.puetsch.easeeocpp.control.SINGLE_CHARGER_MODE
import de.puetsch.easeeocpp.domain.model.ChargeSettings
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import de.puetsch.easeeocpp.domain.model.ControlAction
import de.puetsch.easeeocpp.domain.model.ControlDecision
import de.puetsch.easeeocpp.domain.model.SurplusInputs
import de.puetsch.easeeocpp.domain.model.SurplusResult
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

private const val CSS_OK = "ok"
private const val CSS_WARNUNG = "warnung"
private const val TEXT_JA = "an"
private const val TEXT_NEIN = "aus"
private const val TEXT_VERBUNDEN = "verbunden"
private const val TEXT_GETRENNT = "getrennt"
private const val TEXT_HA_VERBUNDEN = "verbunden"
private const val TEXT_HA_GETRENNT = "getrennt"
private const val TEXT_AUTO_VOR_BATTERIE = "Auto vor Batterie"
private const val TEXT_BATTERIE_VOR_AUTO = "Batterie vor Auto"
private const val TEXT_JA_WORT = "ja"
private const val TEXT_NEIN_WORT = "nein"
private const val TEXT_VERTEILUNG_PRIORISIERT = "Priorisiert"
private const val TEXT_VERTEILUNG_GETEILT = "Geteilt"
private const val TEXT_VERTEILUNG_EIN_AUTO = "Ein Auto"

@Component
class StateViewFactory(private val clock: Clock) {

    fun create(state: ControlState): StateView {
        val now: Instant = clock.instant()
        return StateView(
            schattenmodus = !state.actuationEnabled,
            auswertungZeit = formatClockWithAge(state.evaluatedAt, now),
            uebersprungenGrund = formatOrFallback(state.skippedReason),
            verbindungen = connectionsView(state, now),
            eingaenge = inputsView(state.inputs, state.settings),
            ergebnis = resultView(state.decision, state.skippedReason),
            letzterEingriff = lastActionView(state.lastAction, now),
            historie = state.history.sortedByDescending { it.at }.map { historyRowView(it, now) },
            settleRestzeit = formatSettleRemaining(state.settleUntil, now),
        )
    }

    private fun connectionsView(state: ControlState, now: Instant): ConnectionsView =
        ConnectionsView(
            haStatus = if (state.haConnected) TEXT_HA_VERBUNDEN else TEXT_HA_GETRENNT,
            haStatusKlasse = if (state.haConnected) CSS_OK else CSS_WARNUNG,
            heartbeat = formatClockWithAge(state.lastHeartbeatAt, now),
            chargers = state.chargers.map { chargerView(it, now) },
        )

    private fun chargerView(charger: ChargerSnapshot, now: Instant): ChargerView =
        ChargerView(
            id = charger.chargerId,
            verbindung = if (charger.connected) TEXT_VERBUNDEN else TEXT_GETRENNT,
            verbindungKlasse = if (charger.connected) CSS_OK else CSS_WARNUNG,
            status = chargerStatusLabel(charger.status),
            autoAngesteckt = if (charger.status.hasVehiclePluggedIn()) TEXT_JA_WORT else TEXT_NEIN_WORT,
            leistung = formatWatt(charger.power),
            letzterMesswert = formatClockWithAge(charger.lastMeterValueAt, now),
        )

    private fun inputsView(inputs: SurplusInputs?, settings: ChargeSettings?): InputsView =
        InputsView(
            pvLeistung = formatWatt(inputs?.pvPower),
            netzLeistung = formatWatt(inputs?.gridPower),
            batterieLeistung = formatWatt(inputs?.batteryPower),
            evLeistung = formatWatt(inputs?.evPower),
            puffer = formatWatt(inputs?.securityBuffer),
            mindeststrom = formatAmpere(settings?.minimumCurrent),
            zielphasen = formatPhaseMode(settings?.surplusPhaseMode),
            istPhasen = formatPhaseMode(inputs?.phaseMode),
            ueberschuss = if (settings?.surplusChargeEnabled == true) TEXT_JA else TEXT_NEIN,
            batteriePraeferenz = batteriePraeferenzText(inputs),
            prioCharger = formatOrFallback(settings?.priorityCharger),
        )

    private fun batteriePraeferenzText(inputs: SurplusInputs?): String = when (inputs?.preferCarOverBattery) {
        true -> TEXT_AUTO_VOR_BATTERIE
        false -> TEXT_BATTERIE_VOR_AUTO
        null -> FALLBACK_TEXT
    }

    private fun resultView(decision: ControlDecision?, skippedReason: String?): ResultView {
        val surplus: SurplusResult? = decision?.surplus
        return ResultView(
            mindestPvLeistung = formatWatt(surplus?.minRequiredPvPower),
            rohleistung = formatWatt(surplus?.rawTargetPower),
            zielleistung = formatWatt(surplus?.targetPower),
            zielstrom = formatAmpere(surplus?.targetCurrent),
            verteilungsmodus = verteilungsmodusText(decision?.allocationMode),
            verteilung = decision?.allocation?.limits.orEmpty().map { VerteilungView(it.chargerId, formatAmpere(it.current)) },
            phaseHoechsteSpannung = formatPhaseLabel(decision?.phaseWithHighestVoltage),
            begruendung = formatOrFallback(decision?.reason ?: skippedReason),
        )
    }

    private fun verteilungsmodusText(allocationMode: String?): String = when (allocationMode) {
        AllocationMode.PRIORITIZED.name -> TEXT_VERTEILUNG_PRIORISIERT
        AllocationMode.SHARED.name -> TEXT_VERTEILUNG_GETEILT
        SINGLE_CHARGER_MODE -> TEXT_VERTEILUNG_EIN_AUTO
        else -> FALLBACK_TEXT
    }

    private fun lastActionView(action: ControlAction?, now: Instant): LastActionView =
        LastActionView(
            zeit = formatClockWithAge(action?.at, now),
            charger = formatOrFallback(action?.chargerId),
            aktion = formatOrFallback(action?.kind),
            phasen = formatPhaseMode(action?.phaseMode),
            strom = formatAmpere(action?.current),
            ergebnis = formatOrFallback(action?.outcome),
        )

    private fun historyRowView(action: ControlAction, now: Instant): HistoryRowView =
        HistoryRowView(
            zeit = formatClockWithAge(action.at, now),
            charger = formatOrFallback(action.chargerId),
            aktion = formatOrFallback(action.kind),
            phasen = formatPhaseMode(action.phaseMode),
            strom = formatAmpere(action.current),
            ergebnis = formatOrFallback(action.outcome),
        )

    private fun chargerStatusLabel(status: ChargerStatus): String = when (status) {
        ChargerStatus.AVAILABLE -> "Bereit"
        ChargerStatus.PREPARING -> "Vorbereitung"
        ChargerStatus.CHARGING -> "Lädt"
        ChargerStatus.SUSPENDED_EV -> "Pausiert (Fahrzeug)"
        ChargerStatus.SUSPENDED_EVSE -> "Pausiert (Wallbox)"
        ChargerStatus.FINISHING -> "Beendet"
        ChargerStatus.RESERVED -> "Reserviert"
        ChargerStatus.UNAVAILABLE -> "Nicht verfügbar"
        ChargerStatus.FAULTED -> "Fehler"
    }
}
