"""Settings and secret storage.

Non-secret settings live in a JSON file under the user's config directory.
Secrets (the device token, the OpenRouter key) go to the OS credential
store via ``keyring`` — on Windows that is the Credential Manager.

If no keyring backend is available the secret is *not* silently written to
disk. ``secrets_available()`` reports the truth so the UI can say so.
"""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Final

try:  # keyring is optional at import time so tests run without a backend
    import keyring
    from keyring.errors import KeyringError
except Exception:  # pragma: no cover - exercised only where keyring is absent
    keyring = None  # type: ignore[assignment]

    class KeyringError(Exception):  # type: ignore[no-redef]
        pass


SERVICE: Final = "JARVIS"
_TOKEN_KEY: Final = "device_token"
_OPENROUTER_KEY: Final = "openrouter_api_key"


def config_dir() -> Path:
    """Per-user config directory; APPDATA on Windows, XDG elsewhere."""
    if os.name == "nt":
        base = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return base / "JARVIS"


@dataclass
class Settings:
    """Everything the user can change that is not a secret."""

    backend_url: str = ""
    model: str = "anthropic/claude-3.5-haiku"
    routing_mode: str = "balanced"  # economy | balanced | quality
    voice: str = "ar-DZ-IsmaelNeural"
    voice_pitch: str = "-15Hz"
    voice_rate: str = "-8%"
    auto_speak: bool = True
    wake_word_enabled: bool = False
    push_to_talk_hotkey: str = "Ctrl+Alt+J"
    motion_intensity: str = "full"  # full | reduced | off
    language: str = "ar"
    confirm_sensitive_actions: bool = True
    max_tool_calls: int = 8
    max_output_tokens: int = 1024
    daily_cost_limit_usd: float = 0.0  # 0 means no limit
    enabled_tools: list[str] = field(default_factory=list)

    # --- persistence ---------------------------------------------------

    @classmethod
    def path(cls) -> Path:
        return config_dir() / "settings.json"

    @classmethod
    def load(cls) -> "Settings":
        """Load settings, ignoring unknown or corrupt fields."""
        path = cls.path()
        if not path.is_file():
            return cls()
        try:
            raw: Any = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return cls()
        if not isinstance(raw, dict):
            return cls()
        known = {f for f in cls().__dataclass_fields__}
        return cls(**{k: v for k, v in raw.items() if k in known})

    def save(self) -> Path:
        path = self.path()
        path.parent.mkdir(parents=True, exist_ok=True)
        # Write then replace, so a crash cannot leave a truncated file.
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(asdict(self), indent=2, ensure_ascii=False), encoding="utf-8")
        tmp.replace(path)
        return path


# --------------------------------------------------------------- secrets


def secrets_available() -> bool:
    """True when an OS credential store is usable."""
    if keyring is None:
        return False
    try:
        backend = keyring.get_keyring()
    except Exception:
        return False
    name = type(backend).__name__
    # The fail backend raises on use; the plaintext one is not a secret store.
    return "Fail" not in name and "PlaintextKeyring" not in name


def _get(key: str) -> str | None:
    if not secrets_available():
        return None
    try:
        return keyring.get_password(SERVICE, key)  # type: ignore[union-attr]
    except KeyringError:
        return None


def _set(key: str, value: str | None) -> bool:
    """Store or delete a secret. Returns False when no store is available."""
    if not secrets_available():
        return False
    try:
        if value is None:
            keyring.delete_password(SERVICE, key)  # type: ignore[union-attr]
        else:
            keyring.set_password(SERVICE, key, value)  # type: ignore[union-attr]
        return True
    except KeyringError:
        return False


def get_device_token() -> str | None:
    return _get(_TOKEN_KEY)


def set_device_token(token: str | None) -> bool:
    return _set(_TOKEN_KEY, token)


def get_openrouter_key() -> str | None:
    return _get(_OPENROUTER_KEY)


def set_openrouter_key(key: str | None) -> bool:
    return _set(_OPENROUTER_KEY, key)


def redact(text: str) -> str:
    """Mask anything token-shaped before it reaches a log or the screen."""
    import re

    text = re.sub(r"jv1\.[a-z2-9]{12}\.[a-z2-9]{10}\.[A-Za-z0-9_-]+", "jv1.***", text)
    text = re.sub(r"sk-or-[A-Za-z0-9_-]{6,}", "sk-or-***", text)
    text = re.sub(r"cfut_[A-Za-z0-9]{6,}", "cfut_***", text)
    return text
