"""Zeichnet Netz-/Batterie-/PV-Leistung sekundengenau auf, um die Reaktionszeit nach einer Limit-Änderung zu messen."""

import csv
import json
import os
import sys
import time
import urllib.request

HA = "http://homeassistant.fritz.box:8123/api/states/"
SERVICE = "http://docker.fritz.box:8080/api/state"
SENSOREN = {
    "netz": "sensor.ehz_zaehler_sml_power_curr",
    "batterie": "sensor.venus_system_vebus_power",
    "pv": "sensor.solaredge_modbus_ac_power",
}
DAUER_SEKUNDEN = int(sys.argv[2]) if len(sys.argv) > 2 else 420


def lies(url: str, token: str | None = None) -> dict:
    anfrage = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"} if token else {})
    return json.load(urllib.request.urlopen(anfrage, timeout=5))


def main() -> None:
    token = os.environ["HA_TOKEN"]
    with open(sys.argv[1], "w", newline="", encoding="utf-8") as datei:
        schreiber = csv.writer(datei)
        schreiber.writerow(["t", *SENSOREN, *(f"{name}_geaendert" for name in SENSOREN), "ev_w_ocpp", "limit_a"])
        ende = time.time() + DAUER_SEKUNDEN
        while time.time() < ende:
            start = time.time()
            werte, geaendert = [], []
            for entity in SENSOREN.values():
                zustand = lies(HA + entity, token)
                werte.append(zustand["state"])
                geaendert.append(zustand["last_reported"][11:23])
            dienst = lies(SERVICE)
            lader = next((c for c in dienst["chargers"] if c["status"] == "CHARGING"), None)
            limits = [a for a in dienst["history"] if a["kind"] != "PHASE_MODE"][-1:]
            schreiber.writerow([f"{start:.3f}", *werte, *geaendert, lader["power"] if lader else "", limits[0]["current"] if limits else ""])
            datei.flush()
            time.sleep(max(0.0, 1.0 - (time.time() - start)))


main()
