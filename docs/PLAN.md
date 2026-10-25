# Plan: easee-ha-ocpp (Kotlin / Spring Boot)

Ersetzt `easee/easee.py` (PV-Überschussladen für zwei Easee-Charger) durch einen Spring-Boot-Service.

## 1. Entscheidungen des Nutzers

| Thema | Entscheidung |
|---|---|
| Phasenumschaltung | Hybrid: Phasenmodus weiter per Easee-Cloud-REST (`POST /api/chargers/{id}/settings {"phaseMode":n}`), alles andere per OCPP |
| TLS | `wss://` mit eigener CA; TLS terminiert **im Spring-Service** (Keystore per Konfiguration). Skript erzeugt CA + Serverzertifikat |
| HA-Health | Service schreibt periodisch Zeitstempel per WS-`call_service` in ein `input_datetime`; Template-`binary_sensor` (connectivity) wird `off`, wenn zu alt |
| Verteilung auf 2 Charger | Neue HA-Entity (`input_select`) „Prio-Charger" (Mülltonnen / Eingangsseite), auch auf Dashboard `ev-laden`. Prio-Charger bekommt bis max **32 A bei 1 Phase / 16 A bei 3 Phasen**, Rest geht an Charger 2 |
| Stack | Gradle (Kotlin DSL), Kotlin, Spring Boot **4.1.2**, **JDK 25 Toolchain**, Thymeleaf, Playwright for Java, JUnit 5, Allure (allure-junit5 + Allure-CLI in CI) |
| Secrets | Nur Env-Vars / Docker-Secrets, nie im Repo. Deploy-Script liest lokale User-Env-Vars |

## 2. Fachlogik aus dem Python-Skript (muss erhalten bleiben)

Zyklus alle **5 s** (`refresh_interval=5`), Fehler in einem Zyklus werden geloggt, Schleife läuft weiter.

1. `input_boolean.pv_surplus_charge` == `on`? Nein → Nicht-Überschuss-Modus: `input_select.target_ev_phases` + `input_number.target_ev_current` direkt setzen.
2. Ja → Überschussmodus:
   - `min_required_pv_power = min_ev_power(6*240=1440 W) + security_buffer + min_house_power(100 W)`; `security_buffer = input_number.ev_surplus_charge_security_buffer`.
   - `pv_power = sensor.solaredge_modbus_ac_power`. Ist `pv_power < min_required_pv_power` → `target_power = 0`.
   - Sonst: `ev_power = Σ totalPower(Charger)` (W), `grid = sensor.ehz_zaehler_sml_power_curr`, `battery = sensor.venus_system_vebus_power`.
     - `input_boolean.car_preference_over_pv_battery == on`: `raw = max(ev - grid + battery, 0)`
     - sonst: `raw = max(ev - grid + min(battery, 0), 0)`
     - `target_power = raw - security_buffer`
   - `target_power > 0` → `current = round(target_power / (240 * phases))`; `>=32` → 32, `>=6` → Wert, sonst 0. `phases = input_select.target_ev_phases` (1|3). Sonst `current = 0`.
   - `current = max(current, input_number.minimum_ev_current)` (Quirk: mit Mindeststrom 6 wird nie unter 6 A geregelt; erhalten).
3. Stellen: nur wenn sich Phasenmodus oder Strom gegenüber dem zuletzt gesetzten Wert geändert haben (Start: `null` ⇒ erster Zyklus setzt immer). Phasenmodus geändert → Phasen setzen; Strom geändert → Strom setzen. Nach jeder Änderung **30 s** Wartezeit (`settings_change_sleep_time`), in der keine weitere Regelung läuft.
4. `phase_with_highest_voltage` (adjusted voltage = Spannung + Strom/2 je Phase, höchste gewinnt) bestimmt bei 1-phasig, auf welcher Phase der Strom gesetzt wird.

**Bekannte Altlasten, nicht übernehmen:** Klartext-Secrets (HA-Token, Easee-Passwort, JWT in easee.py, auch in Git-History → Rotation empfohlen), toter Code (`get_ehz_measurements` mit kaputter Variable `ehz_status_url`, `get_minimum_ev_current`), `lru_cache`-Zyklus-Trick (ersetzt durch Zyklus-Snapshot).

**Konsequenz durch OCPP:** OCPP-`SetChargingProfile` kennt nur ein Limit pro Charger, keine Phasenauswahl. `phase_with_highest_voltage` wird weiter berechnet und in der GUI angezeigt, aber nicht angewendet (Annahme; im Plan-Review bewerten).

Identifikatoren: Mülltonnen = `EHDFQLBX`, Eingangsseite = `EH2NSSVK` (als Konfiguration, ChargePointId der OCPP-Verbindung = Seriennummer).

## 3. Architektur

Ein Spring-Boot-Prozess, Pakete nach Verantwortung (Ports/Adapter, damit der Kern ohne Netz testbar ist):

```
de.puetsch.easeeocpp
├─ domain/        reine Kotlin-Logik, keine Spring-Abhängigkeit
│   ├─ model/     ChargeSettings, SurplusInputs, ChargerSnapshot, AllocationResult, ControlDecision
│   ├─ SurplusCalculator      Leistung → Zielstrom (Abschnitt 2)
│   ├─ PhaseVoltageEvaluator  phase_with_highest_voltage
│   └─ CurrentAllocator       Prio-Charger-Verteilung (Abschnitt 4)
├─ ports/         Interfaces: HomeAssistantStates, ChargerFleet, PhaseModeGateway, Clock
├─ ha/            HA-WebSocket-Client (JDK-HttpClient-WebSocket): auth, get_states, subscribe_events(state_changed),
│                 call_service, Reconnect mit Backoff, Health-Heartbeat
├─ ocpp/          OCPP-1.6J-Server (Spring WebSocket, Subprotokoll ocpp1.6, Basic-Auth-Handshake, wss)
│                 Messages: BootNotification, Heartbeat, StatusNotification, MeterValues, Start/StopTransaction,
│                 Authorize; ausgehend: SetChargingProfile, ChangeConfiguration (MeterValuesSampledData/Interval),
│                 TriggerMessage(MeterValues). Request/Response-Korrelation mit Timeout
├─ easee/         Cloud-REST-Client nur für Phasenmodus (Login, Token-Refresh)
├─ control/       ControlLoop (@Scheduled 5 s), Settle-Delay, StateStore (letztes Ergebnis, letzte Inputs, letzter Control-Input)
├─ web/           Thymeleaf-GUI + JSON-Endpoint /api/state, HTMX/Auto-Refresh
└─ config/        @ConfigurationProperties (alle Konstanten benannt), Secrets nur aus Env
```

OCPP bewusst **handgeschrieben** (Jackson, `[2,id,action,payload]`-Frames, typisierte Kotlin-Datenklassen für die ~10 Messages): kleine Protokolloberfläche, keine Abhängigkeit von unmaintainter Bibliothek, gut testbar.

## 4. Verteilung auf zwei Charger

Eingaben: `total_current` (aus Abschnitt 2, A pro Phase), `prio_charger` (HA `input_select.ev_priority_charger`), `phases`.
- `prio_cap = 32 A` bei 1 Phase, `16 A` bei 3 Phasen (benannte Konstanten).
- Eligible = Charger verbunden und Status ∉ {Available, Unavailable, Faulted} (Fahrzeug eingesteckt).
- Prio-Charger eligible: `prio = min(total_current, prio_cap)`; wird `prio < 6` → 0. `rest = total_current - prio`, nicht-Prio-Charger bekommt `rest` (gekappt auf dessen Cap, `<6` → 0).
- Prio-Charger nicht eligible: gesamter Strom an den anderen (gekappt auf Cap).
- Kein Charger eligible: Limits trotzdem setzen (Charger merkt sich Profil) auf `minimum_ev_current`-Logik wie bisher.
- Annahme: Cap 16/32 A gilt pro Charger, Gesamtstrom bleibt Ergebnis der Überschussrechnung.

## 5. Home-Assistant-Anbindung (WebSocket)

- URL `ws://homeassistant.fritz.box:8123/api/websocket`, Token aus Env `HA_TOKEN`.
- Start: `auth` → `get_states` (Snapshot) → `subscribe_events state_changed` (Cache laufend aktuell). Der Regelzyklus liest aus dem Cache; fehlt/`unavailable` ein Eingang → Zyklus überspringt, GUI zeigt den Grund.
- Reconnect mit exponentiellem Backoff, Ping/Pong.
- Heartbeat: alle 30 s `call_service input_datetime.set_datetime` auf `input_datetime.easee_service_heartbeat`.
- HA-Artefakte (Repo `homeassistant/`): Template-`binary_sensor.easee_service_live` (connectivity, `off` bei Heartbeat > 120 s), YAML-Block analog zu euren `*_live`-Sensoren; **idempotentes Setup-Tool** (`input_select/create` für Prio-Charger, `input_datetime/create` für Heartbeat per WS) und Dashboard-Update `ev-laden` (Entity-Karte um Prio-Charger + Health erweitern) per `lovelace/config/save`. Dashboard-Änderung nur nach Dry-Run-Diff und Backup der bestehenden Konfiguration.
- Neue Helper werden nicht automatisch beim Service-Start angelegt, sondern per Setup-Skript.

## 6. OCPP-Server

- Endpunkt `wss://<host>:8443/ocpp/{chargePointId}`, Subprotokoll `ocpp1.6`, HTTP-Basic-Auth (Passwort aus Env), nur konfigurierte ChargePointIds erlaubt.
- TLS: Spring `server.ssl` mit PKCS12-Keystore aus Pfad (Mount/Secret). GUI läuft auf separatem Port **8080** (HTTP, LAN) – zweiter Connector via `TomcatConnectorCustomizer`.
- Telemetrie: `MeterValuesSampledData=Power.Active.Import,Current.Import,Voltage,Current.Offered,Energy.Active.Import.Register`, `MeterValueSampleInterval` konfigurierbar (Default 5 s) per `ChangeConfiguration` nach Boot.
- Limit setzen: `SetChargingProfile` (Ampere, Zeitplan ohne Ende, Stack-Level konfigurierbar). Welche Profilart (`ChargePointMaxProfile` an Connector 0 vs. `TxDefaultProfile`) bei Easee wirkt, ist in der Doku nicht belegt → **konfigurierbar**, Default `TxDefaultProfile` + `ChargePointMaxProfile`; Verifikation am echten Charger (Risiko R1).
- Stromlimit 0 A = Pause.
- Charger-Messwerte → `ChargerSnapshot` (Leistung W, Strom L1–3, Spannung L1–3, Status, Alter der Daten). Veraltete Messwerte (> konfigurierbar) gelten als nicht verfügbar.

## 7. GUI (Thymeleaf)

Seite `/`: Auto-Refresh (HTMX oder Meta-Refresh 5 s), Abschnitte:
- Status der Verbindungen (HA verbunden, Charger 1/2 verbunden, Letzter Heartbeat).
- Relevante Eingänge (Werte + Alter): PV, Netz, Batterie, EV-Leistung, Puffer, Prio-Charger, Modus (Überschuss an/aus, Batterie-Präferenz, Mindeststrom, Zielphasen).
- Berechnungsergebnis: Rohleistung, Zielleistung, Zielstrom, Verteilung je Charger, Phase mit höchster Spannung, Begründung bei 0 A.
- Letzter Control-Input (Zeit, Charger, Phasen, Strom, Ergebnis der OCPP-/REST-Antwort) und Historie der letzten ~50 Eingriffe.
- `/api/state` liefert dasselbe als JSON (auch für Tests). `/actuator/health` für Docker-HEALTHCHECK.

## 8. Teststrategie (alle in CI)

| Ebene | Inhalt |
|---|---|
| Unit (JUnit 5, MockK) | SurplusCalculator (alle Zweige inkl. Grenzen 6/32 A, Batterie-Präferenz, Puffer, Mindeststrom), CurrentAllocator (Prio/Cap/Eligible), PhaseVoltageEvaluator, OCPP-Frame-Parser/Serializer, ControlLoop-Entscheidungslogik (Änderungserkennung, Settle-Delay mit fester `Clock`), Heartbeat-Alterung |
| Spring-Integration (`@SpringBootTest`, RANDOM_PORT) | Fake-HA-WebSocket-Server (in Test, echtes WS) + **Fake-Charger als OCPP-Client über wss** (Testzertifikate zur Laufzeit erzeugt): Boot → Konfiguration → MeterValues → Regelzyklus → `SetChargingProfile` kommt am Fake-Charger an; Basic-Auth-Ablehnung; unbekannte ChargePointId; HA-Reconnect; Heartbeat-`call_service` kommt an; Fake-Easee-REST (MockWebServer) für Phasen |
| Playwright (Java) | Startet App mit Fakes, prüft GUI: Werte sichtbar, Aktualisierung nach geänderter Fake-HA-Eingabe, Fehlerzustände (HA getrennt), letzter Control-Input erscheint; Screenshots/Trace als Allure-Attachments |
| Allure | `allure-junit5`, Schritte/Attachments, `allure generate` in CI; Test-Ergebnisse nach Ebene gelabelt (Epic/Feature) |

Coverage über JaCoCo, als Artefakt. Style: ktlint/detekt optional (Entscheidung im Review).

## 9. CI/CD (GitHub Actions)

- `ci.yml` auf Push/PR: JDK 25 setup, Gradle-Cache, `./gradlew check` (unit + integration), Playwright-Browser installieren, Playwright-Tests, Allure-CLI laden, `allure generate`, **Artefakte**: JUnit-XML, Allure-Results, Allure-HTML-Report, JaCoCo, Playwright-Traces/Screenshots.
- `docker`-Job (nur `main`, nach grünen Tests): Multi-Stage-Dockerfile (Gradle-Build → JRE 25 Runtime, non-root), Push nach `ghcr.io/ppuetsch/easee-ha-ocpp` (Tags `latest`, `sha`), `GITHUB_TOKEN` mit `packages: write`.

## 10. Lokales Deployment (Windows, PowerShell 5.1)

`deploy/deploy.ps1` analog zu `build-and-deploy.ps1` (docker host `docker.fritz.box` per SSH): Image pullen (oder lokal bauen mit `-Local`), `docker kill`/`docker container rm` des alten Containers `easee-ha-ocpp`, `docker run --restart always -d --name easee-ha-ocpp` mit Ports 8443 (OCPP/wss) und 8080 (GUI), Secrets via Env-Vars (`HA_TOKEN`, `EASEE_USERNAME`, `EASEE_PASSWORD`, `OCPP_BASIC_AUTH_PASSWORD`) aus User-Env des lokalen Rechners, Keystore als gemountetes Volume; danach Logs folgen. Alter Container `pv-surcharge` wird **nicht** automatisch gestoppt, sondern erst nach Bestätigung (Parameter `-StopLegacy`), da zwei Regler gleichzeitig kollidieren.
Zusätzliche Skripte: `deploy/generate-certs.ps1` (CA + Serverzertifikat via keytool/openssl, SAN `docker.fritz.box` + IP), `deploy/activate-easee-ocpp.ps1` (ruft `POST https://api.easee.com/local-ocpp/v1/connection-details/{serial}` und `.../connections/chargers/{serial}` mit CA-Zertifikat, `DualProtocol`).

## 11. Umsetzung und Qualitätssicherung

0. Dieser Plan → **Review durch Opus 5.5** (Subagent), Einarbeitung.
1. Ich lege selbst das Grundgerüst an (Gradle, Pakete, Ports/Interfaces, Domain-Typen), damit alle Komponenten feste Verträge haben.
2. **Haiku-5.5-Subagenten** je Komponente (jeweils mit eigener Brief + Testauftrag):
   A Domain/Berechnung + Unit-Tests · B HA-WS-Client + Fake-HA + Tests · C OCPP-Server + Fake-Charger + Tests · D Easee-Phasen-Client · E ControlLoop/StateStore/Config · F GUI + Playwright · G HA-Artefakte/Setup-Tool · H CI/Docker/Deploy/Cert-Skripte/Allure · I README.
   Unabhängige Komponenten laufen parallel.
3. Ich reviewe jedes Ergebnis (Diff lesen, Tests lokal laufen lassen); bei Mängeln gezielte Reparaturaufträge an Haiku.
4. **Gesamtreview durch Opus 5.5**, Findings → Reparatur (Haiku) → Re-Review der Findings.
5. Push, CI läuft, Artefakte und GHCR-Image prüfen.

Coding-Stil (Nutzer-CLAUDE.md, auf Kotlin übertragen): kleine Funktionen auf einer Abstraktionsebene, keine Abschnittskommentare, sprechende ausgeschriebene Namen, Typaliase für Domänenbegriffe (`typealias WattPower = Double` etc.), Prädikatsmethoden an Datenklassen, Konstanten benannt oben, Kommentare nur für das *Warum*. Bezeichner englisch (Protokollnähe), GUI-Texte deutsch.

## 12. Risiken / offene Punkte

- **R1** Easee-Native-OCPP ist Beta; Wirksamkeit von Profilart (`TxDefault` vs `ChargePointMax`), 0-A-Verhalten und Messwert-Intervalle nur am echten Charger prüfbar. → Konfigurierbar, Abnahme-Checkliste in README.
- **R2** Cloud-REST für Phasen nach Aktivierung von `DualProtocol` unbestätigt. → Fallback: Phasenmodus kommt rein aus HA/Config, Warnung in GUI.
- **R3** 1-phasig: Phasenauswahl (`phase_with_highest_voltage`) per OCPP nicht abbildbar.
- **R4** Spring Boot 4.1.2 / Jackson 3 / Spring Framework 7 / Gradle-Toolchain 25: Artefaktnamen und APIs vor Implementierung gegen Maven Central und Doku verifizieren (Modularisierung der Starter, `tools.jackson`-Pakete, Playwright/Allure-Kompatibilität mit JDK 25).
- **R5** Zertifikats-Vorgaben des Chargers (TLS-Version, SAN, Laufzeit) undokumentiert.
- **R6** Zwei Regler (alter Container + neuer) dürfen nie parallel laufen.
- **R7** Secrets in alter Git-History von `easee/` → Rotation (HA-Token, Easee-Passwort) empfohlen, nicht Teil dieses Repos.

## 13. Nachträge aus dem Opus-Review (verbindlich, überschreiben frühere Abschnitte)

**Blocker-Entscheidungen**
- **B1 Circuit-Semantik:** Das Altskript setzte `dynamicCurrent` des ganzen Circuits. Neu: Limits pro Charger per OCPP. `activate-easee-ocpp.ps1` setzt zuerst `dynamicCurrent` einmalig auf das Circuit-Maximum zurück und liest es zurück; README-Checkliste: Easee-Lastverteilung/Zeitpläne in der App abschalten. Konstante `CIRCUIT_MAX_CURRENT_PER_PHASE`; Allocator stellt sicher, dass die Summe der Limits sie pro Phase nie überschreitet (bei 1 Phase: Summe der beiden Charger, da Phasenwahl entfällt).
- **B2 Autorisierung:** Server beantwortet `Authorize` und `StartTransaction` mit `Accepted` (eigene `transactionId`), `StopTransaction` ebenso. Nach Boot per `GetConfiguration`/`ChangeConfiguration` `FreeCharging=true` und `FreeChargingIdTag` setzen (Tag wird vom Server immer akzeptiert). Im Fake-Charger-Test abgedeckt.
- **B3 Ausfallsicherheit:** Basisprofil `stackLevel 0` (ChargePointMaxProfile, Limit = Mindeststrom bzw. 0 A, dauerhaft) plus Regelprofil `stackLevel 1` mit `duration` = 300 s (konfigurierbar), das jeder Zyklus (spätestens alle 60 s, auch ohne Änderung) erneuert wird. Feste `chargingProfileId` je Stack-Ebene (ersetzen statt anhäufen). Nach `BootNotification`/Reconnect sofort neu senden.
- **B4 Nicht-Überschuss & Mindeststrom:** `target_ev_current` ist wie bisher der **Gesamtstrom** und läuft durch den Allocator. `minimum_ev_current` wird wie im Altskript auf den Gesamtstrom angewendet (`max(total, minimum)`), bevor verteilt wird.

**Fachlogik**
- Rundung wie Python (`Math.rint`, Banker's rounding), per Test festgeschrieben; HA-Strings (`"16.0"`, `"3"`) explizit parsen; Phasenwert ∉ {1,3} → Fehler wie im Skript.
- Easee-Token-Ablauf korrekt in UTC (Altskript-Bug nicht übernehmen).
- Golden-Tests: tabellengetriebene Fälle aus dem Python-Verhalten (Python-Referenz-Harness in `src/test/resources/golden/`), gegen `SurplusCalculator` laufen lassen.
- `numberPhases` im `ChargingSchedulePeriod` als Feature-Flag `ocpp.use-number-phases` (Default aus) zum Test am echten Charger.
- Eligible-Regel verfeinert: Charger zählt als nicht eligible, wenn Status `SuspendedEV`/`Finishing` länger als `INELIGIBLE_AFTER` (Default 120 s) oder `Available`/`Unavailable`/`Faulted`. Hysterese: Pause (0 A) erst nach `PAUSE_DELAY` (Default 60 s) unter 6 A; Mindestdauer nach Prio-Wechsel. Rest < 6 A: Option `ALLOW_REBALANCE_FOR_MIN_CURRENT` (Default aus; dokumentiert).
- Settle-Delay (30 s) **global**: währenddessen kein neuer Eingriff, Zyklus rechnet und zeigt weiter an.
- Ladeleistung ohne MeterValues (keine Transaktion) = 0 W, nicht „nicht verfügbar"; `unit` (W/kW) beachten.

**OCPP**
- Nur `ChargePointMaxProfile` (Connector 0) als Default; `TxDefaultProfile` optional. `HeartbeatInterval` per BootNotification-Antwort; `MeterValueSampleInterval` per `ChangeConfiguration` (Easee-Default 0!). Zeitstempel nur UTC mit `Z`. Pro Verbindung höchstens ein offener CALL (OCPP-Regel), CALLERROR-Behandlung, Timeouts.
- Handshake: Subprotokoll `ocpp1.6`, bei falschem Basic-Auth HTTP 401.
- Pfad-Trennung per Filter: OCPP nur auf 8443, GUI/Actuator nur auf 8080; Actuator nur `health`.

**Home Assistant**
- `get_states` fragmentiert (`last=false`) → Teile zusammensetzen, getestet. Alter von HA-Werten **nicht** als veraltet werten (unveränderte Werte erzeugen kein Event); nur `unavailable`/`unknown` verwerfen; `last_reported` nur zur Anzeige.
- Heartbeat-Entity vom Recorder ausschließen (YAML-Hinweis in `homeassistant/`).

**Deploy/Secrets**
- Secrets-Datei (`--env-file`, per scp, Rechte 600) statt `-e` (Prozessliste/`docker inspect`). Secrets: `HA_TOKEN`, `EASEE_USERNAME`, `EASEE_PASSWORD`, `OCPP_BASIC_AUTH_PASSWORD`, `KEYSTORE_PASSWORD` (16–40 Zeichen empfohlen).
- `deploy.ps1` startet nicht, solange `pv-surcharge` läuft, außer mit `-StopLegacy`.
- **Schattenmodus** `actuation.enabled=false`: rechnet und zeigt, schreibt weder OCPP noch REST; Default beim ersten Deploy.
- Zusatzskripte: `deactivate-easee-ocpp.ps1` (`OcppOff`), Zertifikatslaufzeit/Erneuerung im README.
- GUI ohne Auth, nur LAN (im README festgehalten).

**Umsetzung**
- `@Scheduled` nur dünner Trigger; Zyklus über injizierte `Clock` + manuellen Trigger testbar.
- **OCPP-Server (Komponente C) wird von Opus-nahem Review begleitet und von Haiku nur mit enger Spezifikation gebaut**; Frame-Format, CALLERROR, Timeouts, Ein-offener-CALL stehen im Brief.
- Verlauf der Eingriffe wird in einer Datei (JSON-Lines in `/data`) persistiert.
- Optional später: Mittelwertbildung der Eingänge; Metriken/Micrometer.
