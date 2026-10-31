import json

import pytest

from jarvis_win.core import config
from jarvis_win.core.config import Settings, redact


@pytest.fixture
def temp_config(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "config_dir", lambda: tmp_path / "JARVIS")
    monkeypatch.setattr(Settings, "path", classmethod(lambda cls: config.config_dir() / "settings.json"))
    return tmp_path


class TestSettings:
    def test_defaults_match_spec(self):
        s = Settings()
        assert s.voice == "ar-DZ-IsmaelNeural"
        assert s.voice_pitch == "-15Hz"
        assert s.voice_rate == "-8%"
        assert s.language == "ar"
        assert s.confirm_sensitive_actions is True

    def test_round_trip(self, temp_config):
        s = Settings(model="x/y", max_tool_calls=3, enabled_tools=["open_url"])
        s.save()
        loaded = Settings.load()
        assert loaded.model == "x/y"
        assert loaded.max_tool_calls == 3
        assert loaded.enabled_tools == ["open_url"]

    def test_missing_file_gives_defaults(self, temp_config):
        assert Settings.load().model == Settings().model

    def test_corrupt_file_gives_defaults(self, temp_config):
        path = Settings.path()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("{not json", encoding="utf-8")
        assert Settings.load().voice == "ar-DZ-IsmaelNeural"

    def test_unknown_fields_ignored(self, temp_config):
        path = Settings.path()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"model": "keep", "from_future": 1}), encoding="utf-8")
        loaded = Settings.load()
        assert loaded.model == "keep"
        assert not hasattr(loaded, "from_future")

    def test_arabic_survives_round_trip(self, temp_config):
        Settings(enabled_tools=["فتح_تطبيق"]).save()
        assert Settings.load().enabled_tools == ["فتح_تطبيق"]


class TestRedaction:
    def test_hides_device_token(self):
        token = "jv1.abcdefghijkm.npqrstuvwx." + "A" * 45
        assert token not in redact(f"failed with {token}")
        assert "jv1.***" in redact(token)

    def test_hides_openrouter_key(self):
        assert "sk-or-***" in redact("key=sk-or-v1-abcdef123456")

    def test_hides_cloudflare_token(self):
        assert "cfut_***" in redact("CLOUDFLARE_API_TOKEN=cfut_abcdef123456")

    def test_leaves_ordinary_text_alone(self):
        assert redact("لا يوجد اتصال بالإنترنت") == "لا يوجد اتصال بالإنترنت"
