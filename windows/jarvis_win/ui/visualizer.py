"""Voice visualiser.

A row of bars driven by real microphone or playback amplitude, with eased
motion and a soft glow. When no audio is flowing it breathes slowly rather
than freezing, so the app never looks dead.

Deliberately not a holographic sphere or radar sweep — spec section 6
rules those out.
"""

from __future__ import annotations

import math
import time

from PySide6.QtCore import QPointF, Qt, QTimer
from PySide6.QtGui import QColor, QLinearGradient, QPainter, QPainterPath, QPen
from PySide6.QtWidgets import QWidget

from jarvis_win.core.state import AssistantState
from jarvis_win.ui.theme import PALETTE, STATE_COLOR

BAR_COUNT = 28
FPS = 60


class VoiceVisualizer(QWidget):
    """Amplitude-reactive bars. Call :meth:`push_amplitude` from audio code."""

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setMinimumHeight(130)
        self._state = AssistantState.IDLE
        self._levels = [0.0] * BAR_COUNT
        self._targets = [0.0] * BAR_COUNT
        self._amplitude = 0.0
        self._t0 = time.monotonic()
        self._reduced_motion = False

        self._timer = QTimer(self)
        self._timer.timeout.connect(self._tick)
        self._timer.start(int(1000 / FPS))

    # ------------------------------------------------------------- control

    def set_state(self, state: AssistantState) -> None:
        self._state = state
        self.update()

    def set_reduced_motion(self, reduced: bool) -> None:
        """Honour the user's motion preference (spec section 6 / 17)."""
        self._reduced_motion = reduced
        self._timer.setInterval(int(1000 / (12 if reduced else FPS)))

    def push_amplitude(self, amplitude: float) -> None:
        """Feed a real RMS level in 0..1 from the audio pipeline."""
        self._amplitude = max(0.0, min(1.0, amplitude))

    # ---------------------------------------------------------- animation

    def _tick(self) -> None:
        elapsed = time.monotonic() - self._t0
        active = self._state in {
            AssistantState.LISTENING,
            AssistantState.SPEAKING,
            AssistantState.THINKING,
            AssistantState.EXECUTING,
        }

        for i in range(BAR_COUNT):
            # Bell curve so the centre is tallest.
            centre = 1.0 - abs(i - (BAR_COUNT - 1) / 2) / ((BAR_COUNT - 1) / 2)
            shape = centre**1.6

            if active and self._amplitude > 0.01:
                jitter = 0.5 + 0.5 * math.sin(elapsed * 9 + i * 0.7)
                self._targets[i] = self._amplitude * shape * jitter
            elif active:
                # Working but silent: a travelling wave, not a frozen bar.
                self._targets[i] = 0.18 * shape * (0.5 + 0.5 * math.sin(elapsed * 3 - i * 0.35))
            else:
                # Idle breathing.
                self._targets[i] = 0.06 * shape * (0.6 + 0.4 * math.sin(elapsed * 1.2 - i * 0.2))

        # Asymmetric easing: rise fast, fall slow — reads as responsive.
        for i, target in enumerate(self._targets):
            current = self._levels[i]
            k = 0.45 if target > current else 0.12
            self._levels[i] = current + (target - current) * k

        self.update()

    # ------------------------------------------------------------ painting

    def paintEvent(self, event) -> None:  # noqa: N802 - Qt naming
        del event
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        width, height = self.width(), self.height()
        mid = height / 2
        colour = QColor(STATE_COLOR[self._state])

        gap = 4.0
        bar_w = max(2.0, (width - gap * (BAR_COUNT - 1)) / BAR_COUNT)

        # Glow pass: same bars, wider and translucent, drawn underneath.
        if not self._reduced_motion:
            glow = QColor(colour)
            glow.setAlpha(45)
            painter.setPen(QPen(glow, bar_w * 2.1, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
            for i, level in enumerate(self._levels):
                x = i * (bar_w + gap) + bar_w / 2
                h = max(2.0, level * (height * 0.8))
                painter.drawLine(QPointF(x, mid - h / 2), QPointF(x, mid + h / 2))

        gradient = QLinearGradient(0, 0, 0, height)
        gradient.setColorAt(0.0, colour.lighter(135))
        gradient.setColorAt(0.5, colour)
        gradient.setColorAt(1.0, colour.darker(140))

        pen = QPen(gradient, bar_w, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap)
        painter.setPen(pen)
        for i, level in enumerate(self._levels):
            x = i * (bar_w + gap) + bar_w / 2
            h = max(2.0, level * (height * 0.8))
            painter.drawLine(QPointF(x, mid - h / 2), QPointF(x, mid + h / 2))

        # Hairline baseline for grounding.
        base = QColor(PALETTE.border)
        base.setAlpha(120)
        painter.setPen(QPen(base, 1))
        path = QPainterPath()
        path.moveTo(0, mid)
        path.lineTo(width, mid)
        painter.drawPath(path)
        painter.end()
