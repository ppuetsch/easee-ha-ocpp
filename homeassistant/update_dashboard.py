"""Ergaenzt die Entity-Karte im Dashboard `ev-laden` um Prio-Charger und Service-Status.

Vor jeder Aenderung wird die bestehende Konfiguration nach `backup/` gesichert.
Standard ist Dry-Run mit Diff; `--apply` speichert.

Aufruf:
    python update_dashboard.py            zeigt das Diff
    python update_dashboard.py --apply    sichert und speichert
"""

from __future__ import annotations

import argparse
import difflib
import io
import json
import sys
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any

from ha_websocket import HOME_ASSISTANT_WEBSOCKET_URL, HaBefehlsKanal, oeffne_verbindung
from setup_helpers import PRIO_CHARGER_ENTITY

type Karte = dict[str, Any]
type Konfiguration = dict[str, Any]
type Entity = str | dict[str, Any]

DASHBOARD_PFAD = "ev-laden"
ANKER_ENTITY = "input_boolean.pv_surplus_charge"
SERVICE_LIVE_ENTITY = "binary_sensor.easee_service_live"
ZU_ERGAENZENDE_ENTITIES = [PRIO_CHARGER_ENTITY, SERVICE_LIVE_ENTITY]
BACKUP_VERZEICHNIS = Path(__file__).resolve().parent / "backup"
BACKUP_PRAEFIX = "ev-laden"
DRY_RUN_HINWEIS = "Dry-Run: nichts gespeichert. Mit --apply ausfuehren."


class AnkerKarteFehlt(RuntimeError):
    """Im Dashboard gibt es keine Entity-Karte mit dem Anker-Eintrag."""


@dataclass(frozen=True)
class Umbau:
    konfiguration: Konfiguration
    ergaenzt: list[str]


@dataclass(frozen=True)
class Ausfuehrung:
    zeilen: list[str]
    gespeichert: bool


def entity_id_eines_eintrags(eintrag: Entity) -> str | None:
    if isinstance(eintrag, str):
        return eintrag
    entity_id = eintrag.get("entity")
    return entity_id if isinstance(entity_id, str) else None


def entity_ids_der_karte(karte: Karte) -> list[str]:
    eintraege: list[Entity] = karte.get("entities", [])
    return [entity_id for eintrag in eintraege if (entity_id := entity_id_eines_eintrags(eintrag)) is not None]


def ist_entity_karte(karte: Karte) -> bool:
    return karte.get("type", "entities") == "entities"


def _karten_in_container(container: Karte) -> Iterator[Karte]:
    for karte in container.get("cards", []):
        yield karte
        yield from _karten_in_container(karte)
    for sektion in container.get("sections", []):
        yield from _karten_in_container(sektion)


def alle_karten(konfiguration: Konfiguration) -> Iterator[Karte]:
    for ansicht in konfiguration.get("views", []):
        yield from _karten_in_container(ansicht)


def finde_anker_karte(konfiguration: Konfiguration) -> Karte:
    for karte in alle_karten(konfiguration):
        if ist_entity_karte(karte) and ANKER_ENTITY in entity_ids_der_karte(karte):
            return karte
    raise AnkerKarteFehlt(f"Keine Entity-Karte mit {ANKER_ENTITY} im Dashboard {DASHBOARD_PFAD}.")


def fehlende_entities(karte: Karte, gewuenschte: list[str]) -> list[str]:
    vorhanden = set(entity_ids_der_karte(karte))
    return [entity_id for entity_id in gewuenschte if entity_id not in vorhanden]


def erweitere_konfiguration(konfiguration: Konfiguration, gewuenschte: list[str]) -> Umbau:
    neue_konfiguration: Konfiguration = json.loads(json.dumps(konfiguration))
    anker_karte = finde_anker_karte(neue_konfiguration)
    ergaenzt = fehlende_entities(anker_karte, gewuenschte)
    anker_karte.setdefault("entities", []).extend({"entity": entity_id} for entity_id in ergaenzt)
    return Umbau(konfiguration=neue_konfiguration, ergaenzt=ergaenzt)


def diff_als_text(vorher: Konfiguration, nachher: Konfiguration) -> str:
    return "\n".join(
        difflib.unified_diff(
            _als_zeilen(vorher),
            _als_zeilen(nachher),
            fromfile="vorher",
            tofile="nachher",
            lineterm="",
        )
    )


def _als_zeilen(konfiguration: Konfiguration) -> list[str]:
    return json.dumps(konfiguration, indent=2, ensure_ascii=False).splitlines()


def schreibe_backup(konfiguration: Konfiguration, datum: date, verzeichnis: Path) -> Path:
    verzeichnis.mkdir(parents=True, exist_ok=True)
    ziel = _freier_backup_pfad(datum, verzeichnis)
    ziel.write_text(json.dumps(konfiguration, indent=2, ensure_ascii=False), encoding="utf-8")
    return ziel


def _freier_backup_pfad(datum: date, verzeichnis: Path) -> Path:
    basis = f"{BACKUP_PRAEFIX}-{datum.isoformat()}"
    ziel = verzeichnis / f"{basis}.json"
    laufnummer = 2
    while ziel.exists():
        ziel = verzeichnis / f"{basis}-{laufnummer}.json"
        laufnummer += 1
    return ziel


def lese_dashboard(kanal: HaBefehlsKanal) -> Konfiguration:
    antwort = kanal.sende({"type": "lovelace/config", "url_path": DASHBOARD_PFAD})
    konfiguration: Konfiguration = antwort["result"]
    return konfiguration


def speichere_dashboard(kanal: HaBefehlsKanal, konfiguration: Konfiguration) -> None:
    kanal.sende({"type": "lovelace/config/save", "url_path": DASHBOARD_PFAD, "config": konfiguration})


def fuehre_aus(kanal: HaBefehlsKanal, anwenden: bool, datum: date, verzeichnis: Path) -> Ausfuehrung:
    vorher = lese_dashboard(kanal)
    umbau = erweitere_konfiguration(vorher, ZU_ERGAENZENDE_ENTITIES)
    if not umbau.ergaenzt:
        return Ausfuehrung(zeilen=["Dashboard enthaelt bereits alle Entities. Keine Aenderung."], gespeichert=False)
    zeilen = [diff_als_text(vorher, umbau.konfiguration), f"Ergaenzt: {', '.join(umbau.ergaenzt)}"]
    if not anwenden:
        return Ausfuehrung(zeilen=[*zeilen, DRY_RUN_HINWEIS], gespeichert=False)
    backup = schreibe_backup(vorher, datum, verzeichnis)
    speichere_dashboard(kanal, umbau.konfiguration)
    _pruefe_gespeichert(kanal, umbau.ergaenzt)
    return Ausfuehrung(zeilen=[*zeilen, f"Backup: {backup}", "Gespeichert und geprueft."], gespeichert=True)


def _pruefe_gespeichert(kanal: HaBefehlsKanal, erwartet: list[str]) -> None:
    nachher = lese_dashboard(kanal)
    fehlend = fehlende_entities(finde_anker_karte(nachher), erwartet)
    if fehlend:
        raise RuntimeError(f"Nach dem Speichern fehlen im Dashboard: {fehlend}")


def _stelle_utf8_ausgabe_sicher() -> None:
    if isinstance(sys.stdout, io.TextIOWrapper):
        sys.stdout.reconfigure(encoding="utf-8")


def _lese_argumente(argumente: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Erweitert das Dashboard ev-laden um Prio-Charger und Service-Status.")
    parser.add_argument("--apply", action="store_true", help="Backup anlegen und Dashboard speichern")
    parser.add_argument("--url", default=HOME_ASSISTANT_WEBSOCKET_URL, help="WebSocket-URL von Home Assistant")
    return parser.parse_args(argumente)


def main(argumente: list[str] | None = None) -> int:
    _stelle_utf8_ausgabe_sicher()
    args = _lese_argumente(argumente)
    with oeffne_verbindung(args.url) as kanal:
        ausfuehrung = fuehre_aus(kanal, anwenden=args.apply, datum=date.today(), verzeichnis=BACKUP_VERZEICHNIS)
    for zeile in ausfuehrung.zeilen:
        print(zeile)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
