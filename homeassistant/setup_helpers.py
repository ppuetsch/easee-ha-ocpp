"""Legt die Helfer an, die Dashboard und Heartbeat brauchen. Idempotent, Standard ist Dry-Run.

Aufruf:
    python setup_helpers.py            zeigt nur, was passieren wuerde
    python setup_helpers.py --apply    fuehrt die Aenderungen aus

Home Assistant vergibt die Entity-ID beim Anlegen aus dem Namen (Slug). Der Name
„EV Prio-Charger“ ergibt `input_select.ev_prio_charger`, die App erwartet aber
`input_select.ev_priority_charger`. Deshalb wird die Entity nach dem Anlegen in der
Entity-Registry umbenannt.
"""

from __future__ import annotations

import argparse
import io
import re
import sys
from dataclasses import dataclass
from typing import Any

from ha_websocket import (
    HOME_ASSISTANT_WEBSOCKET_URL,
    HaBefehlsKanal,
    Kommando,
    oeffne_verbindung,
)

type Eintrag = dict[str, Any]

PRIO_CHARGER_NAME = "EV Prio-Charger"
PRIO_CHARGER_ENTITY = "input_select.ev_priority_charger"
PRIO_CHARGER_OPTIONEN = ["Mülltonnen", "Eingangsseite"]
PRIO_CHARGER_ICON = "mdi:ev-station"

HEARTBEAT_NAME = "Easee Service Heartbeat"
HEARTBEAT_ENTITY = "input_datetime.easee_service_heartbeat"

DRY_RUN_HINWEIS = "Dry-Run: nichts geaendert. Mit --apply ausfuehren."


@dataclass(frozen=True)
class HelferSpezifikation:
    domain: str
    name: str
    entity_id: str
    zusatzfelder: dict[str, Any]

    @property
    def vorhergesagte_item_id(self) -> str:
        return re.sub(r"[^a-z0-9]+", "_", self.name.lower()).strip("_")

    def anlege_kommando(self) -> Kommando:
        return {"type": f"{self.domain}/create", "name": self.name} | self.zusatzfelder

    def list_kommando(self) -> Kommando:
        return {"type": f"{self.domain}/list"}


PRIO_CHARGER_SPEZIFIKATION = HelferSpezifikation(
    domain="input_select",
    name=PRIO_CHARGER_NAME,
    entity_id=PRIO_CHARGER_ENTITY,
    zusatzfelder={"options": PRIO_CHARGER_OPTIONEN, "icon": PRIO_CHARGER_ICON},
)

HEARTBEAT_SPEZIFIKATION = HelferSpezifikation(
    domain="input_datetime",
    name=HEARTBEAT_NAME,
    entity_id=HEARTBEAT_ENTITY,
    zusatzfelder={"has_date": True, "has_time": True},
)

SPEZIFIKATIONEN = [PRIO_CHARGER_SPEZIFIKATION, HEARTBEAT_SPEZIFIKATION]


@dataclass(frozen=True)
class Bestand:
    items_je_domain: dict[str, list[Eintrag]]
    registrierung: list[Eintrag]


@dataclass(frozen=True)
class Helferplan:
    spezifikation: HelferSpezifikation
    anlegen: bool
    item_id: str
    aktuelle_entity_id: str
    abweichungen: list[str]

    @property
    def umbenennen(self) -> bool:
        return self.aktuelle_entity_id != self.spezifikation.entity_id

    @property
    def ist_vollstaendig(self) -> bool:
        return not self.anlegen and not self.umbenennen

    def beschreibung(self) -> list[str]:
        zeilen: list[str] = []
        if self.anlegen:
            zeilen.append(f"[anlegen] {self.spezifikation.domain} „{self.spezifikation.name}“")
        if self.umbenennen:
            zeilen.append(f"[umbenennen] {self.aktuelle_entity_id} -> {self.spezifikation.entity_id}")
        zeilen.extend(f"[Abweichung, bleibt unveraendert] {eintrag}" for eintrag in self.abweichungen)
        if self.ist_vollstaendig:
            zeilen.append(f"[vorhanden] {self.spezifikation.entity_id}")
        return zeilen


def finde_item(items: list[Eintrag], name: str) -> Eintrag | None:
    for item in items:
        if item.get("name") == name:
            return item
    return None


def entity_id_in_registrierung(registrierung: list[Eintrag], domain: str, item_id: str) -> str | None:
    for eintrag in registrierung:
        if eintrag.get("platform") == domain and eintrag.get("unique_id") == item_id:
            entity_id: str = eintrag["entity_id"]
            return entity_id
    return None


def finde_abweichungen(item: Eintrag, zusatzfelder: dict[str, Any]) -> list[str]:
    return [
        f"{schluessel}: vorhanden {item.get(schluessel)!r}, gewuenscht {wert!r}"
        for schluessel, wert in zusatzfelder.items()
        if item.get(schluessel) != wert
    ]


def plane_helfer(spezifikation: HelferSpezifikation, bestand: Bestand) -> Helferplan:
    items = bestand.items_je_domain[spezifikation.domain]
    item = finde_item(items, spezifikation.name)
    if item is None:
        vorhergesagte_entity = f"{spezifikation.domain}.{spezifikation.vorhergesagte_item_id}"
        return Helferplan(
            spezifikation=spezifikation,
            anlegen=True,
            item_id=spezifikation.vorhergesagte_item_id,
            aktuelle_entity_id=vorhergesagte_entity,
            abweichungen=[],
        )
    item_id: str = item["id"]
    aktuelle_entity_id = entity_id_in_registrierung(bestand.registrierung, spezifikation.domain, item_id)
    return Helferplan(
        spezifikation=spezifikation,
        anlegen=False,
        item_id=item_id,
        aktuelle_entity_id=aktuelle_entity_id or f"{spezifikation.domain}.{item_id}",
        abweichungen=finde_abweichungen(item, spezifikation.zusatzfelder),
    )


def lese_bestand(kanal: HaBefehlsKanal) -> Bestand:
    items_je_domain = {
        spezifikation.domain: _ergebnisliste(kanal.sende(spezifikation.list_kommando()))
        for spezifikation in SPEZIFIKATIONEN
    }
    registrierung = _ergebnisliste(kanal.sende({"type": "config/entity_registry/list"}))
    return Bestand(items_je_domain=items_je_domain, registrierung=registrierung)


def wende_plan_an(kanal: HaBefehlsKanal, plan: Helferplan) -> None:
    if plan.anlegen:
        antwort = kanal.sende(plan.spezifikation.anlege_kommando())
        item_id: str = antwort["result"]["id"]
    else:
        item_id = plan.item_id
    benennung = _aktuelle_entity_id_nach_anlegen(kanal, plan.spezifikation.domain, item_id)
    if benennung != plan.spezifikation.entity_id:
        kanal.sende(
            {
                "type": "config/entity_registry/update",
                "entity_id": benennung,
                "new_entity_id": plan.spezifikation.entity_id,
            }
        )


def _aktuelle_entity_id_nach_anlegen(kanal: HaBefehlsKanal, domain: str, item_id: str) -> str:
    registrierung = _ergebnisliste(kanal.sende({"type": "config/entity_registry/list"}))
    entity_id = entity_id_in_registrierung(registrierung, domain, item_id)
    if entity_id is None:
        return f"{domain}.{item_id}"
    return entity_id


def _ergebnisliste(antwort: dict[str, Any]) -> list[Eintrag]:
    ergebnis = antwort["result"]
    if not isinstance(ergebnis, list):
        raise TypeError(f"Unerwartete Antwort von Home Assistant: {antwort}")
    return ergebnis


def fuehre_aus(kanal: HaBefehlsKanal, anwenden: bool) -> list[str]:
    bestand = lese_bestand(kanal)
    plaene = [plane_helfer(spezifikation, bestand) for spezifikation in SPEZIFIKATIONEN]
    zeilen = [zeile for plan in plaene for zeile in plan.beschreibung()]
    if not anwenden:
        return [*zeilen, DRY_RUN_HINWEIS]
    for plan in plaene:
        if not plan.ist_vollstaendig:
            wende_plan_an(kanal, plan)
    return [*zeilen, "Fertig."]


def _stelle_utf8_ausgabe_sicher() -> None:
    if isinstance(sys.stdout, io.TextIOWrapper):
        sys.stdout.reconfigure(encoding="utf-8")


def _lese_argumente(argumente: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Legt input_select und input_datetime fuer die Easee-Steuerung an.")
    parser.add_argument("--apply", action="store_true", help="Aenderungen tatsaechlich ausfuehren")
    parser.add_argument("--url", default=HOME_ASSISTANT_WEBSOCKET_URL, help="WebSocket-URL von Home Assistant")
    return parser.parse_args(argumente)


def main(argumente: list[str] | None = None) -> int:
    _stelle_utf8_ausgabe_sicher()
    args = _lese_argumente(argumente)
    with oeffne_verbindung(args.url) as kanal:
        zeilen = fuehre_aus(kanal, anwenden=args.apply)
    for zeile in zeilen:
        print(zeile)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
