"""The Groq layer: payloads, parsing, speech helpers and the agent loop.

No network: the client is replaced by a scripted fake.
"""

from __future__ import annotations

import struct

import pytest

from jarvis_win.core import groq
from jarvis_win.core.config import Settings, redact
from jarvis_win.core.groq import (
    GroqError,
    Reply,
    ToolCall,
    build_chat_payload,
    describe_error,
    parse_chat_reply,
    run_agent,
    split_for_speech,
    voice_spec,
    wav_to_pcm,
)
from jarvis_win.core.tools import Param, Tool, ToolResult


def wav(rate: int, channels: int, bits: int, samples: list[int], extra: bool = False, unknown_size: bool = False) -> bytes:
    data = struct.pack(f"<{len(samples)}h", *samples)
    fmt = struct.pack("<HHIIHH", 1, channels, rate, rate * channels * bits // 8, channels * bits // 8, bits)
    body = b"WAVE" + b"fmt " + struct.pack("<I", 16) + fmt
    if extra:
        body += b"LIST" + struct.pack("<I", 6) + b"\0" * 6
    body += b"data" + struct.pack("<I", 0xFFFFFFFF if unknown_size else len(data)) + data
    return b"RIFF" + struct.pack("<I", 0xFFFFFFFF if unknown_size else len(body)) + body


class TestDefaults:
    def test_groq_is_the_default_everywhere(self):
        s = Settings()
        assert s.model == groq.DEFAULT_MODEL
        assert s.tts_provider == "groq" and s.stt_provider == "groq"

    def test_the_old_paid_default_is_replaced_on_load(self, tmp_path, monkeypatch):
        monkeypatch.setattr(Settings, "path", classmethod(lambda cls: tmp_path / "settings.json"))
        old = Settings(model="anthropic/claude-3.5-haiku")
        old.save()
        assert Settings.load().model == groq.DEFAULT_MODEL

    def test_a_chosen_model_is_kept(self, tmp_path, monkeypatch):
        monkeypatch.setattr(Settings, "path", classmethod(lambda cls: tmp_path / "settings.json"))
        Settings(model="openai/gpt-oss-120b").save()
        assert Settings.load().model == "openai/gpt-oss-120b"

    def test_groq_keys_are_redacted(self):
        assert "gsk_abcdef123456" not in redact("key gsk_abcdef123456 leaked")


class TestChat:
    def test_tools_are_sent_with_auto_choice_only_when_present(self):
        with_tools = build_chat_payload("m", [{"role": "user", "content": "x"}], [{"type": "function"}], 50)
        assert with_tools["tool_choice"] == "auto" and with_tools["max_tokens"] == 50
        assert "tools" not in build_chat_payload("m", [], [], 50)

    def test_a_plain_reply_is_text(self):
        reply = parse_chat_reply({"choices": [{"message": {"role": "assistant", "content": " مرحبا "}}]})
        assert reply.text == "مرحبا" and reply.tool_calls == ()

    def test_tool_calls_are_parsed_with_their_arguments(self):
        body = {"choices": [{"message": {"content": None, "tool_calls": [
            {"id": "c1", "function": {"name": "open_url", "arguments": '{"url": "https://a.b"}'}}]}}]}
        (call,) = parse_chat_reply(body).tool_calls
        assert call == ToolCall(id="c1", name="open_url", args={"url": "https://a.b"})

    def test_unparsable_arguments_are_flagged_not_fatal(self):
        body = {"choices": [{"message": {"tool_calls": [{"id": "c", "function": {"name": "t", "arguments": "{oops"}}]}}]}
        assert parse_chat_reply(body).tool_calls[0].bad_arguments

    def test_an_empty_response_is_an_error(self):
        with pytest.raises(GroqError):
            parse_chat_reply({"choices": []})


class TestErrors:
    def test_unaccepted_terms_name_the_page(self):
        body = '{"error":{"message":"requires terms acceptance","code":"model_terms_required"}}'
        assert "console.groq.com/playground?model=m" in describe_error(400, body, "صوت Groq", "m")

    def test_bad_key_and_rate_limit(self):
        assert "مفتاح" in describe_error(401, "{}", "x")
        assert "حدّ" in describe_error(429, "{}", "x")

    def test_other_refusals_carry_groqs_message(self):
        assert "voice must be" in describe_error(400, '{"error":{"message":"voice must be one of"}}', "x")

    def test_a_non_json_body_still_yields_a_message(self):
        assert "418" in describe_error(418, "<html>", "x")


class TestSpeech:
    def test_voice_id_picks_model_and_name(self):
        assert voice_spec("groq-ar-fahad", "x") == (groq.TTS_ARABIC, "fahad")
        assert voice_spec("groq-en-troy", "x") == (groq.TTS_ENGLISH, "troy")

    def test_a_foreign_voice_falls_back_by_script(self):
        assert voice_spec("ar-DZ-IsmaelNeural", "مرحبا")[0] == groq.TTS_ARABIC
        assert voice_spec(None, "hello")[0] == groq.TTS_ENGLISH

    def test_six_voices_per_language(self):
        assert sum(v[2].startswith("ar") for v in groq.VOICES) == 6
        assert sum(v[2].startswith("en") for v in groq.VOICES) == 6

    def test_split_respects_the_limit_and_loses_nothing(self):
        text = " ".join(f"كلمة{i}" for i in range(80)) + ". " + " ".join(f"word{i}" for i in range(80)) + "?"
        pieces = split_for_speech(text)
        assert len(pieces) > 1 and all(len(p) <= groq.MAX_TTS_CHARS for p in pieces)
        assert "".join(pieces).replace(" ", "") == text.replace(" ", "")

    def test_split_prefers_a_sentence_end(self):
        first = "هذه جملة أولى طويلة نسبيًا تنتهي هنا."
        assert split_for_speech(first + " " + "ثم " * 40, limit=60)[0] == first

    def test_an_overlong_word_is_cut_hard(self):
        assert [len(p) for p in split_for_speech("a" * 450, limit=200)] == [200, 200, 50]

    def test_blank_text_is_nothing(self):
        assert split_for_speech("   ") == []

    def test_wav_rate_and_samples(self):
        pcm, rate = wav_to_pcm(wav(24_000, 1, 16, [1, -2, 3]))
        assert rate == 24_000 and len(pcm) == 6

    def test_wav_extra_chunk_and_unknown_length(self):
        assert wav_to_pcm(wav(48_000, 1, 16, [7, 8], extra=True)) == (struct.pack("<2h", 7, 8), 48_000)
        assert wav_to_pcm(wav(48_000, 1, 16, [5, 6], unknown_size=True))[0] == struct.pack("<2h", 5, 6)

    def test_wav_stereo_is_mixed_down(self):
        pcm, _ = wav_to_pcm(wav(24_000, 2, 16, [100, 300, -50, 50]))
        assert struct.unpack("<2h", pcm) == (200, 0)

    @pytest.mark.parametrize("data", [b"not audio at all", b"RIFF", wav(24_000, 1, 8, [1, 2]), wav(24_000, 1, 16, [])])
    def test_anything_else_is_refused(self, data):
        with pytest.raises(GroqError):
            wav_to_pcm(data)


class FakeRegistry:
    def __init__(self, tools):
        self._tools = {t.name: t for t in tools}

    def find(self, name):
        return self._tools.get(name)

    def schemas(self):
        return [t.schema() for t in self._tools.values()]


class FakeClient:
    """Replays scripted replies and records what it was asked."""

    def __init__(self, replies):
        self.replies = list(replies)
        self.calls = []

    def chat(self, model, messages, tools=(), max_tokens=1024):
        self.calls.append({"messages": list(messages), "tools": list(tools)})
        return self.replies.pop(0)


def call(name, **args):
    message = {"role": "assistant", "tool_calls": [{"id": "c1", "function": {"name": name}}]}
    return Reply(text="", tool_calls=(ToolCall(id="c1", name=name, args=args),), message=message)


class TestAgent:
    def make(self, run):
        return Tool("open_url", "يفتح رابطًا", [Param("url", "string", "الرابط")], run)

    def test_a_direct_answer_needs_no_tools(self):
        client = FakeClient([Reply("الجواب")])
        outcome = run_agent(client, "m", "سؤال", [], FakeRegistry([]))
        assert outcome.text == "الجواب" and outcome.tool_calls == 0
        assert client.calls[0]["messages"][0]["role"] == "system"
        assert client.calls[0]["messages"][-1] == {"role": "user", "content": "سؤال"}

    def test_a_tool_runs_and_its_result_goes_back_to_the_model(self):
        ran = []
        tool = self.make(lambda args: (ran.append(args), ToolResult.success("فُتح"))[1])
        client = FakeClient([call("open_url", url="https://a.b"), Reply("تمّ")])
        seen = []
        outcome = run_agent(client, "m", "افتح", [], FakeRegistry([tool]), on_tool=seen.append)
        assert ran == [{"url": "https://a.b"}] and seen == ["open_url"]
        assert outcome.text == "تمّ" and outcome.tool_calls == 1
        assert client.calls[1]["messages"][-1] == {"role": "tool", "tool_call_id": "c1", "content": "فُتح"}

    def test_invalid_arguments_are_reported_and_the_tool_does_not_run(self):
        ran = []
        tool = self.make(lambda args: ran.append(args) or ToolResult.success("x"))
        client = FakeClient([call("open_url"), Reply("لم أستطع")])
        run_agent(client, "m", "افتح", [], FakeRegistry([tool]))
        assert ran == []
        assert "مطلوب" in client.calls[1]["messages"][-1]["content"]

    def test_an_unknown_tool_is_reported(self):
        client = FakeClient([call("fly"), Reply("لا أستطيع")])
        run_agent(client, "m", "طِر", [], FakeRegistry([]))
        assert "غير موجودة" in client.calls[1]["messages"][-1]["content"]

    def test_a_tool_needing_approval_stops_the_loop_with_its_plan(self):
        plan = object()
        tool = self.make(lambda args: ToolResult.needs_approval("سيُحذف ملفان", plan))
        client = FakeClient([call("open_url", url="x")])
        outcome = run_agent(client, "m", "احذف", [], FakeRegistry([tool]))
        assert outcome.pending == ("سيُحذف ملفان", plan)
        assert len(client.calls) == 1  # the model is not asked again

    def test_a_crashing_tool_does_not_crash_the_assistant(self):
        def boom(args):
            raise OSError("disk")

        client = FakeClient([call("open_url", url="x"), Reply("تعذّر")])
        outcome = run_agent(client, "m", "افتح", [], FakeRegistry([self.make(boom)]))
        assert outcome.text == "تعذّر"
        assert "OSError" in client.calls[1]["messages"][-1]["content"]

    def test_past_the_budget_the_model_must_answer_without_tools(self):
        tool = self.make(lambda args: ToolResult.success("ok"))
        client = FakeClient([call("open_url", url="x"), call("open_url", url="y"), Reply("كفى")])
        outcome = run_agent(client, "m", "كرّر", [], FakeRegistry([tool]), max_tool_calls=2)
        assert outcome.text == "كفى"
        assert client.calls[0]["tools"] and client.calls[1]["tools"] and client.calls[2]["tools"] == []

    def test_cancel_before_the_first_request_asks_nothing(self):
        client = FakeClient([])
        outcome = run_agent(client, "m", "x", [], FakeRegistry([]), cancelled=lambda: True)
        assert outcome.cancelled and client.calls == []

    def test_only_recent_history_is_sent(self):
        history = [{"role": "user", "content": str(i)} for i in range(40)]
        client = FakeClient([Reply("ok")])
        run_agent(client, "m", "now", history, FakeRegistry([]))
        assert len(client.calls[0]["messages"]) == 1 + 12 + 1
