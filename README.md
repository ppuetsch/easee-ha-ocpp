# easee-ha-ocpp

Spring-Boot-Service (Kotlin) für PV-Überschussladen zweier Easee-Charger. Ersetzt `easee/easee.py`.
Home Assistant liefert die Werte per WebSocket, die Charger werden per direktem OCPP 1.6J (wss, eigene CA) begrenzt;
nur der Phasenmodus geht weiter über die Easee-Cloud-REST-API.

Details und Entscheidungen: [docs/PLAN.md](docs/PLAN.md).

## Betrieb
- GUI: `http://<host>:8080/` (aktueller Zustand, Berechnung, letzte Eingriffe), JSON: `/api/state`, Health: `/actuator/health`. Kein Login, nur LAN.
- Health: DOWN nur, wenn der Regelzyklus seit über 60 s nicht gelaufen ist (oder nach einer Anlaufzeit von 60 s noch keiner lief). Eine fehlende HA-Verbindung ist kein DOWN, sondern steht als Detail `haConnected`. Der Docker-HEALTHCHECK prüft `/actuator/health`.
- OCPP: `wss://<host>:8443/ocpp/<Seriennummer>`, Basic-Auth (Benutzer = Seriennummer).
- **Schattenmodus ist Standard** (`ACTUATION_ENABLED=false`): der Service rechnet und zeigt an, sendet aber nichts an Charger und Easee-Cloud. Erst mit `deploy/deploy.ps1 -Actuate` regelt er.
- Secrets nur als Env-Vars: `HA_TOKEN`, `EASEE_USERNAME`, `EASEE_PASSWORD`, `OCPP_BASIC_AUTH_PASSWORD`, `KEYSTORE_PASSWORD`.
- Fail-safe: Das Regelprofil läuft nach 5 min ab, danach gilt ein 0-A-Basisprofil. Fällt der Service aus, hört die Ladung also auf.
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
6. Abnahme am echten Charger (Beta-Feature, Firmware ≥ 344): Verbindung, MeterValues, Wirkung von `SetChargingProfile` (ChargePointMaxProfile, 0 A), `FreeCharging`, Reconnect. Rückweg: `deploy/deactivate-easee-ocpp.ps1`.

## Entwicklung
`./gradlew test` (Unit, Spring-Integration, Playwright; Allure-Ergebnisse in `build/allure-results`), `./gradlew installPlaywrightBrowsers`.
CI (`.github/workflows/ci.yml`) lädt JUnit-XML, Allure-Report, JaCoCo und Playwright-Traces als Artefakte hoch und pusht auf `main` das Image nach `ghcr.io/ppuetsch/easee-ha-ocpp`.
