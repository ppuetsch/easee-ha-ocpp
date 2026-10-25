# Home-Assistant-Artefakte für easee-ha-ocpp

Was der Easee-Service in Home Assistant braucht: zwei Helfer, einen Lebenszeichen-Sensor,
einen Recorder-Ausschluss und die Dashboard-Ergänzung `ev-laden`.
Stand: 2026-10-25. Die Werte sind gegen die laufende Instanz geprüft (Dry-Run), nicht aus Erinnerung.

## Dateien

| Datei | Zweck |
|---|---|
| `easee_service_live.yaml` | Snippet für den `template:`-Block: `binary_sensor.easee_service_live` (connectivity). `on`, solange der Heartbeat jünger als 120 s ist |
| `recorder_exclude.yaml` | Snippet: Heartbeat-Entity vom Recorder ausschließen |
| `setup_helpers.py` | Legt `input_select.ev_priority_charger` und `input_datetime.easee_service_heartbeat` per WebSocket an. Idempotent |
| `update_dashboard.py` | Ergänzt die Entity-Karte im Dashboard `ev-laden` um Prio-Charger und Service-Status. Sichert vorher nach `backup/` |
| `ha_websocket.py` | Schmale WebSocket-Schnittstelle, die Setup und Dashboard nutzen; im Test gefälscht |
| `backup/` | Ziel der Dashboard-Sicherungen (siehe unten) |

Alle Python-Skripte laufen mit Python 3.14 und `websocket-client`. Der Token kommt aus der
Umgebungsvariable `HA_TOKEN` und wird nie ausgegeben.

## Einspielen

Reihenfolge einhalten. Vor jedem `--apply` die Config sichern.

1. **Helfer anlegen (Dry-Run, dann ausführen)**

   ```powershell
   cd homeassistant
   python setup_helpers.py            # zeigt nur, was passieren würde
   python setup_helpers.py --apply    # legt an und benennt um
   ```

   Home Assistant vergibt die Entity-ID aus dem Namen. Aus „EV Prio-Charger“ würde
   `input_select.ev_prio_charger`, die App erwartet aber `input_select.ev_priority_charger`.
   Das Skript benennt die Entity deshalb nach dem Anlegen in der Entity-Registry um.
   Ein zweiter Lauf ändert nichts. Weicht eine bestehende Optionsliste ab, meldet das Skript
   die Abweichung und lässt sie unverändert.

2. **Template-Sensor**

   Vorher auf dem Host sichern (Port 22, Terminal-Add-on):

   ```bash
   ssh root@homeassistant.fritz.box -p 22 "cp /config/configuration.yaml /config/configuration.yaml.vor_easee_service_live"
   ```

   Dann den Block aus `easee_service_live.yaml` als Listenelement unter den bestehenden
   `template:`-Schlüssel in `configuration.yaml` setzen. Ist kein `template:` vorhanden,
   den Schlüssel mit dem Block neu anlegen.

3. **Recorder**

   `recorder_exclude.yaml` in die bestehende `recorder:`-Konfiguration übernehmen, nicht als
   zweiten Block. Die Recorder-Änderung wirkt erst nach einem Neustart des Core.

4. **Prüfen und laden**

   ```bash
   ssh root@homeassistant.fritz.box -p 22222 "ha core check"
   curl -s -X POST -H "Authorization: Bearer $HA_TOKEN" -d '{}' \
     http://homeassistant.fritz.box:8123/api/services/template/reload
   ```

   Der Recorder-Ausschluss braucht danach `ha core restart`. Den Recorder-Block ggf. analog
   in der InfluxDB-Konfiguration ausschließen, sonst landet der Heartbeat dort ungefiltert.

5. **Dashboard ev-laden (Dry-Run, dann ausführen)**

   Erst Schritt 1, sonst zeigt das Dashboard die beiden Entities als „nicht gefunden“.

   ```powershell
   python update_dashboard.py          # zeigt das Diff
   python update_dashboard.py --apply  # sichert nach backup/ und speichert
   ```

   Ergänzt werden nur fehlende Entities, am Ende der ersten Entity-Karte mit
   `input_boolean.pv_surplus_charge`. Andere Karten und Reihenfolgen bleiben unverändert.
   Nach dem Speichern liest das Skript das Dashboard zurück und prüft die Entities.

## Backup-Verzeichnis

`backup/` nimmt die Dashboard-Sicherungen `ev-laden-<datum>.json` auf. Gleichtägige Läufe
bekommen `-2`, `-3` usw. Die `.gitignore` des Repos ist nicht angepasst. Die Sicherungen
enthalten die Dashboard-Konfiguration und gehören nicht ins Repo. Vor jedem Commit mit
`git status` prüfen; nur `.gitkeep` soll versioniert werden.

## Tests und Typprüfung

Aus dem Repo-Root:

```powershell
python -m unittest discover homeassistant
python -m mypy --ignore-missing-imports homeassistant
```

Die Tests laufen ohne Netz. Die WebSocket-Schnittstelle wird über `testhilfen.FakeKanal`
gefälscht.
