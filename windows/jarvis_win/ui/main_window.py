"""Main assistant window."""

from __future__ import annotations

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtGui import QFont, QKeySequence, QShortcut
from PySide6.QtWidgets import (
    QComboBox,
    QFrame,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from jarvis_win.core.config import Settings
from jarvis_win.core.intent import route
from jarvis_win.core.state import AssistantState, StateMachine
from jarvis_win.ui.theme import PALETTE, STATE_COLOR, stylesheet
from jarvis_win.ui.visualizer import VoiceVisualizer


class MainWindow(QWidget):
    """The assistant surface: identity, state, visualiser, input, controls.

    This window owns presentation only. It emits intent and lets the
    controller decide what to run, so no UI code performs actions.
    """

    #: Emitted when the user submits text or finishes dictating.
    utterance_submitted = Signal(str)
    #: Emitted when the user asks to stop whatever is running.
    cancel_requested = Signal()
    #: Emitted when the user toggles the microphone.
    mic_toggled = Signal(bool)
    settings_requested = Signal()
    #: Emitted when the user picks a different voice.
    voice_changed = Signal(str)

    def __init__(self, settings: Settings, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.settings = settings
        self.machine = StateMachine()
        self.machine.subscribe(self._on_state_changed)
        self._mic_on = False

        self.setWindowTitle("JARVIS")
        self.setMinimumSize(560, 520)
        self.setStyleSheet(stylesheet())
        if settings.language == "ar":
            self.setLayoutDirection(Qt.LayoutDirection.RightToLeft)

        self._build()
        self._apply_state(AssistantState.IDLE)

        QShortcut(QKeySequence("Esc"), self, activated=self._on_cancel)

    # -------------------------------------------------------------- layout

    def _build(self) -> None:
        root = QVBoxLayout(self)
        root.setContentsMargins(26, 22, 26, 22)
        root.setSpacing(18)

        # --- header -------------------------------------------------------
        header = QHBoxLayout()
        title = QLabel("J.A.R.V.I.S")
        title.setObjectName("title")
        title.setFont(QFont(title.font().family(), 20, QFont.Weight.DemiBold))
        header.addWidget(title)
        header.addStretch(1)

        self.connection_dot = QLabel("●")
        self.connection_dot.setToolTip("حالة الاتصال بالخادم")
        header.addWidget(self.connection_dot)

        self.voice_picker = QComboBox()
        self.voice_picker.setToolTip("الصوت")
        self.voice_picker.setMinimumWidth(170)
        self.voice_picker.currentIndexChanged.connect(self._on_voice_changed)
        header.addWidget(self.voice_picker)

        settings_btn = QPushButton("الإعدادات")
        settings_btn.clicked.connect(self.settings_requested.emit)
        header.addWidget(settings_btn)
        root.addLayout(header)

        # --- visualiser panel --------------------------------------------
        panel = QFrame()
        panel.setObjectName("panel")
        panel_layout = QVBoxLayout(panel)
        panel_layout.setContentsMargins(20, 18, 20, 18)
        panel_layout.setSpacing(12)

        self.visualizer = VoiceVisualizer()
        self.visualizer.set_reduced_motion(self.settings.motion_intensity != "full")
        panel_layout.addWidget(self.visualizer)

        self.status_label = QLabel()
        self.status_label.setObjectName("status")
        self.status_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        panel_layout.addWidget(self.status_label)
        root.addWidget(panel)

        # --- response -----------------------------------------------------
        self.transcript = QLabel("")
        self.transcript.setObjectName("transcript")
        self.transcript.setWordWrap(True)
        self.transcript.setAlignment(
            Qt.AlignmentFlag.AlignTop
            | (Qt.AlignmentFlag.AlignRight if self.settings.language == "ar" else Qt.AlignmentFlag.AlignLeft)
        )
        self.transcript.setMinimumHeight(90)
        root.addWidget(self.transcript, 1)

        # --- input --------------------------------------------------------
        input_row = QHBoxLayout()
        self.input = QLineEdit()
        self.input.setPlaceholderText("اكتب أمرك، أو اضغط على الميكروفون")
        self.input.returnPressed.connect(self._on_submit)
        input_row.addWidget(self.input, 1)

        self.mic_btn = QPushButton("🎙 تحدّث")
        self.mic_btn.setObjectName("primary")
        self.mic_btn.setCheckable(True)
        self.mic_btn.clicked.connect(self._on_mic)
        input_row.addWidget(self.mic_btn)

        self.cancel_btn = QPushButton("إيقاف")
        self.cancel_btn.setObjectName("danger")
        self.cancel_btn.clicked.connect(self._on_cancel)
        self.cancel_btn.setEnabled(False)
        input_row.addWidget(self.cancel_btn)
        root.addLayout(input_row)

        hint = QLabel("Ctrl+Alt+J للتفعيل من أي مكان · Esc للإيقاف")
        hint.setObjectName("status")
        hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
        root.addWidget(hint)

    # --------------------------------------------------------------- state

    def _on_state_changed(self, previous: AssistantState, current: AssistantState) -> None:
        del previous
        self._apply_state(current)

    def _apply_state(self, state: AssistantState) -> None:
        self.status_label.setText(self.machine.status_ar)
        self.visualizer.set_state(state)
        self.cancel_btn.setEnabled(self.machine.busy)
        colour = STATE_COLOR[state]
        self.status_label.setStyleSheet(f"color: {colour};")

        connected = state is not AssistantState.DISCONNECTED
        self.connection_dot.setStyleSheet(
            f"color: {PALETTE.success if connected else PALETTE.error}; font-size: 16px;"
        )

        # Success and error are momentary; drift back to idle on their own.
        if state in {AssistantState.SUCCESS, AssistantState.ERROR}:
            QTimer.singleShot(2200, self._settle)

    def _settle(self) -> None:
        if self.machine.state in {AssistantState.SUCCESS, AssistantState.ERROR}:
            self.machine.go(AssistantState.IDLE)

    def set_state(self, state: AssistantState) -> None:
        """Entry point for the controller."""
        self.machine.go(state)

    def set_connected(self, connected: bool) -> None:
        if not connected:
            self.machine.go(AssistantState.DISCONNECTED)
        elif self.machine.state is AssistantState.DISCONNECTED:
            self.machine.go(AssistantState.IDLE)

    def show_text(self, text: str) -> None:
        self.transcript.setStyleSheet("")
        self.transcript.setText(text)

    def show_error(self, message: str) -> None:
        self.transcript.setStyleSheet(f"color: {PALETTE.error};")
        self.transcript.setText(message)

    def populate_voices(self, voices) -> None:
        """Fill the picker with the voices this machine can actually use."""
        self.voice_picker.blockSignals(True)
        self.voice_picker.clear()
        if not voices:
            self.voice_picker.addItem("لا توجد أصوات متاحة", None)
            self.voice_picker.setEnabled(False)
        else:
            self.voice_picker.setEnabled(True)
            for voice in voices:
                self.voice_picker.addItem(voice.display, voice.id)
            index = self.voice_picker.findData(self.settings.voice)
            if index >= 0:
                self.voice_picker.setCurrentIndex(index)
        self.voice_picker.blockSignals(False)

    def _on_voice_changed(self, index: int) -> None:
        voice_id = self.voice_picker.itemData(index)
        if not voice_id:
            return
        self.settings.voice = voice_id
        self.settings.save()
        self.voice_changed.emit(voice_id)

    def append_text(self, chunk: str) -> None:
        """Append streamed model output without reflowing the whole label."""
        self.transcript.setText(self.transcript.text() + chunk)

    def push_amplitude(self, level: float) -> None:
        self.visualizer.push_amplitude(level)

    # ------------------------------------------------------------- actions

    def _on_submit(self) -> None:
        text = self.input.text().strip()
        if not text:
            return
        self.input.clear()
        self.transcript.setText("")
        # Show the local routing decision immediately; the controller still
        # owns execution and confirmation.
        intent = route(text)
        self.machine.go(AssistantState.EXECUTING if intent else AssistantState.THINKING)
        self.utterance_submitted.emit(text)

    def _on_mic(self, checked: bool) -> None:
        self._mic_on = checked
        self.mic_btn.setText("⏹ إيقاف الاستماع" if checked else "🎙 تحدّث")
        if checked and self.machine.can_go(AssistantState.LISTENING):
            self.machine.go(AssistantState.LISTENING)
        elif not checked and self.machine.state is AssistantState.LISTENING:
            self.machine.go(AssistantState.IDLE)
        self.mic_toggled.emit(checked)

    def _on_cancel(self) -> None:
        if not self.machine.busy:
            return
        self.cancel_requested.emit()
        self.mic_btn.setChecked(False)
        self.mic_btn.setText("🎙 تحدّث")
        self.machine.go(AssistantState.IDLE)
