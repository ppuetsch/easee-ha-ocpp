from __future__ import annotations

import json
import tempfile
import unittest
from datetime import date
from pathlib import Path
from typing import Any

from setup_helpers import PRIO_CHARGER_ENTITY
from testhilfen import FakeKanal, erfolg
from update_dashboard import (
    ANKER_ENTITY,
    SERVICE_LIVE_ENTITY,
    ZU_ERGAENZENDE_ENTITIES,
    AnkerKarteFehlt,
    diff_als_text,
    entity_ids_der_karte,
    erweitere_konfiguration,
    finde_anker_karte,
    fuehre_aus,
    schreibe_backup,
)

HEUTE = date(2026, 10, 25)


def beispiel_dashboard() -> dict[str, Any]:
    return {
        "views": [
            {
                "title": "Laden",
                "cards": [
                    {"type": "gauge", "entity": "sensor.ladestand"},
                    {
                        "type": "entities",
                        "title": "PV-Ueberschuss",
                        "entities": [
                            "input_number.target_ev_current",
                            {"entity": ANKER_ENTITY},
                            {"entity": "input_number.minimum_ev_current"},
                        ],
                        "state_color": True,
                    },
                    {"type": "entities", "title": "Andere", "entities": ["sensor.andere"]},
                ],
            }
        ]
    }


def dashboard_mit_ergaenzung() -> dict[str, Any]:
    konfiguration = beispiel_dashboard()
    anker_karte = konfiguration["views"][0]["cards"][1]
    anker_karte["entities"].extend({"entity": entity_id} for entity_id in ZU_ERGAENZENDE_ENTITIES)
    return konfiguration


def dashboard_mit_zwei_entity_karten_ohne_anker_in_erster() -> dict[str, Any]:
    return {
        "views": [
            {
                "cards": [
                    {"type": "entities", "entities": ["sensor.erste"]},
                    {"type": "vertical-stack", "cards": [{"type": "entities", "entities": [ANKER_ENTITY]}]},
                ]
            }
        ]
    }


class UmbauTest(unittest.TestCase):
    def test_ergaenzt_beide_entities_am_ende_der_anker_karte(self) -> None:
        vorher = beispiel_dashboard()

        umbau = erweitere_konfiguration(vorher, ZU_ERGAENZENDE_ENTITIES)

        anker_karte = finde_anker_karte(umbau.konfiguration)
        self.assertEqual(umbau.ergaenzt, [PRIO_CHARGER_ENTITY, SERVICE_LIVE_ENTITY])
        self.assertEqual(
            entity_ids_der_karte(anker_karte),
            [
                "input_number.target_ev_current",
                ANKER_ENTITY,
                "input_number.minimum_ev_current",
                PRIO_CHARGER_ENTITY,
                SERVICE_LIVE_ENTITY,
            ],
        )

    def test_aendert_nur_die_anker_karte_und_das_original_nicht(self) -> None:
        vorher = beispiel_dashboard()
        vorher_kopie = json.dumps(vorher)

        umbau = erweitere_konfiguration(vorher, ZU_ERGAENZENDE_ENTITIES)

        self.assertEqual(json.dumps(vorher), vorher_kopie)
        self.assertEqual(umbau.konfiguration["views"][0]["cards"][0], vorher["views"][0]["cards"][0])
        self.assertEqual(umbau.konfiguration["views"][0]["cards"][2], vorher["views"][0]["cards"][2])
        self.assertEqual(umbau.konfiguration["views"][0]["cards"][1]["state_color"], True)

    def test_zweiter_umbau_ist_idempotent(self) -> None:
        umbau = erweitere_konfiguration(dashboard_mit_ergaenzung(), ZU_ERGAENZENDE_ENTITIES)

        self.assertEqual(umbau.ergaenzt, [])
        self.assertEqual(umbau.konfiguration, dashboard_mit_ergaenzung())

    def test_teilweise_vorhandene_entity_wird_nur_einmal_ergaenzt(self) -> None:
        konfiguration = beispiel_dashboard()
        konfiguration["views"][0]["cards"][1]["entities"].append(PRIO_CHARGER_ENTITY)

        umbau = erweitere_konfiguration(konfiguration, ZU_ERGAENZENDE_ENTITIES)

        self.assertEqual(umbau.ergaenzt, [SERVICE_LIVE_ENTITY])
        self.assertEqual(entity_ids_der_karte(finde_anker_karte(umbau.konfiguration)).count(PRIO_CHARGER_ENTITY), 1)

    def test_waehlt_die_erste_karte_mit_dem_anker_auch_in_verschachtelten_stacks(self) -> None:
        umbau = erweitere_konfiguration(
            dashboard_mit_zwei_entity_karten_ohne_anker_in_erster(), ZU_ERGAENZENDE_ENTITIES
        )

        erste_karte = umbau.konfiguration["views"][0]["cards"][0]
        self.assertNotIn(PRIO_CHARGER_ENTITY, entity_ids_der_karte(erste_karte))
        self.assertEqual(umbau.ergaenzt, [PRIO_CHARGER_ENTITY, SERVICE_LIVE_ENTITY])

    def test_fehlende_anker_karte_wirft_fehler(self) -> None:
        with self.assertRaises(AnkerKarteFehlt):
            erweitere_konfiguration({"views": [{"cards": []}]}, ZU_ERGAENZENDE_ENTITIES)

    def test_diff_zeigt_die_neuen_entities_als_hinzugefuegte_zeilen(self) -> None:
        vorher = beispiel_dashboard()
        nachher = erweitere_konfiguration(vorher, ZU_ERGAENZENDE_ENTITIES).konfiguration

        diff = diff_als_text(vorher, nachher)

        hinzugefuegt = [zeile for zeile in diff.splitlines() if zeile.startswith("+") and not zeile.startswith("+++")]
        self.assertTrue(any(PRIO_CHARGER_ENTITY in zeile for zeile in hinzugefuegt))
        self.assertTrue(any(SERVICE_LIVE_ENTITY in zeile for zeile in hinzugefuegt))


class LaufTest(unittest.TestCase):
    def test_dry_run_schreibt_weder_backup_noch_dashboard(self) -> None:
        kanal = FakeKanal({"lovelace/config": [erfolg(beispiel_dashboard())]})
        with tempfile.TemporaryDirectory() as verzeichnis:
            ausfuehrung = fuehre_aus(kanal, anwenden=False, datum=HEUTE, verzeichnis=Path(verzeichnis))

            self.assertEqual(list(Path(verzeichnis).iterdir()), [])
        self.assertEqual(kanal.gesendete_typen(), ["lovelace/config"])
        self.assertFalse(ausfuehrung.gespeichert)
        self.assertTrue(ausfuehrung.zeilen[-1].startswith("Dry-Run"))

    def test_apply_sichert_vor_dem_speichern_und_prueft_danach(self) -> None:
        kanal = FakeKanal(
            {
                "lovelace/config": [erfolg(beispiel_dashboard()), erfolg(dashboard_mit_ergaenzung())],
                "lovelace/config/save": [erfolg(None)],
            }
        )
        with tempfile.TemporaryDirectory() as verzeichnis:
            ausfuehrung = fuehre_aus(kanal, anwenden=True, datum=HEUTE, verzeichnis=Path(verzeichnis))

            backup = json.loads((Path(verzeichnis) / "ev-laden-2026-10-25.json").read_text(encoding="utf-8"))
        self.assertEqual(backup, beispiel_dashboard())
        self.assertEqual(kanal.gesendete_typen(), ["lovelace/config", "lovelace/config/save", "lovelace/config"])
        self.assertEqual(kanal.gesendet[1]["config"], dashboard_mit_ergaenzung())
        self.assertTrue(ausfuehrung.gespeichert)

    def test_bereits_vollstaendiges_dashboard_wird_nicht_angefasst(self) -> None:
        kanal = FakeKanal({"lovelace/config": [erfolg(dashboard_mit_ergaenzung())]})
        with tempfile.TemporaryDirectory() as verzeichnis:
            ausfuehrung = fuehre_aus(kanal, anwenden=True, datum=HEUTE, verzeichnis=Path(verzeichnis))

            self.assertEqual(list(Path(verzeichnis).iterdir()), [])
        self.assertEqual(kanal.gesendete_typen(), ["lovelace/config"])
        self.assertFalse(ausfuehrung.gespeichert)

    def test_fehlgeschlagene_pruefung_nach_dem_speichern_meldet_fehler(self) -> None:
        kanal = FakeKanal(
            {
                "lovelace/config": [erfolg(beispiel_dashboard()), erfolg(beispiel_dashboard())],
                "lovelace/config/save": [erfolg(None)],
            }
        )
        with tempfile.TemporaryDirectory() as verzeichnis:
            with self.assertRaises(RuntimeError):
                fuehre_aus(kanal, anwenden=True, datum=HEUTE, verzeichnis=Path(verzeichnis))


class BackupTest(unittest.TestCase):
    def test_gleichtaegige_backups_ueberschreiben_sich_nicht(self) -> None:
        with tempfile.TemporaryDirectory() as verzeichnis:
            ziel = Path(verzeichnis)
            erster = schreibe_backup({"a": 1}, HEUTE, ziel)
            zweiter = schreibe_backup({"a": 2}, HEUTE, ziel)

            self.assertEqual(erster.name, "ev-laden-2026-10-25.json")
            self.assertEqual(zweiter.name, "ev-laden-2026-10-25-2.json")
            self.assertEqual(json.loads(erster.read_text(encoding="utf-8")), {"a": 1})


if __name__ == "__main__":
    unittest.main()
