"""Deciding when the user has finished speaking.

Transcribing only after the sentence ends is what makes dictation feel
fast: one transcription of a complete utterance beats a stream of partial
guesses that keep rewriting themselves.

The state machine here is pure — it consumes "was this frame speech?"
booleans and emits events. That keeps the timing rules testable without a
microphone, which is where endpointing bugs actually live.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, auto
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
