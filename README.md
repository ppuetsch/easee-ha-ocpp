# easee-ha-ocpp

Spring-Boot-Service (Kotlin) für PV-Überschussladen zweier Easee-Charger. Ersetzt `easee/easee.py`.
Home Assistant liefert die Werte per WebSocket. Die Charger hängen per lokalem OCPP an Home Assistant (Integration „Easee Local OCPP“, `ppuetsch/easee-ocpp`); der Service liest ihre Messwerte aus HA und setzt die Limits über den HA-Dienst `easee_ocpp.set_current_limit`.
Nur der Phasenmodus geht weiter über die Easee-Cloud-REST-API. Der frühere eigene OCPP-Server ist noch enthalten, aber aus (`easee.ocpp.enabled=false`, Rückweg siehe unten).

Details und Entscheidungen: [docs/PLAN.md](docs/PLAN.md).

## Betrieb
- GUI: `http://<host>:8080/` (aktueller Zustand, Berechnung, letzte Eingriffe), JSON: `/api/state`, Health: `/actuator/health`. Kein Login, nur LAN.
- Health: DOWN nur, wenn der Regelzyklus seit über 60 s nicht gelaufen ist (oder nach einer Anlaufzeit von 60 s noch keiner lief). Eine fehlende HA-Verbindung ist kein DOWN, sondern steht als Detail `haConnected`. Der Docker-HEALTHCHECK prüft `/actuator/health`.
- Charger über HA: Messwerte aus `binary_sensor.<prefix>_ocpp_verbunden`, `sensor.<prefix>_ocpp_status`, `_ocpp_leistung`, `_ocpp_strom_l1..3`, `_ocpp_spannung_l1..3` (`prefix` je Charger in `easee.chargers`, z. B. `mulltonnen`). Limit per `easee_ocpp.set_current_limit` (Seriennummer, Strom, Dauer), frische Messwerte per `easee_ocpp.trigger_message`.
- **Schattenmodus ist Standard** (`ACTUATION_ENABLED=false`): der Service rechnet und zeigt an, sendet aber nichts an Charger und Easee-Cloud. Erst mit `deploy/deploy.ps1 -Actuate` regelt er.
- Fail-safe: Das Limit läuft nach 2 min ab, danach gilt der OCPP-Maximalstrom der Integration. Der steht für beide Charger auf 0 A (`number.<prefix>_ocpp_maximalstrom`). Fällt der Service aus, hört die Ladung also auf. Details unten unter „Dienstausfall“.
- Secrets per Env-Var: `HA_TOKEN`, `EASEE_USERNAME`, `EASEE_PASSWORD`. Nur mit eigenem OCPP-Server (`deploy.ps1 -OcppServer`) zusätzlich `OCPP_BASIC_AUTH_PASSWORD`, `KEYSTORE_PASSWORD`, `EASEE_OCPP_ALLOWEDIDTAGS`.

## RFID-Freigabe
Die Freigabe entscheidet jetzt die Easee-Integration in HA: Whitelist unter Easee → Konfigurieren → RFID-Karten, neue Karten mit dem Button „OCPP nächste RFID-Karte anlernen“. Der folgende Abschnitt gilt nur noch für den eigenen OCPP-Server (`-OcppServer`).

- Geladen wird nur mit einer Karte, deren ID auf der Whitelist steht. `Authorize` und `StartTransaction` antworten für andere Karten mit `Invalid`. `StopTransaction` wird immer akzeptiert.
- Die Whitelist kommt aus `EASEE_OCPP_ALLOWEDIDTAGS` (kommagetrennt). Der Vergleich ignoriert Groß-/Kleinschreibung und Leerzeichen am Rand. Ist die Liste leer, werden alle Karten abgelehnt; der Service warnt beim Start.
- Die Liste liegt als User-Umgebungsvariable gleichen Namens auf dem Windows-PC. `deploy.ps1` bricht ab, wenn sie fehlt.
- Jede Entscheidung steht im Log: akzeptiert als INFO, abgelehnt als WARN mit ID und Charger.
- Neue Karte hinzufügen:
  1. Unbekannte Karte an den Charger halten.
  2. ID aus dem Log lesen: `docker logs easee-ha-ocpp | grep abgelehnt`
  3. Die ID in `EASEE_OCPP_ALLOWEDIDTAGS` ergänzen (kommagetrennt, bestehende Einträge behalten).
  4. Neu deployen: `deploy/deploy.ps1 -Local -PlainOcpp -Actuate` (Parameter wie beim laufenden Betrieb).
- `FreeCharging` wird nicht genutzt: Der Service setzt es nie am Charger, da Easee die Keys nicht kennt und die Freigabe über die Whitelist läuft.

## Dienstausfall
- Das Limit gilt 2 min (`easee.ha.charger-limit-validity`, Standard 120 s; die Integration legt es als Profil auf Stack-Ebene 1 ab). Der Service erneuert es alle 60 s (`easee.control.profile-refresh-interval`). Danach gilt der OCPP-Maximalstrom der Integration (Stack-Ebene 0, auf 0 A gestellt), und die Ladung stoppt.
- Fällt Home Assistant aus, gilt dasselbe: Die Charger behalten kein Limit über 2 min hinaus.
- Fehlt nur ein Eingang aus HA (`unavailable`/`unknown`), erneuert der Service die zuletzt gesetzten Limits unverändert noch bis zu 5 min nach dem letzten vollständigen Zyklus (`easee.control.missing-input-hold`). Ein kurzer Sensoraussetzer beendet so keine Ladung. Erst danach läuft das Limit ab. Die Statusseite zeigt dann „letzte Limits werden gehalten“.
- PV-, Netz- und Batterieleistung sind nur bei eingeschaltetem Überschussladen Pflichteingänge. Ist es aus, zählen fehlende Leistungswerte als 0 W; der Festwert wird normal gesetzt.
- Der Start prüft, dass die Erneuerung kürzer ist als die Gültigkeit. Andernfalls bricht der Service mit einer Fehlermeldung ab.
- **Einschränkung:** Dieses Verhalten gilt nur, solange der Charger die Profile behält. Nach einem Neustart des Chargers ist ungeprüft, ob er das Regelprofil noch hat oder wie er ohne Service reagiert.
- Wie im Altskript wird nie unter `minimum_ev_current` geregelt; die Pausen-Hysterese greift daher nur bei Mindeststrom 0.

## Container und Pfade
- Image: `/app/app.jar`, läuft als uid 10001. Keystore unter `/secrets/keystore.p12` (Mount `~/easee-secrets`, read-only), Regelhistorie unter `/data/control-history.jsonl` (Volume `easee-data`). Beides ist im Dockerfile per ENV gesetzt (`EASEE_OCPP_SSL_KEYSTOREPATH`, `EASEE_CONTROL_HISTORYFILE`).
- **Secrets sind in `docker inspect` sichtbar.** `deploy.ps1` übergibt sie per `--env-file`, Docker speichert sie danach als Container-Env. Wer Zugriff auf den Docker-Host bzw. die Docker-Gruppe hat, kann sie auslesen. Die Datei `~/easee-ha-ocpp.env` auf dem Host hat Rechte 600; der Host selbst muss entsprechend abgeschottet sein.

## Parallelbetrieb im Schattenmodus
- Der neue Service darf im Schattenmodus **parallel** zum alten Regler `pv-surcharge` laufen. Er sendet dann nichts an Charger oder Easee-Cloud und ist nur ein Beobachter: GUI (`/`) gegen die Entscheidungen von `pv-surcharge` vergleichen.
- `deploy.ps1` (ohne `-Actuate`) bricht deshalb wegen eines laufenden `pv-surcharge` nicht ab.
- `deploy.ps1 -Actuate` ohne `-StopLegacy` bricht ab, solange `pv-surcharge` läuft: zwei Regler dürfen nicht aktiv regeln.
- `deploy.ps1 -StopLegacy` ohne `-Actuate` stoppt `pv-surcharge`, ohne dass der neue Service regelt. Das Skript warnt und fragt nach; `-Force` überspringt die Abfrage. Dieser Zustand soll nur kurz im Umschaltfenster bestehen.

## Inbetriebnahme
1. Secrets als User-Umgebungsvariablen setzen (Vorlage: `deploy/env.example`), dann `deploy/generate-certs.ps1`. Fehlt `KEYSTORE_PASSWORD`, erzeugt das Skript ein Passwort und speichert es als User-Umgebungsvariable (der Wert wird nicht ausgegeben). Zertifikatslaufzeit beachten (Server 2 Jahre).
2. `python homeassistant/setup_helpers.py` (Dry-Run), dann `--apply`; `homeassistant/update_dashboard.py` analog; YAML aus `homeassistant/` einspielen (siehe dortige README).
3. Schattenmodus parallel zu `pv-surcharge`: `deploy/deploy.ps1 -DryRun`, dann `deploy/deploy.ps1`. Einige Zyklen lang vergleichen (GUI gegen `pv-surcharge`), `/actuator/health` muss UP sein.
4. Umschaltfenster: `deploy/deploy.ps1 -StopLegacy` stoppt `pv-surcharge` (Bestätigung nötig, siehe oben). Danach in der Easee-App Lastverteilung/Zeitpläne des Circuits abschalten und `deploy/activate-easee-ocpp.ps1 -Serial <Nr>` ausführen (setzt vorher den Circuit-`dynamicCurrent` zurück).
5. Regelung scharf schalten: `deploy/deploy.ps1 -Actuate`. Erst jetzt sendet der Service Befehle an den Charger.
6. Abnahme am echten Charger (Beta-Feature, Firmware ≥ 344): Verbindung, MeterValues, Wirkung von `SetChargingProfile` (ChargePointMaxProfile, 0 A), RFID-Freigabe mit bekannter und unbekannter Karte, Reconnect. Rückweg: `deploy/deactivate-easee-ocpp.ps1`.

## Entwicklung
`./gradlew test` (Unit, Spring-Integration, Playwright; Allure-Ergebnisse in `build/allure-results`), `./gradlew installPlaywrightBrowsers`.
CI (`.github/workflows/ci.yml`) lädt JUnit-XML, Allure-Report, JaCoCo und Playwright-Traces als Artefakte hoch und pusht auf `main` das Image nach `ghcr.io/ppuetsch/easee-ha-ocpp`.

## Stand der Inbetriebnahme (2026-10-25) und Abweichungen vom Plan
- **`ws://` statt `wss://`:** Die Easee-API lehnt eine private CA ab (`Invalid CaCertificate: self-signed certificate`). Für `wss` bräuchte es ein öffentlich vertrauenswürdiges Zertifikat (z. B. Let's Encrypt für eine eigene Domain). Aktuell läuft OCPP daher unverschlüsselt mit Basic-Auth (Security-Profil 1) im Heimnetz; das Passwort (max. 20 Zeichen, Easee-Vorgabe) geht im Klartext über das LAN. Der TLS-Connector bleibt im Code (`easee.ocpp.ssl.enabled=true`, `deploy/generate-certs.ps1`) für den Fall eines öffentlichen Zertifikats.
- **Messintervall:** Der Charger akzeptiert `MeterValueSampleInterval` frühestens bei 30 s. Die Ladeleistung ist daher bis zu ~30 s alt (`meterValueMaxAge` 90 s).
- **`FreeCharging`/`FreeChargingIdTag`** kennt Easee nicht (`ProtocolError`) und wird deshalb nicht mehr gesendet. Freigabe läuft ausschließlich über die RFID-Whitelist (siehe „RFID-Freigabe“).
- **Bekanntes Risiko:** Nach einem Neustart des Service blieb ein Charger ~14 min still (verbunden, aber ohne BootNotification), bis seine Konfiguration erneut angewendet wurde (`deploy/activate-easee-ocpp.ps1 -Serial <Nr> -SkipCircuitReset -Plain`). Danach Status in der GUI prüfen.
- Der alte Container `pv-surcharge` ist gestoppt, nicht gelöscht (`--restart=no`).

## Rückweg zum eigenen OCPP-Server
1. Charger in HA aus der Integration „Easee Local OCPP“ entfernen (die Integration stellt sie auf Cloud zurück) und mit `deploy/activate-easee-ocpp.ps1 -Serial <Nr> -SkipCircuitReset -Plain` auf den Service zeigen lassen.
2. `deploy/deploy.ps1 -Local -HostNetwork -OcppServer -PlainOcpp -Actuate`.

## Rollback auf den alten Regler
1. `deploy/deactivate-easee-ocpp.ps1 -Serial EHDFQLBX` und `-Serial EH2NSSVK` (zurück auf Cloud).
2. `ssh root@docker.fritz.box "docker rm -f easee-ha-ocpp; docker update --restart=always pv-surcharge; docker start pv-surcharge"`.

## Zugangsdaten rotieren
Die alten Zugangsdaten standen im Klartext in `easee/easee.py` und in dessen Git-Historie und wurden für den Service als User-Umgebungsvariablen übernommen. So rotierst du sie:
1. **Easee-Passwort** in der Easee-App/Webseite ändern, dann lokal: `[Environment]::SetEnvironmentVariable('EASEE_PASSWORD','<neu>','User')`.
2. **HA-Token:** In Home Assistant unter Profil → Sicherheit → Langlebige Zugangstoken ein neues erstellen, das alte löschen, dann `[Environment]::SetEnvironmentVariable('HA_TOKEN','<neu>','User')` (das Token aus `easee.py` ist damit ebenfalls ungültig).
3. **OCPP-Passwort** (max. 20 Zeichen) neu setzen: `[Environment]::SetEnvironmentVariable('OCPP_BASIC_AUTH_PASSWORD','<neu>','User')`, danach `deploy/activate-easee-ocpp.ps1` für beide Charger (`-SkipCircuitReset -Plain`) und `deploy/deploy.ps1 -Local -PlainOcpp -Actuate`.
4. Nach jeder Änderung neu deployen: `deploy/deploy.ps1 -Local -PlainOcpp -Actuate` (schreibt die env-Datei neu).

## Versionsnummer
Die Version ist der Short-SHA (7 Zeichen) des Commits, aus dem gebaut wurde; bei lokalen, uncommitteten Änderungen mit Suffix `-dirty`. Sie steht im Fuß der GUI und unter `GET /api/version` (`{"version":"abc1234"}`). Gradle (`springBoot.buildInfo`) bekommt sie per `-PgitSha`, Docker per `--build-arg GIT_SHA`; die CI übergibt `GITHUB_SHA` gekürzt. `deploy.ps1` prüft nach dem Start (bis 120 s), dass `/api/version` mit HTTP 200 die erwartete Version liefert und `/actuator/health` `UP` ist; sonst bricht es mit Fehler ab (`-ExpectedVersion` überschreibt die erwartete Version).

## Stromrampe
Der Strom je Charger steigt höchstens um 1 A alle 15 s (`easee.control.ramp-up-step`, `ramp-up-interval`), damit die Batterieregelung folgen kann. Absenkungen gelten sofort auf den Zielwert und gehen auch durch die Settle-Pause (10 s). Ein pausierter Charger startet direkt mit dem Mindeststrom (6 A) und steigt dann in Schritten. Nach einem Neustart des Service beginnt die Rampe beim gemessenen Strom. Die GUI zeigt in der Begründung `Rampe: <Charger> <gesetzt> A (Ziel <Ziel> A)`, solange das Ziel noch nicht erreicht ist. Gemessen (2026-10-27): Anstieg 6 → 19 A in 13 Schritten à ~15,5 s; Absenkung 20 → 6 A im selben Regelzyklus, Ladeleistung 4,0 → 1,1 kW nach ~10 s.

Meldet ein Charger `SuspendedEV` (Fahrzeug nimmt keinen Strom), bekommt er weiter Strom angeboten und fällt erst nach 10 min aus der Verteilung (`easee.control.ineligible-after`). Der Kia EV9 wacht nach einer langen 0-A-Pause erst nach einem Weckbefehl aus der Cloud wieder auf; mit der früheren Karenz von 2 min fiel der Charger beim nächtlichen Nachladen immer wieder heraus, bevor das Auto anfing (beobachtet 2026-11-16, 03:00–05:00).

## Betriebshinweis: Docker-Host im LXC (2026-10-27)
Ein automatisches `apt dist-upgrade` auf `docker.fritz.box` hob `containerd.io` auf 2.4.2 und `runc` auf 1.5.2 an. Danach starten Container im unprivilegierten LXC nicht mehr (`Exited (126)`, `open sysctl net.ipv4.ip_unprivileged_port_start: permission denied`). Der Service läuft deshalb mit `deploy.ps1 ... -HostNetwork` (Host-Netzwerk, kein Port-Mapping). Andere Container auf dem Host (z. B. `serverboerse`) sind davon ebenfalls betroffen; Abhilfe auf Host-Ebene: `containerd.io`/`runc` auf die vorherige Version festsetzen oder die LXC-Konfiguration anpassen.
