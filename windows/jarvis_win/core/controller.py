"""Wires the window to the microphone, the voice, and the tools.

Keeps Qt out of the core modules and audio off the UI thread. Everything
that arrives from a background thread is marshalled back through a Qt
signal before it touches a widget.
"""

from __future__ import annotations

import logging
import threading
from pathlib import Path

from PySide6.QtCore import QObject, Signal, Slot

from .config import Settings, get_azure_key, get_openrouter_key
from .files import DeletionPlan, DeletionResult, execute
from .intent import route
from .listener import ListenerCallbacks, VoiceListener
from .state import AssistantState
from .stt import AzureSttProvider, SttProvider, Transcript, WhisperSttProvider
from .stt import pick_provider as pick_stt
from .tools import ToolRegistry, ToolResult
from .tts import AzureTtsProvider, Sapi5TtsProvider, TtsProvider, Voice
from .tts import pick_provider as pick_tts

log = logging.getLogger(__name__)


class Controller(QObject):
    """Owns the moving parts; the window only renders what it emits."""

    state_changed = Signal(object)  # AssistantState
    level_changed = Signal(float)
    text_shown = Signal(str)
    error_shown = Signal(str)
    #: (summary, plan) — the window must confirm before anything is deleted.
    approval_requested = Signal(str, object)

    def __init__(self, settings: Settings, parent: QObject | None = None) -> None:
        super().__init__(parent)
        self.settings = settings
        self.registry = ToolRegistry()

        # The Azure key lives in the OS credential store, never in settings.
        azure_key = get_azure_key()
        self._stt_providers: list[SttProvider] = [
            WhisperSttProvider(model_size=settings.whisper_model),
            AzureSttProvider(azure_key, settings.azure_region),
        ]
        self._tts_providers: list[TtsProvider] = [
            Sapi5TtsProvider(),
            AzureTtsProvider(azure_key, settings.azure_region),
        ]

        self._listener: VoiceListener | None = None
        self._speak_thread: threading.Thread | None = None
        self._state = AssistantState.IDLE

    # ------------------------------------------------------------- state

    def _go(self, state: AssistantState) -> None:
        self._state = state
        self.state_changed.emit(state)

    # --------------------------------------------------------- dictation

    def available_voices(self) -> list[Voice]:
        """Every voice the machine can actually speak with, both engines."""
        found: list[Voice] = []
        for provider in self._tts_providers:
            if provider.is_available():
                found.extend(provider.voices())
        return found

    def stt_available(self) -> SttProvider | None:
        return pick_stt(self._stt_providers, self.settings.stt_provider)

    @Slot(bool)
    def set_listening(self, listening: bool) -> None:
        if not listening:
            self._stop_listening()
            return

        provider = self.stt_available()
        if provider is None:
            self.error_shown.emit(
                "لا يوجد محرّك تعرّف متاح. ثبّت faster-whisper أو اضبط مفتاح أزور."
            )
            self._go(AssistantState.IDLE)
            return

        callbacks = ListenerCallbacks(
            on_level=self.level_changed.emit,
            on_speech_started=lambda: self._go(AssistantState.LISTENING),
            on_transcribing=lambda: self._go(AssistantState.UNDERSTANDING),
            on_transcript=self._on_transcript,
            on_error=self.error_shown.emit,
        )
        self._listener = VoiceListener(provider, callbacks)
        self._listener.start()
        self._go(AssistantState.LISTENING)

    def _stop_listening(self) -> None:
        if self._listener is not None:
            self._listener.stop()
            self._listener = None
        self.level_changed.emit(0.0)
        if self._state in (AssistantState.LISTENING, AssistantState.UNDERSTANDING):
            self._go(AssistantState.IDLE)

    def _on_transcript(self, transcript: Transcript) -> None:
        """Arrives on the listener thread; signals marshal it back to the UI."""
        self.text_shown.emit(transcript.text)
        self.handle_utterance(transcript.text)

    # ----------------------------------------------------------- command

    @Slot(str)
    def handle_utterance(self, text: str) -> None:
        if not text.strip():
            return

        intent = route(text)
        if intent is None:
            # Without the model wired in, say so rather than look broken.
            if not get_openrouter_key():
                self.error_shown.emit("هذا الطلب يحتاج نموذجًا — أضف مفتاح OpenRouter من الإعدادات")
                self._go(AssistantState.ERROR)
            else:
                self._go(AssistantState.THINKING)
            return

        tool = self.registry.find(intent.tool)
        if tool is None:
            self.error_shown.emit(f"الأداة '{intent.tool}' غير متاحة على الحاسوب")
            self._go(AssistantState.ERROR)
            return

        self._go(AssistantState.EXECUTING)
        result: ToolResult = tool.run(dict(intent.args))
        self._finish_tool(result)

    def _finish_tool(self, result: ToolResult) -> None:
        if result.pending is not None:
            # Deletion stops here until the user approves the exact list.
            self.approval_requested.emit(result.summary, result.pending)
            self._go(AssistantState.IDLE)
            return

        self.text_shown.emit(result.summary)
        self._go(AssistantState.SUCCESS if result.ok else AssistantState.ERROR)
        if result.ok and self.settings.auto_speak:
            self.speak(result.summary)

    @Slot(object)
    def approve_deletion(self, plan: DeletionPlan) -> None:
        """Run a plan the user approved, and report exactly what happened."""
        audit = Path(self.settings.path()).parent / "deletions.tsv"
        try:
            outcome: DeletionResult = execute(plan, audit_path=audit)
        except Exception as exc:
            self.error_shown.emit(str(exc))
            self._go(AssistantState.ERROR)
            return

        if outcome.failed and not outcome.deleted:
            self.error_shown.emit(f"تعذّر حذف {len(outcome.failed)} عنصرًا")
            self._go(AssistantState.ERROR)
            return

        message = f"حُذف {len(outcome.deleted)} عنصرًا"
        if outcome.failed:
            message += f"، وتعذّر حذف {len(outcome.failed)}"
        if not plan.permanent:
            message += " (في سلّة المحذوفات)"
        self.text_shown.emit(message)
        self._go(AssistantState.SUCCESS)
        if self.settings.auto_speak:
            self.speak(message)

    @Slot()
    def reject_deletion(self) -> None:
        self.text_shown.emit("أُلغي الحذف")
        self._go(AssistantState.IDLE)

    # ------------------------------------------------------------- voice

    def speak(self, text: str) -> None:
        provider = pick_tts(self._tts_providers, self.settings.tts_provider)
        if provider is None:
            self.error_shown.emit("لا يوجد محرّك نطق متاح")
            return

        def run() -> None:
            self._go(AssistantState.SPEAKING)
            try:
                provider.speak(text, self.settings.voice, self.settings.voice_pitch, self.settings.voice_rate)
            except Exception as exc:
                self.error_shown.emit(str(exc))
            finally:
                self._go(AssistantState.IDLE)

        self._speak_thread = threading.Thread(target=run, name="jarvis-speak", daemon=True)
        self._speak_thread.start()

    @Slot()
    def cancel(self) -> None:
        """Always available: an action the user cannot abort is a bug."""
        self._stop_listening()
        for provider in self._tts_providers:
            try:
                provider.stop()
            except Exception:
                log.debug("stop on an idle provider")
        self._go(AssistantState.IDLE)

    def shutdown(self) -> None:
        self.cancel()
