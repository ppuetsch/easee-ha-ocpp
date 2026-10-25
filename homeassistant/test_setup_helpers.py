from __future__ import annotations

import unittest
from typing import Any

from setup_helpers import (
    HEARTBEAT_ENTITY,
    HEARTBEAT_SPEZIFIKATION,
    PRIO_CHARGER_ENTITY,
    PRIO_CHARGER_OPTIONEN,
    PRIO_CHARGER_SPEZIFIKATION,
    Bestand,
    fuehre_aus,
    plane_helfer,
)
from testhilfen import FakeKanal, erfolg

SCHREIBTYPEN = {"input_select/create", "input_datetime/create", "config/entity_registry/update"}


def prio_eintrag(item_id: str = "ev_prio_charger") -> dict[str, Any]:
    return {
        "id": item_id,
        "name": "EV Prio-Charger",
        "options": PRIO_CHARGER_OPTIONEN,
        "icon": "mdi:ev-station",
    }


def heartbeat_eintrag() -> dict[str, Any]:
    return {"id": "easee_service_heartbeat", "name": "Easee Service Heartbeat", "has_date": True, "has_time": True}


def registrierung_eintrag(platform: str, unique_id: str, entity_id: str) -> dict[str, Any]:
    return {"platform": platform, "unique_id": unique_id, "entity_id": entity_id}


def leerer_bestand() -> Bestand:
    return Bestand(items_je_domain={"input_select": [], "input_datetime": []}, registrierung=[])


def vollstaendiger_bestand() -> Bestand:
    return Bestand(
        items_je_domain={"input_select": [prio_eintrag()], "input_datetime": [heartbeat_eintrag()]},
        registrierung=[
            registrierung_eintrag("input_select", "ev_prio_charger", PRIO_CHARGER_ENTITY),
            registrierung_eintrag("input_datetime", "easee_service_heartbeat", HEARTBEAT_ENTITY),
        ],
    )


def kanal_fuer_leeren_bestand_mit_nachlauf() -> FakeKanal:
    return FakeKanal(
        {
            "input_select/list": [erfolg([])],
            "input_datetime/list": [erfolg([])],
            "config/entity_registry/list": [
                erfolg([]),
                erfolg([registrierung_eintrag("input_select", "ev_prio_charger", "input_select.ev_prio_charger")]),
                erfolg(
                    [
                        registrierung_eintrag("input_select", "ev_prio_charger", "input_select.ev_prio_charger"),
                        registrierung_eintrag(
                            "input_datetime", "easee_service_heartbeat", HEARTBEAT_ENTITY
                        ),
                    ]
                ),
            ],
            "input_select/create": [erfolg({"id": "ev_prio_charger", "name": "EV Prio-Charger"})],
            "input_datetime/create": [erfolg({"id": "easee_service_heartbeat", "name": "Easee Service Heartbeat"})],
            "config/entity_registry/update": [erfolg({})],
        }
    )


class PlanungTest(unittest.TestCase):
    def test_fehlender_prio_charger_wird_angelegt_und_umbenannt(self) -> None:
        plan = plane_helfer(PRIO_CHARGER_SPEZIFIKATION, leerer_bestand())

        self.assertTrue(plan.anlegen)
        self.assertTrue(plan.umbenennen)
        self.assertEqual(plan.aktuelle_entity_id, "input_select.ev_prio_charger")
        self.assertIn("[umbenennen] input_select.ev_prio_charger -> input_select.ev_priority_charger", plan.beschreibung())

    def test_fehlender_heartbeat_wird_angelegt_ohne_umbenennung(self) -> None:
        plan = plane_helfer(HEARTBEAT_SPEZIFIKATION, leerer_bestand())

        self.assertTrue(plan.anlegen)
        self.assertFalse(plan.umbenennen)

    def test_vollstaendig_vorhandene_helfer_sind_fertig(self) -> None:
        bestand = vollstaendiger_bestand()

        self.assertTrue(plane_helfer(PRIO_CHARGER_SPEZIFIKATION, bestand).ist_vollstaendig)
        self.assertTrue(plane_helfer(HEARTBEAT_SPEZIFIKATION, bestand).ist_vollstaendig)

    def test_abweichende_optionen_werden_gemeldet_aber_nicht_geaendert(self) -> None:
        bestand = vollstaendiger_bestand()
        bestand.items_je_domain["input_select"][0]["options"] = ["Mülltonnen"]

        plan = plane_helfer(PRIO_CHARGER_SPEZIFIKATION, bestand)

        self.assertTrue(plan.ist_vollstaendig)
        self.assertTrue(any(zeile.startswith("[Abweichung") for zeile in plan.beschreibung()))


class FuehreAusTest(unittest.TestCase):
    def test_dry_run_sendet_nur_leseKommandos(self) -> None:
        kanal = kanal_fuer_leeren_bestand_mit_nachlauf()

        zeilen = fuehre_aus(kanal, anwenden=False)

        self.assertFalse(set(kanal.gesendete_typen()) & SCHREIBTYPEN)
        self.assertIn("[anlegen] input_select „EV Prio-Charger“", zeilen)
        self.assertTrue(zeilen[-1].startswith("Dry-Run"))

    def test_apply_legt_an_und_benennt_prio_charger_um(self) -> None:
        kanal = kanal_fuer_leeren_bestand_mit_nachlauf()

        fuehre_aus(kanal, anwenden=True)

        self.assertEqual(
            kanal.gesendete_typen(),
            [
                "input_select/list",
                "input_datetime/list",
                "config/entity_registry/list",
                "input_select/create",
                "config/entity_registry/list",
                "config/entity_registry/update",
                "input_datetime/create",
                "config/entity_registry/list",
            ],
        )
        self.assertEqual(
            kanal.gesendet[5],
            {
                "type": "config/entity_registry/update",
                "entity_id": "input_select.ev_prio_charger",
                "new_entity_id": PRIO_CHARGER_ENTITY,
            },
        )

    def test_anlegen_sendet_optionen_und_icon(self) -> None:
        kanal = kanal_fuer_leeren_bestand_mit_nachlauf()

        fuehre_aus(kanal, anwenden=True)

        anlage = next(k for k in kanal.gesendet if k["type"] == "input_select/create")
        self.assertEqual(anlage["name"], "EV Prio-Charger")
        self.assertEqual(anlage["options"], ["Mülltonnen", "Eingangsseite"])
        self.assertEqual(anlage["icon"], "mdi:ev-station")

    def test_zweiter_lauf_ist_idempotent(self) -> None:
        kanal = FakeKanal(
            {
                "input_select/list": [erfolg([prio_eintrag()])],
                "input_datetime/list": [erfolg([heartbeat_eintrag()])],
                "config/entity_registry/list": [
                    erfolg(
                        [
                            registrierung_eintrag("input_select", "ev_prio_charger", PRIO_CHARGER_ENTITY),
                            registrierung_eintrag("input_datetime", "easee_service_heartbeat", HEARTBEAT_ENTITY),
                        ]
                    )
                ],
            }
        )

        zeilen: list[str] = fuehre_aus(kanal, anwenden=True)

        self.assertFalse(set(kanal.gesendete_typen()) & SCHREIBTYPEN)
        self.assertIn(f"[vorhanden] {PRIO_CHARGER_ENTITY}", zeilen)
        self.assertEqual(zeilen[-1], "Fertig.")


if __name__ == "__main__":
    unittest.main()
