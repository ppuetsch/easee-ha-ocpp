package de.puetsch.easeeocpp.web

data class StateView(
    val schattenmodus: Boolean,
    val auswertungZeit: String,
    val uebersprungenGrund: String,
    val verbindungen: ConnectionsView,
    val eingaenge: InputsView,
    val ergebnis: ResultView,
    val letzterEingriff: LastActionView,
    val historie: List<HistoryRowView>,
    val settleRestzeit: String,
)

data class ConnectionsView(
    val haStatus: String,
    val haStatusKlasse: String,
    val heartbeat: String,
    val chargers: List<ChargerView>,
)

data class ChargerView(
    val id: String,
    val verbindung: String,
    val verbindungKlasse: String,
    val status: String,
    val leistung: String,
    val letzterMesswert: String,
)

data class InputsView(
    val pvLeistung: String,
    val netzLeistung: String,
    val batterieLeistung: String,
    val evLeistung: String,
    val puffer: String,
    val mindeststrom: String,
    val zielphasen: String,
    val istPhasen: String,
    val ueberschuss: String,
    val batteriePraeferenz: String,
    val prioCharger: String,
)

data class ResultView(
    val mindestPvLeistung: String,
    val rohleistung: String,
    val zielleistung: String,
    val zielstrom: String,
    val verteilung: List<VerteilungView>,
    val phaseHoechsteSpannung: String,
    val begruendung: String,
)

data class VerteilungView(val chargerId: String, val strom: String)

data class LastActionView(
    val zeit: String,
    val charger: String,
    val aktion: String,
    val phasen: String,
    val strom: String,
    val ergebnis: String,
)

data class HistoryRowView(
    val zeit: String,
    val charger: String,
    val aktion: String,
    val phasen: String,
    val strom: String,
    val ergebnis: String,
)
