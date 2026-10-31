"""Speech output, behind a replaceable provider.

Windows is the easy platform for this: SAPI5 exposes every voice installed
on the machine, so the user can pick from whatever they have, offline and
instantly. Azure adds the neural Arabic voices on top, ar-DZ-IsmaelNeural
among them.
"""

from __future__ import annotations

import logging
import threading
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Final

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class Voice:
    id: str
    name: str
    locale: str = ""
    gender: str = ""
    provider: str = ""

    @property
    def is_arabic(self) -> bool:
        return self.locale.lower().startswith("ar")

    @property
    def display(self) -> str:
        bits = [self.name]
        if self.locale:
            bits.append(f"({self.locale})")
        return " ".join(bits)


class TtsProvider(ABC):
    id: str = "tts"
    label: str = "محرّك نطق"

    @abstractmethod
    def is_available(self) -> bool: ...

    @abstractmethod
    def voices(self) -> list[Voice]: ...

    @abstractmethod
    def speak(self, text: str, voice: str | None, pitch: str, rate: str) -> None:
        """Speak, blocking until finished or stopped."""

    @abstractmethod
    def stop(self) -> None: ...


class TtsError(RuntimeError):
    pass


def pitch_to_multiplier(pitch: str) -> float:
    """SSML's '-15Hz' onto the 0.5..2 multiplier local engines use."""
    try:
        hz = float(pitch.replace("Hz", ""))
    except (ValueError, AttributeError):
        return 1.0
    return max(0.5, min(2.0, 1.0 + hz / 100.0))


def rate_to_multiplier(rate: str) -> float:
    """SSML's '-8%' onto a 0.5..2 multiplier."""
    try:
        percent = float(rate.replace("%", ""))
    except (ValueError, AttributeError):
        return 1.0
    return max(0.5, min(2.0, 1.0 + percent / 100.0))


def rate_to_sapi_words(rate: str) -> int:
    """SAPI5 takes words per minute; its default is about 200."""
    return int(max(80, min(400, 200 * rate_to_multiplier(rate))))


class Sapi5TtsProvider(TtsProvider):
    """Every voice installed in Windows, offline and immediate.

    This is the multi-voice engine: whatever the user has installed — the
    built-in Microsoft voices, or extra language packs — shows up here and
    can be chosen per utterance.
    """

    id = "sapi5"
    label = "أصوات ويندوز (بلا إنترنت)"

    def __init__(self) -> None:
        self._engine = None
        self._lock = threading.Lock()

    def is_available(self) -> bool:
        try:
            import pyttsx3  # noqa: F401
        except ImportError:
            return False
        try:
            self._ensure_engine()
        except TtsError:
            return False
        return True

    def _ensure_engine(self):
        if self._engine is not None:
            return self._engine
        try:
            import pyttsx3

            self._engine = pyttsx3.init()
        except Exception as exc:  # no SAPI, no audio device, driver missing
            raise TtsError(f"تعذّر تهيئة محرّك ويندوز: {type(exc).__name__}") from exc
        return self._engine

    def voices(self) -> list[Voice]:
        try:
            engine = self._ensure_engine()
        except TtsError:
            return []

        found: list[Voice] = []
        for raw in engine.getProperty("voices"):
            languages = getattr(raw, "languages", []) or []
            locale = ""
            if languages:
                first = languages[0]
                locale = first.decode(errors="ignore") if isinstance(first, bytes) else str(first)
            found.append(
                Voice(
                    id=raw.id,
                    name=getattr(raw, "name", raw.id),
                    locale=locale.strip("\x05 "),
                    gender=str(getattr(raw, "gender", "") or ""),
                    provider=self.id,
                )
            )
        # Arabic first, since that is what this assistant speaks.
        return sorted(found, key=lambda v: (not v.is_arabic, v.name))

    def speak(self, text: str, voice: str | None, pitch: str, rate: str) -> None:
        with self._lock:
            engine = self._ensure_engine()
            if voice:
                try:
                    engine.setProperty("voice", voice)
                except Exception:
                    log.warning("voice %s not settable; using the current one", voice)
            engine.setProperty("rate", rate_to_sapi_words(rate))
            # SAPI exposes volume, not pitch, through pyttsx3; pitch is
            # accepted and ignored here rather than silently misapplied.
            engine.say(text)
            engine.runAndWait()

    def stop(self) -> None:
        if self._engine is None:
            return
        try:
            self._engine.stop()
        except Exception:
            log.debug("stop on an idle engine")


class AzureTtsProvider(TtsProvider):
    """Neural voices, including the Algerian male ar-DZ-IsmaelNeural."""

    id = "azure"
    label = "أصوات أزور العصبية"

    ARABIC_MALE: Final = [
        Voice("ar-DZ-IsmaelNeural", "إسماعيل", "ar-DZ", "Male", "azure"),
        Voice("ar-SA-HamedNeural", "حامد", "ar-SA", "Male", "azure"),
        Voice("ar-AE-HamdanNeural", "حمدان", "ar-AE", "Male", "azure"),
        Voice("ar-EG-ShakirNeural", "شاكر", "ar-EG", "Male", "azure"),
        Voice("ar-MA-JamalNeural", "جمال", "ar-MA", "Male", "azure"),
        Voice("ar-TN-HediNeural", "هادي", "ar-TN", "Male", "azure"),
        Voice("ar-LY-OmarNeural", "عمر", "ar-LY", "Male", "azure"),
        Voice("ar-IQ-BasselNeural", "باسل", "ar-IQ", "Male", "azure"),
    ]

    ARABIC_FEMALE: Final = [
        Voice("ar-DZ-AminaNeural", "أمينة", "ar-DZ", "Female", "azure"),
        Voice("ar-EG-SalmaNeural", "سلمى", "ar-EG", "Female", "azure"),
        Voice("ar-SA-ZariyahNeural", "زارية", "ar-SA", "Female", "azure"),
    ]

    def __init__(self, api_key: str | None, region: str | None) -> None:
        self.api_key = api_key
        self.region = region
        self._stop = threading.Event()

    def is_available(self) -> bool:
        return bool(self.api_key and self.region)

    def voices(self) -> list[Voice]:
        return [*self.ARABIC_MALE, *self.ARABIC_FEMALE]

    def synthesize(self, text: str, voice: str | None, pitch: str, rate: str) -> bytes:
        """Return MP3 bytes, so a caller can cache or save instead of play."""
        if not self.is_available():
            raise TtsError("مفتاح أزور أو المنطقة غير مضبوطين")

        import httpx

        chosen = voice or "ar-DZ-IsmaelNeural"
        ssml = build_ssml(text, chosen, pitch, rate)
        url = f"https://{self.region}.tts.speech.microsoft.com/cognitiveservices/v1"

        try:
            response = httpx.post(
                url,
                headers={
                    "Ocp-Apim-Subscription-Key": self.api_key,
                    "Content-Type": "application/ssml+xml",
                    "X-Microsoft-OutputFormat": "audio-24khz-48kbitrate-mono-mp3",
                    "User-Agent": "jarvis",
                },
                content=ssml.encode("utf-8"),
                timeout=20.0,
            )
        except httpx.HTTPError as exc:
            raise TtsError(f"تعذّر الوصول إلى خدمة الصوت: {type(exc).__name__}") from exc

        if response.status_code in (401, 403):
            raise TtsError("خدمة الصوت رفضت المفتاح")
        if response.status_code == 429:
            raise TtsError("تجاوزت حصّة الصوت، حاول بعد قليل")
        if response.status_code >= 400:
            raise TtsError(f"خطأ من خدمة الصوت (HTTP {response.status_code})")
        return response.content

    def speak(self, text: str, voice: str | None, pitch: str, rate: str) -> None:
        audio = self.synthesize(text, voice, pitch, rate)
        play_mp3(audio, self._stop)

    def stop(self) -> None:
        self._stop.set()


def escape_xml(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&apos;")
    )


def build_ssml(text: str, voice: str, pitch: str, rate: str) -> str:
    """The SSML document Azure expects, with the text safely escaped."""
    locale = "-".join(voice.split("-")[:2])
    return (
        f'<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="{locale}">'
        f'<voice name="{voice}">'
        f'<prosody pitch="{pitch}" rate="{rate}">{escape_xml(text)}</prosody>'
        f"</voice></speak>"
    )


def play_mp3(data: bytes, stop_flag: threading.Event | None = None) -> None:
    """Play MP3 bytes through whatever audio backend is installed."""
    stop_flag and stop_flag.clear()
    try:
        import miniaudio
        import sounddevice as sd
    except ImportError as exc:
        raise TtsError("حزم تشغيل الصوت غير مثبّتة") from exc

    try:
        decoded = miniaudio.decode(data)
    except Exception as exc:
        raise TtsError("تعذّر فكّ ترميز الصوت") from exc

    import numpy as np

    samples = np.array(decoded.samples, dtype=np.int16).reshape(-1, decoded.nchannels)
    sd.play(samples, decoded.sample_rate)
    try:
        while sd.get_stream().active:
            if stop_flag is not None and stop_flag.is_set():
                sd.stop()
                return
            sd.sleep(50)
    except Exception:
        sd.wait()


def pick_provider(providers: list[TtsProvider], preferred_id: str | None = None) -> TtsProvider | None:
    """First available provider, preferring the user's choice."""
    if preferred_id:
        for provider in providers:
            if provider.id == preferred_id and provider.is_available():
                return provider
    for provider in providers:
        if provider.is_available():
            return provider
    return None
