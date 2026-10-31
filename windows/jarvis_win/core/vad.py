"""Deciding when the user has finished speaking.

Transcribing only after the sentence ends is what makes dictation feel
fast: one transcription of a complete utterance beats a stream of partial
guesses that keep rewriting themselves.

The state machine here is pure — it consumes "was this frame speech?"
booleans and emits events. That keeps the timing rules testable without a
microphone, which is where endpointing bugs actually live.
"""

from __future__ import annotations

from array import array
from dataclasses import dataclass
from enum import Enum, auto
from math import log10, sqrt
from typing import Final

FRAME_MS: Final = 20  # webrtcvad accepts 10, 20 or 30 ms frames
SAMPLE_RATE: Final = 16_000


class Event(Enum):
    NOTHING = auto()
    SPEECH_STARTED = auto()
    SPEECH_ENDED = auto()
    #: Speech began but was too short to be an utterance (a cough, a knock).
    TOO_SHORT = auto()
    #: The speaker ran past the cap; cut here rather than listen forever.
    MAX_DURATION = auto()


class State(Enum):
    IDLE = auto()
    SPEAKING = auto()
    TRAILING = auto()  # in speech, but currently silent


@dataclass
class EndpointConfig:
    """Timing rules, in milliseconds."""

    #: Silence after speech that ends the utterance. Shorter feels snappier
    #: but clips people who pause mid-sentence; 700 ms is a usable middle.
    endpoint_silence_ms: int = 700
    #: Speech shorter than this is discarded as noise.
    min_speech_ms: int = 250
    #: Hard cap on one utterance.
    max_utterance_ms: int = 20_000
    #: Consecutive speech frames needed to declare a start, so a single
    #: noisy frame does not trigger it.
    start_frames: int = 3


class Endpointer:
    """Turns a stream of per-frame speech flags into utterance boundaries."""

    def __init__(self, config: EndpointConfig | None = None, frame_ms: int = FRAME_MS) -> None:
        self.config = config or EndpointConfig()
        self.frame_ms = frame_ms
        self.state = State.IDLE
        self._speech_ms = 0
        self._silence_ms = 0
        self._run = 0

    def reset(self) -> None:
        self.state = State.IDLE
        self._speech_ms = 0
        self._silence_ms = 0
        self._run = 0

    @property
    def speech_ms(self) -> int:
        """How much speech the current utterance holds."""
        return self._speech_ms

    def push(self, is_speech: bool) -> Event:
        """Feed one frame. Returns what just happened."""
        if self.state is State.IDLE:
            return self._idle(is_speech)
        if self.state is State.SPEAKING:
            return self._speaking(is_speech)
        return self._trailing(is_speech)

    # ------------------------------------------------------------ states

    def _idle(self, is_speech: bool) -> Event:
        if not is_speech:
            self._run = 0
            return Event.NOTHING

        self._run += 1
        if self._run < self.config.start_frames:
            return Event.NOTHING

        self.state = State.SPEAKING
        # Count the frames that proved it was speech, not just this one.
        self._speech_ms = self._run * self.frame_ms
        self._silence_ms = 0
        self._run = 0
        return Event.SPEECH_STARTED

    def _speaking(self, is_speech: bool) -> Event:
        self._speech_ms += self.frame_ms
        if self._speech_ms >= self.config.max_utterance_ms:
            self.reset()
            return Event.MAX_DURATION
        if is_speech:
            return Event.NOTHING

        self.state = State.TRAILING
        self._silence_ms = self.frame_ms
        return Event.NOTHING

    def _trailing(self, is_speech: bool) -> Event:
        if is_speech:
            # A pause inside a sentence: back to speaking, silence forgiven.
            self.state = State.SPEAKING
            self._speech_ms += self.frame_ms
            self._silence_ms = 0
            return Event.NOTHING

        self._silence_ms += self.frame_ms
        # Trailing silence still counts toward the cap, or a quiet speaker
        # could hold the session open indefinitely.
        if self._speech_ms + self._silence_ms >= self.config.max_utterance_ms:
            spoke_enough = self._speech_ms >= self.config.min_speech_ms
            self.reset()
            return Event.MAX_DURATION if spoke_enough else Event.TOO_SHORT

        if self._silence_ms < self.config.endpoint_silence_ms:
            return Event.NOTHING

        spoke_enough = self._speech_ms >= self.config.min_speech_ms
        self.reset()
        return Event.SPEECH_ENDED if spoke_enough else Event.TOO_SHORT


def frame_size(frame_ms: int = FRAME_MS, sample_rate: int = SAMPLE_RATE) -> int:
    """Samples per frame, which webrtcvad requires exactly."""
    return int(sample_rate * frame_ms / 1000)


# --------------------------------------------------------- speech detection


class SpeechDetector:
    """Decides whether one frame contains speech."""

    name = "detector"

    def is_speech(self, frame: bytes, sample_rate: int = SAMPLE_RATE) -> bool:
        raise NotImplementedError


class EnergyDetector(SpeechDetector):
    """Loudness above an adaptive noise floor.

    Dependency-free, so it always packages and always runs. It tracks the
    room's own noise and treats a frame as speech when it rises clearly
    above it, which handles a noisy room better than a fixed threshold and
    avoids a native extension that PyInstaller has to be taught about.

    Less discriminating than a trained VAD — it will call a door slam
    speech — but the endpointer's minimum-duration rule discards those.
    """

    name = "energy"

    def __init__(self, margin_db: float = 10.0, floor_db: float = -60.0) -> None:
        self.margin_db = margin_db
        self.floor_db = floor_db
        self._noise_db: float | None = None

    def reset(self) -> None:
        self._noise_db = None

    @property
    def noise_floor_db(self) -> float:
        return self._noise_db if self._noise_db is not None else self.floor_db

    def is_speech(self, frame: bytes, sample_rate: int = SAMPLE_RATE) -> bool:
        # Stdlib only: a 320-sample RMS does not need numpy, and keeping it
        # dependency-free means the detector cannot fail to package.
        if len(frame) < 2:
            return False
        block = array("h")
        block.frombytes(frame[: len(frame) - (len(frame) % 2)])
        if not block:
            return False

        rms = sqrt(sum(float(v) * v for v in block) / len(block))
        db = self.floor_db if rms < 1.0 else 20.0 * log10(rms / 32768.0)

        if self._noise_db is None:
            self._noise_db = db
            return False

        speech = db > self._noise_db + self.margin_db
        if not speech:
            # Adapt only to quiet frames, or loud speech would drag the
            # floor up until nothing counted as speech any more.
            self._noise_db += (db - self._noise_db) * 0.05
            self._noise_db = max(self._noise_db, self.floor_db)
        return speech


class WebrtcDetector(SpeechDetector):
    """Google's WebRTC VAD, when it is installed. Better at rejecting noise."""

    name = "webrtc"

    def __init__(self, aggressiveness: int = 2) -> None:
        import webrtcvad

        self._vad = webrtcvad.Vad(aggressiveness)

    def is_speech(self, frame: bytes, sample_rate: int = SAMPLE_RATE) -> bool:
        try:
            return self._vad.is_speech(frame, sample_rate)
        except Exception:
            return False


def make_detector() -> SpeechDetector:
    """WebRTC when available, otherwise the built-in energy detector."""
    try:
        return WebrtcDetector()
    except Exception:
        return EnergyDetector()
