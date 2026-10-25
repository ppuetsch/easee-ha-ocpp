"""Ändert testweise HA-Entities und prüft, dass der Service die Wirkung zeigt; stellt die Originalwerte wieder her."""

import json
import os
import time
import urllib.request

HA = "http://homeassistant.fritz.box:8123/api"
SERVICE = "http://docker.fritz.box:8080/api/state"
TOKEN = os.environ["HA_TOKEN"]
WARTEZEIT_SEKUNDEN = 12


def ha(pfad: str, daten: dict | None = None) -> dict:
    anfrage = urllib.request.Request(
        f"{HA}/{pfad}",
        data=json.dumps(daten).encode() if daten is not None else None,
        headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
    )
    return json.load(urllib.request.urlopen(anfrage, timeout=10))


def zustand(entity: str) -> str:
    return str(ha(f"states/{entity}")["state"])


def setze(domain: str, service: str, entity: str, **felder: object) -> None:
    ha(f"services/{domain}/{service}", {"entity_id": entity, **felder})


def service_zustand() -> dict:
    return json.load(urllib.request.urlopen(SERVICE, timeout=10))


def beobachte(titel: str) -> None:
    time.sleep(WARTEZEIT_SEKUNDEN)
    d = service_zustand()
    print(f"{titel}\n   Eingänge: {d['inputs']}\n   {d['decision']['reason']}\n")


def main() -> None:
    puffer = "input_number.ev_surplus_charge_security_buffer"
    batterie = "input_boolean.car_preference_over_pv_battery"
    ueberschuss = "input_boolean.pv_surplus_charge"
    fest_strom = "input_number.target_ev_current"
    prio = "input_select.ev_priority_charger"
    original = {e: zustand(e) for e in (puffer, batterie, ueberschuss, fest_strom, prio)}
    print("Originalwerte:", original)
    try:
        beobachte("Basis")
        setze("input_number", "set_value", puffer, value=2000)
        beobachte("Puffer 2000 W")
        setze("input_boolean", "turn_off", batterie)
        beobachte("Batterie-Vorrang aus")
        setze("input_select", "select_option", prio, option="Eingangsseite")
        beobachte("Prio-Charger Eingangsseite")
        setze("input_boolean", "turn_off", ueberschuss)
        setze("input_number", "set_value", fest_strom, value=10)
        beobachte("Überschuss aus, Festmodus 10 A")
    finally:
        setze("input_number", "set_value", puffer, value=float(original[puffer]))
        setze("input_number", "set_value", fest_strom, value=float(original[fest_strom]))
        setze("input_select", "select_option", prio, option=original[prio])
        setze("input_boolean", "turn_on" if original[batterie] == "on" else "turn_off", batterie)
        setze("input_boolean", "turn_on" if original[ueberschuss] == "on" else "turn_off", ueberschuss)
        nachher = {e: zustand(e) for e in original}
        print("Wiederhergestellt:", nachher, "OK" if nachher == original else "ABWEICHUNG")


main()
