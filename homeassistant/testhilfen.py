"""Gefaelschter Home-Assistant-Kanal fuer die Unit-Tests; spricht kein Netz an."""

from __future__ import annotations

from typing import Any

from ha_websocket import Antwort, Kommando


def erfolg(ergebnis: Any) -> Antwort:
    return {"success": True, "result": ergebnis}


class FakeKanal:
    """Beantwortet jeden Befehlstyp aus einer Warteschlange; die letzte Antwort bleibt stehen."""

    def __init__(self, antworten: dict[str, list[Antwort]]) -> None:
        self._antworten = {typ: list(liste) for typ, liste in antworten.items()}
        self.gesendet: list[Kommando] = []

    def sende(self, kommando: Kommando) -> Antwort:
        self.gesendet.append(kommando)
        warteschlange = self._antworten[kommando["type"]]
        if len(warteschlange) > 1:
            return warteschlange.pop(0)
        return warteschlange[0]

    def gesendete_typen(self) -> list[str]:
        return [kommando["type"] for kommando in self.gesendet]
