"""Schmale WebSocket-Schnittstelle zu Home Assistant.

Setup und Dashboard-Umbau kennen nur `HaBefehlsKanal`. So lassen sich beide Logiken
ohne Netz testen, indem im Test ein gefaelschter Kanal eingesetzt wird.
"""

from __future__ import annotations

import json
import os
from types import TracebackType
from typing import Any, Protocol, Self

import websocket

HOME_ASSISTANT_WEBSOCKET_URL = "ws://homeassistant.fritz.box:8123/api/websocket"
VERBINDUNGS_TIMEOUT_SEKUNDEN = 30
TOKEN_UMGEBUNGSVARIABLE = "HA_TOKEN"

type Kommando = dict[str, Any]
type Antwort = dict[str, Any]


class HaBefehlFehlgeschlagen(RuntimeError):
    """Home Assistant hat ein Kommando mit `success: false` beantwortet."""


class HaBefehlsKanal(Protocol):
    def sende(self, kommando: Kommando) -> Antwort: ...


def lese_token_aus_umgebung() -> str:
    token = os.environ.get(TOKEN_UMGEBUNGSVARIABLE, "")
    if token == "":
        raise RuntimeError(f"Umgebungsvariable {TOKEN_UMGEBUNGSVARIABLE} ist nicht gesetzt.")
    return token


class HaWebSocketVerbindung:
    """Authentifizierte Sitzung; alle Kommandos laufen nacheinander ueber dieselbe Leitung."""

    def __init__(self, url: str, token: str) -> None:
        self._url = url
        self._token = token
        self._leitung: websocket.WebSocket | None = None
        self._naechste_kommando_id = 1

    def __enter__(self) -> Self:
        self._leitung = websocket.create_connection(self._url, timeout=VERBINDUNGS_TIMEOUT_SEKUNDEN)
        self._authentifiziere(self._leitung)
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        if self._leitung is not None:
            self._leitung.close()
            self._leitung = None

    def sende(self, kommando: Kommando) -> Antwort:
        leitung = self._offene_leitung()
        kommando_id = self._naechste_kommando_id
        self._naechste_kommando_id += 1
        leitung.send(json.dumps({"id": kommando_id} | kommando))
        antwort = self._antwort_auf(leitung, kommando_id)
        if not antwort.get("success"):
            raise HaBefehlFehlgeschlagen(f"Home Assistant lehnte {kommando['type']} ab: {antwort.get('error')}")
        return antwort

    def _offene_leitung(self) -> websocket.WebSocket:
        if self._leitung is None:
            raise RuntimeError("Verbindung ist nicht geoeffnet; 'with' verwenden.")
        return self._leitung

    def _authentifiziere(self, leitung: websocket.WebSocket) -> None:
        json.loads(leitung.recv())
        leitung.send(json.dumps({"type": "auth", "access_token": self._token}))
        antwort = json.loads(leitung.recv())
        if antwort.get("type") != "auth_ok":
            raise PermissionError(f"Anmeldung bei Home Assistant abgelehnt (Typ: {antwort.get('type')}).")

    @staticmethod
    def _antwort_auf(leitung: websocket.WebSocket, kommando_id: int) -> Antwort:
        while True:
            antwort: Antwort = json.loads(leitung.recv())
            if antwort.get("id") == kommando_id:
                return antwort


def oeffne_verbindung(url: str = HOME_ASSISTANT_WEBSOCKET_URL) -> HaWebSocketVerbindung:
    return HaWebSocketVerbindung(url, lese_token_aus_umgebung())
