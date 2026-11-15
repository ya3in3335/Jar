"""Groq: the model, the ears and the voice, all on one key.

The desktop app had a microphone, a voice and tools, but nothing that could
think: any request the local intent router did not recognise ended in "this
needs a model". This module is that model, and it also gives the app speech
recognition and Arabic/English voices that work from a frozen build with
nothing to install — the local Whisper engine cannot be bundled.

Nothing here imports Qt, and the pure parts (payloads, parsing, splitting,
WAV reading) take no network, so they are tested on their own.
"""

from __future__ import annotations

import json
import logging
import struct
from dataclasses import dataclass, field
from typing import Any, Callable, Final, Sequence

import httpx

from .config import redact

log = logging.getLogger(__name__)

BASE_URL: Final = "https://api.groq.com/openai/v1"
DEFAULT_MODEL: Final = "llama-3.3-70b-versatile"
SUGGESTED_MODELS: Final = (
    "llama-3.3-70b-versatile",
    "openai/gpt-oss-120b",
    "openai/gpt-oss-20b",
    "llama-3.1-8b-instant",
)
STT_MODEL: Final = "whisper-large-v3-turbo"
TTS_ARABIC: Final = "canopylabs/orpheus-arabic-saudi"
TTS_ENGLISH: Final = "canopylabs/orpheus-v1-english"
#: Groq's documented limit for one speech request.
MAX_TTS_CHARS: Final = 200

_AR: Final = "groq-ar-"
_EN: Final = "groq-en-"
DEFAULT_ARABIC_VOICE: Final = _AR + "fahad"
DEFAULT_ENGLISH_VOICE: Final = _EN + "troy"

#: (id, display name, locale, gender). Ids carry the language so one string
#: picks the model. The Arabic voices are Saudi-dialect.
VOICES: Final = (
    (_AR + "fahad", "فهد", "ar-SA", "male"),
    (_AR + "abdullah", "عبدالله", "ar-SA", "male"),
    (_AR + "sultan", "سلطان", "ar-SA", "male"),
    (_AR + "noura", "نورة", "ar-SA", "female"),
    (_AR + "lulwa", "لولوة", "ar-SA", "female"),
    (_AR + "aisha", "عائشة", "ar-SA", "female"),
    (_EN + "troy", "Troy", "en-US", "male"),
    (_EN + "austin", "Austin", "en-US", "male"),
    (_EN + "daniel", "Daniel", "en-US", "male"),
    (_EN + "hannah", "Hannah", "en-US", "female"),
    (_EN + "diana", "Diana", "en-US", "female"),
    (_EN + "autumn", "Autumn", "en-US", "female"),
)

SYSTEM_PROMPT: Final = (
    "أنت جارفيس، مساعد صوتي يعمل على حاسوب ويندوز. "
    "ردّ بإيجاز وبلغة المستخدم (العربية غالبًا)، فردّك سيُقرأ بصوت عالٍ: بلا قوائم ولا رموز. "
    "استعمل الأدوات المتاحة لتنفيذ ما يُطلب بدل وصف طريقة تنفيذه. "
    "لا تقل إنك نفّذت شيئًا لم تنفّذه أداة فعلًا، وإن فشلت أداة فقل ذلك."
)


class GroqError(RuntimeError):
    """A failure worth showing the user, already phrased for them."""


# ----------------------------------------------------------------- errors


def describe_error(status: int, body: str, what: str, model: str = "") -> str:
    """Say why a request failed, using Groq's own words where they help.

    The speech models refuse every request until their terms are accepted
    once in the Groq console, which from inside the app looks like a dead
    voice — so that case names the fix.
    """
    code = message = ""
    try:
        error = json.loads(body).get("error") or {}
        code = str(error.get("code") or "")
        message = str(error.get("message") or "")
    except (ValueError, AttributeError):
        pass

    if code == "model_terms_required" or "terms" in message.lower():
        return (
            f"{what} يحتاج قبول شروط النموذج مرّة واحدة: افتح "
            f"console.groq.com/playground?model={model} بحسابك واقبل الشروط ثم أعد المحاولة"
        )
    if status in (401, 403):
        return "مفتاح Groq غير صحيح أو منتهي"
    if status == 429:
        return "تجاوزت حدّ الطلبات المجاني في Groq، حاول بعد قليل"
    if 500 <= status <= 599:
        return "خدمة Groq غير متاحة حاليًا"
    if message:
        return f"رفضت Groq الطلب: {redact(message)[:160]}"
    return f"فشل طلب Groq (HTTP {status})"


# ------------------------------------------------------------------- chat


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    #: Parsed arguments; empty when the model sent something unparsable.
    args: dict[str, Any]
    #: Set when the arguments were not valid JSON, so the model can be told.
    bad_arguments: bool = False


@dataclass(frozen=True)
class Reply:
    text: str
    tool_calls: tuple[ToolCall, ...] = ()
    #: The assistant message exactly as returned, to be echoed back in history.
    message: dict[str, Any] = field(default_factory=dict)


def build_chat_payload(
    model: str,
    messages: Sequence[dict[str, Any]],
    tools: Sequence[dict[str, Any]],
    max_tokens: int,
) -> dict[str, Any]:
    payload: dict[str, Any] = {"model": model, "messages": list(messages), "max_tokens": max_tokens}
    if tools:
        payload["tools"] = list(tools)
        payload["tool_choice"] = "auto"
    return payload


def parse_chat_reply(body: dict[str, Any]) -> Reply:
    choices = body.get("choices") or []
    if not choices:
        raise GroqError("أرجع النموذج ردًّا فارغًا")
    message = choices[0].get("message") or {}

    calls: list[ToolCall] = []
    for raw in message.get("tool_calls") or []:
        function = raw.get("function") or {}
        name = str(function.get("name") or "")
        if not name:
            continue
        arguments = function.get("arguments")
        bad = False
        try:
            args = json.loads(arguments) if isinstance(arguments, str) and arguments.strip() else (arguments or {})
        except ValueError:
            args, bad = {}, True
        if not isinstance(args, dict):
            args, bad = {}, True
        calls.append(ToolCall(id=str(raw.get("id") or name), name=name, args=args, bad_arguments=bad))

    return Reply(text=str(message.get("content") or "").strip(), tool_calls=tuple(calls), message=message)


# ----------------------------------------------------------------- speech


def is_english_voice(voice: str | None) -> bool:
    return bool(voice) and str(voice).startswith(_EN)


def voice_spec(voice: str | None, text: str) -> tuple[str, str]:
    """(model, voice name) for [voice].

    A voice id from another engine gets the default for the script the text
    is written in, so Arabic is never read by an English voice.
    """
    known = voice if voice in {v[0] for v in VOICES} else None
    if known is None:
        arabic = any("؀" <= ch <= "ۿ" for ch in text)
        known = DEFAULT_ARABIC_VOICE if arabic else DEFAULT_ENGLISH_VOICE
    if known.startswith(_EN):
        return TTS_ENGLISH, known[len(_EN):]
    return TTS_ARABIC, known[len(_AR):]


def split_for_speech(text: str, limit: int = MAX_TTS_CHARS) -> list[str]:
    """Cut [text] into pieces of at most [limit] characters.

    Prefers the end of a sentence, then a comma, then a space. A single word
    longer than the limit is cut hard: it cannot be spoken otherwise.
    """
    rest = " ".join(text.split())
    pieces: list[str] = []
    while len(rest) > limit:
        window = rest[:limit]
        cut = max(window.rfind(ch) for ch in ".!?؟…")
        if cut < limit // 3:
            cut = max(window.rfind(ch) for ch in ",،;؛:")
        if cut < limit // 3:
            space = window.rfind(" ")
            cut = space - 1 if space > 0 else limit - 1
        pieces.append(rest[: cut + 1].strip())
        rest = rest[cut + 1 :].strip()
    if rest:
        pieces.append(rest)
    return [p for p in pieces if p]


def wav_to_pcm(data: bytes) -> tuple[bytes, int]:
    """Mono 16-bit PCM and its sample rate from a RIFF/WAVE body.

    Chunks are walked rather than assuming a 44-byte header, and a streamed
    file that declares an unknown length is read to its end. Stereo is mixed
    down. Anything that is not 16-bit PCM raises [GroqError].
    """
    if len(data) < 12 or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise GroqError("الصوت المُرجَع ليس بصيغة WAV")

    rate = channels = bits = 0
    offset = 12
    while offset + 8 <= len(data):
        tag = data[offset : offset + 4]
        (declared,) = struct.unpack_from("<I", data, offset + 4)
        body = offset + 8
        size = len(data) - body if declared in (0, 0xFFFFFFFF) or body + declared > len(data) else declared

        if tag == b"fmt " and size >= 16:
            fmt, channels, rate = struct.unpack_from("<HHI", data, body)
            (bits,) = struct.unpack_from("<H", data, body + 14)
            if fmt == 0xFFFE and size >= 26:  # extensible: the real format follows
                (fmt,) = struct.unpack_from("<H", data, body + 24)
            if fmt != 1:
                raise GroqError("صيغة الصوت المُرجَعة غير مدعومة")
        elif tag == b"data":
            if rate <= 0 or bits != 16 or channels not in (1, 2):
                raise GroqError("صيغة الصوت المُرجَعة غير مدعومة")
            frame = 2 * channels
            pcm = data[body : body + size - size % frame]
            if not pcm:
                raise GroqError("أرجعت Groq ملفًا صوتيًا فارغًا")
            if channels == 2:
                samples = struct.unpack(f"<{len(pcm) // 2}h", pcm)
                mono = [(samples[i] + samples[i + 1]) // 2 for i in range(0, len(samples), 2)]
                pcm = struct.pack(f"<{len(mono)}h", *mono)
            return pcm, rate
        offset = body + size + (size & 1)  # chunks are word-aligned
    raise GroqError("لم يُعثر على بيانات صوتية في الملف المُرجَع")


# ----------------------------------------------------------------- client


class GroqClient:
    """Blocking HTTP calls; callers run them off the UI thread."""

    def __init__(self, key_provider: Callable[[], str | None], timeout: float = 60.0) -> None:
        self._key_provider = key_provider
        self._timeout = timeout

    def has_key(self) -> bool:
        return bool((self._key_provider() or "").strip())

    def _headers(self) -> dict[str, str]:
        key = (self._key_provider() or "").strip()
        if not key:
            raise GroqError("أضف مفتاح Groq من الإعدادات")
        return {"Authorization": f"Bearer {key}"}

    def _post(self, path: str, what: str, model: str, **kwargs: Any) -> httpx.Response:
        try:
            response = httpx.post(f"{BASE_URL}{path}", headers=self._headers(), timeout=self._timeout, **kwargs)
        except httpx.TimeoutException as exc:
            raise GroqError("انتهت مهلة الاتصال بـ Groq") from exc
        except httpx.HTTPError as exc:
            raise GroqError("تعذّر الاتصال بـ Groq — تحقّق من الإنترنت") from exc
        if response.status_code >= 400:
            raise GroqError(describe_error(response.status_code, response.text, what, model))
        return response

    def chat(
        self,
        model: str,
        messages: Sequence[dict[str, Any]],
        tools: Sequence[dict[str, Any]] = (),
        max_tokens: int = 1024,
    ) -> Reply:
        payload = build_chat_payload(model, messages, tools, max_tokens)
        response = self._post("/chat/completions", "النموذج", model, json=payload)
        try:
            return parse_chat_reply(response.json())
        except ValueError as exc:
            raise GroqError("ردّ غير مفهوم من Groq") from exc

    def transcribe(self, wav: bytes, language: str | None = None) -> str:
        data = {"model": STT_MODEL, "response_format": "json"}
        if language:
            data["language"] = language
        response = self._post(
            "/audio/transcriptions",
            "التعرّف على الكلام",
            STT_MODEL,
            data=data,
            files={"file": ("speech.wav", wav, "audio/wav")},
        )
        try:
            return str(response.json().get("text") or "").strip()
        except ValueError as exc:
            raise GroqError("ردّ غير مفهوم من Groq") from exc

    def speech(self, model: str, voice: str, text: str) -> bytes:
        payload = {"model": model, "voice": voice, "input": text, "response_format": "wav"}
        return self._post("/audio/speech", "صوت Groq", model, json=payload).content


# ------------------------------------------------------------------ agent


@dataclass
class AgentOutcome:
    text: str
    #: (summary, plan) when a tool stopped for the user's approval.
    pending: tuple[str, Any] | None = None
    tool_calls: int = 0
    cancelled: bool = False


def run_agent(
    client: GroqClient,
    model: str,
    utterance: str,
    history: Sequence[dict[str, Any]],
    registry: Any,
    *,
    max_tool_calls: int = 8,
    max_tokens: int = 1024,
    on_tool: Callable[[str], None] | None = None,
    cancelled: Callable[[], bool] = lambda: False,
) -> AgentOutcome:
    """Ask the model, run the tools it asks for, and return what it says.

    A tool that needs approval (deletion) stops the loop: the plan goes back
    to the caller and nothing is deleted here.
    """
    messages: list[dict[str, Any]] = [{"role": "system", "content": SYSTEM_PROMPT}]
    messages += list(history)[-12:]
    messages.append({"role": "user", "content": utterance})
    tools = registry.schemas()
    used = 0

    while True:
        if cancelled():
            return AgentOutcome("", tool_calls=used, cancelled=True)
        # Past the budget the model is asked once more without tools, so it
        # must answer with what it has instead of looping.
        reply = client.chat(model, messages, tools if used < max_tool_calls else (), max_tokens)
        if not reply.tool_calls:
            return AgentOutcome(reply.text or "لم أجد ما أقوله.", tool_calls=used)

        messages.append(reply.message)
        for call in reply.tool_calls:
            if cancelled():
                return AgentOutcome("", tool_calls=used, cancelled=True)
            used += 1
            tool = registry.find(call.name)
            if tool is None:
                content = f"الأداة '{call.name}' غير موجودة على هذا الحاسوب"
            elif call.bad_arguments:
                content = "وسائط الأداة ليست JSON صالحًا"
            elif (problem := tool.validate(call.args)) is not None:
                content = problem
            else:
                if on_tool is not None:
                    on_tool(call.name)
                try:
                    result = tool.run(dict(call.args))
                except Exception as exc:  # a tool must not take the assistant down
                    log.warning("tool %s failed: %s", call.name, type(exc).__name__)
                    content = f"فشلت الأداة: {type(exc).__name__}"
                else:
                    if result.pending is not None:
                        return AgentOutcome(result.summary, pending=(result.summary, result.pending), tool_calls=used)
                    content = result.summary + (f"\n{result.detail}" if result.detail else "")
                    if not result.ok:
                        content = "فشل: " + content
            messages.append({"role": "tool", "tool_call_id": call.id, "content": content[:4000]})
