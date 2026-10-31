"""Client for the JARVIS cloud backend.

Covers the parts the desktop app needs: pairing, the presence/task
WebSocket, task acknowledgement, and the AI and voice relays.

Everything is asyncio-based. Qt integration lives in the ui layer; nothing
here imports Qt, so this module is testable on its own.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from dataclasses import dataclass
from typing import AsyncIterator, Awaitable, Callable

import httpx
import websockets

from .config import redact

log = logging.getLogger(__name__)

#: Tools this device advertises to the hub.
WINDOWS_CAPABILITIES = [
    "open_application",
    "open_url",
    "search_web",
    "control_windows_application",
    "read_selected_file",
    "analyze_screen",
    "type_text",
    "control_volume",
]


class BackendError(RuntimeError):
    """A structured error returned by the backend."""

    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.status = status
        self.code = code


@dataclass(frozen=True)
class Task:
    id: str
    origin: str
    kind: str
    payload: dict
    status: str

    @property
    def instruction(self) -> str | None:
        value = self.payload.get("instruction")
        return value if isinstance(value, str) else None


def _raise_for_error(response: httpx.Response) -> None:
    if response.is_success:
        return
    code, message = "http_error", f"HTTP {response.status_code}"
    with contextlib.suppress(Exception):
        body = response.json()
        error = body.get("error", {})
        code = error.get("code", code)
        message = error.get("message", message)
    raise BackendError(response.status_code, code, message)


class JarvisClient:
    """Talks to one hub as one device."""

    def __init__(self, base_url: str, token: str | None = None, timeout: float = 20.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self._http = httpx.AsyncClient(timeout=timeout, follow_redirects=False)

    async def aclose(self) -> None:
        await self._http.aclose()

    # ------------------------------------------------------------ plumbing

    def _headers(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        headers = {"content-type": "application/json"}
        if self.token:
            headers["authorization"] = f"Bearer {self.token}"
        if extra:
            headers.update(extra)
        return headers

    async def _request(self, method: str, path: str, **kwargs) -> httpx.Response:
        response = await self._http.request(
            method, f"{self.base_url}{path}", headers=self._headers(kwargs.pop("extra_headers", None)), **kwargs
        )
        _raise_for_error(response)
        return response

    # ------------------------------------------------------------- pairing

    async def create_hub(self, device_name: str) -> dict:
        """Create a brand-new hub with this machine as its first device."""
        response = await self._request(
            "POST",
            "/v1/hubs",
            json={"deviceName": device_name, "platform": "windows", "capabilities": WINDOWS_CAPABILITIES},
        )
        data = response.json()
        self.token = data["token"]
        return data

    async def pair(self, code: str, device_name: str) -> dict:
        """Join an existing hub using a 6-digit code shown on the phone."""
        response = await self._request(
            "POST",
            "/v1/pair/complete",
            json={
                "code": code.strip(),
                "deviceName": device_name,
                "platform": "windows",
                "capabilities": WINDOWS_CAPABILITIES,
            },
        )
        data = response.json()
        self.token = data["token"]
        return data

    async def start_pairing(self) -> dict:
        return (await self._request("POST", "/v1/pair/start")).json()

    async def me(self) -> dict:
        return (await self._request("GET", "/v1/me")).json()

    async def devices(self) -> list[dict]:
        return (await self._request("GET", "/v1/devices")).json()["devices"]

    async def revoke(self, device_id: str) -> None:
        await self._request("DELETE", f"/v1/devices/{device_id}")

    async def refresh_token(self) -> str:
        data = (await self._request("POST", "/v1/auth/refresh")).json()
        self.token = data["token"]
        return self.token

    # --------------------------------------------------------------- tasks

    async def send_task(self, target: str, instruction: str) -> dict:
        response = await self._request(
            "POST", "/v1/tasks", json={"target": target, "kind": "instruction", "instruction": instruction}
        )
        return response.json()["task"]

    async def report_task(
        self, task_id: str, status: str, summary: str | None = None, evidence: str | None = None
    ) -> None:
        body: dict[str, str] = {"status": status}
        if summary:
            body["summary"] = summary[:2000]
        if evidence:
            body["evidence"] = evidence[:2000]
        await self._request("POST", f"/v1/tasks/{task_id}/events", json=body)

    # ----------------------------------------------------------- websocket

    async def listen(
        self,
        on_task: Callable[[Task], Awaitable[None]],
        on_presence: Callable[[str, bool], Awaitable[None]] | None = None,
        on_state: Callable[[bool], Awaitable[None]] | None = None,
    ) -> None:
        """Hold the presence socket open, reconnecting with backoff forever.

        ``on_state(connected)`` fires on every connect and disconnect so the
        UI can show DISCONNECTED honestly instead of looking idle.
        """
        if not self.token:
            raise BackendError(401, "unauthorized", "Device is not paired yet")

        ws_url = self.base_url.replace("https://", "wss://").replace("http://", "ws://") + "/v1/ws"
        backoff = 1.0

        while True:
            try:
                async with websockets.connect(
                    ws_url,
                    additional_headers={"authorization": f"Bearer {self.token}"},
                    ping_interval=30,
                    ping_timeout=20,
                ) as socket:
                    backoff = 1.0
                    if on_state:
                        await on_state(True)
                    async for raw in socket:
                        await self._dispatch(raw, on_task, on_presence)
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 - reconnect on anything
                log.warning("presence socket dropped: %s", redact(str(exc)))

            if on_state:
                await on_state(False)
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 60.0)  # capped exponential backoff

    async def _dispatch(
        self,
        raw: str | bytes,
        on_task: Callable[[Task], Awaitable[None]],
        on_presence: Callable[[str, bool], Awaitable[None]] | None,
    ) -> None:
        try:
            message = json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            return
        kind = message.get("type")

        if kind == "task":
            data = message.get("task", {})
            await on_task(
                Task(
                    id=data.get("id", ""),
                    origin=data.get("origin", ""),
                    kind=data.get("kind", "instruction"),
                    payload=data.get("payload", {}),
                    status=data.get("status", "delivered"),
                )
            )
        elif kind == "presence" and on_presence:
            await on_presence(message.get("deviceId", ""), bool(message.get("online")))

    # ------------------------------------------------------------ AI relay

    async def chat(
        self, messages: list[dict], model: str, openrouter_key: str, max_tokens: int = 1024
    ) -> AsyncIterator[str]:
        """Stream assistant text from OpenRouter through the backend relay."""
        payload = {
            "model": model,
            "messages": messages,
            "max_tokens": max_tokens,
            "stream": True,
        }
        async with self._http.stream(
            "POST",
            f"{self.base_url}/v1/ai/chat/completions",
            headers=self._headers({"x-openrouter-key": openrouter_key}),
            json=payload,
        ) as response:
            if not response.is_success:
                await response.aread()
                _raise_for_error(response)
            async for line in response.aiter_lines():
                if not line.startswith("data: "):
                    continue
                data = line[6:].strip()
                if data == "[DONE]":
                    return
                try:
                    chunk = json.loads(data)
                except json.JSONDecodeError:
                    continue
                delta = chunk.get("choices", [{}])[0].get("delta", {}).get("content")
                if delta:
                    yield delta

    # --------------------------------------------------------- voice relay

    async def speak(self, text: str, voice: str, pitch: str, rate: str) -> bytes:
        """Synthesise Arabic speech and return MP3 bytes."""
        response = await self._request(
            "POST", "/v1/voice/tts", json={"text": text, "voice": voice, "pitch": pitch, "rate": rate}
        )
        return response.content
