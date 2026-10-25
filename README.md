# easee-ha-ocpp

Spring-Boot-Service (Kotlin) für PV-Überschussladen zweier Easee-Charger. Ersetzt `easee/easee.py`.
Home Assistant liefert die Werte per WebSocket, die Charger werden per direktem OCPP 1.6J (wss, eigene CA) begrenzt;
nur der Phasenmodus geht weiter über die Easee-Cloud-REST-API.

Details und Entscheidungen: [docs/PLAN.md](docs/PLAN.md).

## Betrieb
- GUI: `http://<host>:8080/` (aktueller Zustand, Berechnung, letzte Eingriffe), JSON: `/api/state`, Health: `/actuator/health`. Kein Login, nur LAN.
- OCPP: `wss://<host>:8443/ocpp/<Seriennummer>`, Basic-Auth (Benutzer = Seriennummer).
- **Schattenmodus ist Standard** (`ACTUATION_ENABLED=false`): der Service rechnet und zeigt an, sendet aber nichts. Erst mit `deploy/deploy.ps1 -Actuate` regelt er.
- Secrets nur als Env-Vars: `HA_TOKEN`, `EASEE_USERNAME`, `EASEE_PASSWORD`, `OCPP_BASIC_AUTH_PASSWORD`, `KEYSTORE_PASSWORD`.
- Fail-safe: Das Regelprofil läuft nach 5 min ab, danach gilt ein 0-A-Basisprofil. Fällt der Service aus, hört die Ladung also auf.
- Wie im Altskript wird nie unter `minimum_ev_current` geregelt; die Pausen-Hysterese greift daher nur bei Mindeststrom 0.

## Inbetriebnahme
1. `deploy/generate-certs.ps1` (CA + Serverzertifikat nach `secrets/`), Zertifikatslaufzeit beachten (Server 2 Jahre).
2. `python homeassistant/setup_helpers.py` (Dry-Run), dann `--apply`; `homeassistant/update_dashboard.py` analog; YAML aus `homeassistant/` einspielen (siehe dortige README).
3. `deploy/deploy.ps1 -DryRun`, dann echt; der alte Container `pv-surcharge` muss mit `-StopLegacy` gestoppt werden (nie zwei Regler parallel).
4. In der Easee-App Lastverteilung/Zeitpläne des Circuits abschalten; `deploy/activate-easee-ocpp.ps1 -Serial <Nr>` (setzt vorher den Circuit-`dynamicCurrent` zurück).
5. Abnahme am echten Charger (Beta-Feature, Firmware ≥ 344): Verbindung, MeterValues, Wirkung von `SetChargingProfile` (ChargePointMaxProfile, 0 A), `FreeCharging`, Reconnect. Rückweg: `deploy/deactivate-easee-ocpp.ps1`.

## Entwicklung
`./gradlew test` (Unit, Spring-Integration, Playwright; Allure-Ergebnisse in `build/allure-results`), `./gradlew installPlaywrightBrowsers`.
CI (`.github/workflows/ci.yml`) lädt JUnit-XML, Allure-Report, JaCoCo und Playwright-Traces als Artefakte hoch und pusht auf `main` das Image nach `ghcr.io/ppuetsch/easee-ha-ocpp`.
