"""Microphone capture, endpointing, and transcription on utterance end.

Runs on its own thread and reports through callbacks, so the Qt interface
never blocks on audio or on a model.

Only the current utterance is held in memory, and it is dropped as soon as
it has been transcribed: nothing is written to disk.
"""

from __future__ import annotations

import logging
import threading
from collections import deque
from dataclasses import dataclass
from typing import Callable

from .stt import SttError, SttProvider, Transcript
from .vad import SAMPLE_RATE, EndpointConfig, Endpointer, Event, frame_size

log = logging.getLogger(__name__)

FRAME_MS = 20
#: Audio kept from before speech was confirmed, so the first syllable
#: survives the three frames the endpointer needs to be sure.
PREROLL_FRAMES = 10


@dataclass
class ListenerCallbacks:
    on_level: Callable[[float], None] | None = None
    on_speech_started: Callable[[], None] | None = None
    on_transcribing: Callable[[], None] | None = None
    on_transcript: Callable[[Transcript], None] | None = None
    on_error: Callable[[str], None] | None = None


class VoiceListener:
    """Listens until stopped, transcribing each complete utterance."""

    def __init__(
        self,
        stt: SttProvider,
        callbacks: ListenerCallbacks | None = None,
        config: EndpointConfig | None = None,
    ) -> None:
        self.stt = stt
        self.callbacks = callbacks or ListenerCallbacks()
        self.config = config or EndpointConfig()
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()

    @property
    def is_running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> None:
        if self.is_running:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="jarvis-listener", daemon=True)
        self._thread.start()

    def stop(self, timeout: float = 2.0) -> None:
        self._stop.set()
        thread = self._thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=timeout)
        self._thread = None

    # ------------------------------------------------------------ internals

    def _emit_error(self, message: str) -> None:
        log.warning("listener: %s", message)
        if self.callbacks.on_error:
            self.callbacks.on_error(message)

    def _run(self) -> None:
        try:
            import sounddevice as sd
            import webrtcvad
        except ImportError:
            self._emit_error("حزم الميكروفون غير مثبّتة (sounddevice, webrtcvad)")
            return

        import numpy as np

        vad = webrtcvad.Vad(2)  # 0 permissive … 3 aggressive
        endpointer = Endpointer(self.config, frame_ms=FRAME_MS)
        samples_per_frame = frame_size(FRAME_MS, SAMPLE_RATE)

        preroll: deque[bytes] = deque(maxlen=PREROLL_FRAMES)
        utterance: list[bytes] = []
        collecting = False

        try:
            stream = sd.RawInputStream(
                samplerate=SAMPLE_RATE,
                blocksize=samples_per_frame,
                dtype="int16",
                channels=1,
            )
        except Exception as exc:
            self._emit_error(f"تعذّر فتح الميكروفون: {type(exc).__name__}")
            return

        with stream:
            while not self._stop.is_set():
                try:
                    raw, overflowed = stream.read(samples_per_frame)
                except Exception as exc:
                    self._emit_error(f"انقطع الميكروفون: {type(exc).__name__}")
                    return
                if overflowed:
                    log.debug("input overflow")

                frame = bytes(raw)
                if len(frame) < samples_per_frame * 2:
                    continue

                if self.callbacks.on_level:
                    block = np.frombuffer(frame, dtype=np.int16).astype(np.float32)
                    rms = float(np.sqrt(np.mean(block * block))) / 32768.0
                    self.callbacks.on_level(min(1.0, rms * 6.0))

                try:
                    is_speech = vad.is_speech(frame, SAMPLE_RATE)
                except Exception:
                    is_speech = False

                event = endpointer.push(is_speech)

                if collecting:
                    utterance.append(frame)
                else:
                    preroll.append(frame)

                if event is Event.SPEECH_STARTED:
                    collecting = True
                    # Keep the audio that preceded the decision.
                    utterance = list(preroll)
                    preroll.clear()
                    if self.callbacks.on_speech_started:
                        self.callbacks.on_speech_started()

                elif event in (Event.SPEECH_ENDED, Event.MAX_DURATION):
                    audio = b"".join(utterance)
                    utterance = []
                    collecting = False
                    self._transcribe(audio)

                elif event is Event.TOO_SHORT:
                    utterance = []
                    collecting = False

    def _transcribe(self, pcm: bytes) -> None:
        if not pcm:
            return
        if self.callbacks.on_transcribing:
            self.callbacks.on_transcribing()
        try:
            transcript = self.stt.transcribe(pcm, SAMPLE_RATE)
        except SttError as exc:
            self._emit_error(str(exc))
            return
        except Exception as exc:
            self._emit_error(f"فشل التعرّف: {type(exc).__name__}")
            return

        if transcript.is_empty:
            return
        if self.callbacks.on_transcript:
            self.callbacks.on_transcript(transcript)
