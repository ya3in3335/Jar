"""The assistant orb, drawn with maths rather than any image asset.

The same construction as the Android app, so both surfaces look like one
product: nested closed harmonic curves inside a glassy sphere, their
distortion driven by real audio amplitude, under a rim light, a specular
arc, a bloomed ring and a sweeping dot ring.

Nothing here is a bitmap, so it stays sharp at any size and on any DPI.
"""

from __future__ import annotations

import math
import time

from PySide6.QtCore import QPointF, QRectF, Qt, QTimer
from PySide6.QtGui import QColor, QPainter, QPainterPath, QPen, QRadialGradient
from PySide6.QtWidgets import QWidget

from jarvis_win.core.state import AssistantState
from jarvis_win.ui.theme import STATE_COLOR

LAYERS = 18
POINTS = 96
DOTS = 72
FPS = 60


def _mix(a: QColor, b: QColor, t: float) -> QColor:
    """Blend two colours, for highlights and shadows."""
    return QColor(
        int(a.red() + (b.red() - a.red()) * t),
        int(a.green() + (b.green() - a.green()) * t),
        int(a.blue() + (b.blue() - a.blue()) * t),
    )


class JarvisOrb(QWidget):
    """Amplitude-reactive orb. Feed it with :meth:`push_amplitude`."""

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setMinimumSize(260, 260)
        self.setAttribute(Qt.WidgetAttribute.WA_OpaquePaintEvent, False)

        self._state = AssistantState.IDLE
        self._target = 0.0
        self._energy = 0.0
        self._t0 = time.monotonic()
        self._reduced = False

        self._timer = QTimer(self)
        self._timer.timeout.connect(self._tick)
        self._timer.start(int(1000 / FPS))

    # ------------------------------------------------------------- control

    def set_state(self, state: AssistantState) -> None:
        self._state = state
        self.update()

    def set_reduced_motion(self, reduced: bool) -> None:
        self._reduced = reduced
        self._timer.setInterval(int(1000 / (15 if reduced else FPS)))

    def push_amplitude(self, level: float) -> None:
        self._target = max(0.0, min(1.0, float(level)))

    # ----------------------------------------------------------- animation

    def _tick(self) -> None:
        # Fast attack, slow release: the orb snaps to a syllable and settles
        # gently instead of flickering on a spiky microphone.
        k = 0.35 if self._target > self._energy else 0.08
        self._energy += (self._target - self._energy) * k
        self.update()

    @property
    def _active(self) -> bool:
        return self._state in {
            AssistantState.LISTENING,
            AssistantState.SPEAKING,
            AssistantState.THINKING,
            AssistantState.EXECUTING,
        }

    # ------------------------------------------------------------- painting

    def paintEvent(self, event) -> None:  # noqa: N802 - Qt naming
        del event
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        centre = QPointF(self.width() / 2, self.height() / 2)
        max_r = min(self.width(), self.height()) / 2
        t = time.monotonic() - self._t0
        accent = QColor(STATE_COLOR[self._state])

        breathe = 1 + 0.02 * math.sin(t * 1.1) + self._energy * 0.05
        ring_r = max_r * 0.78 * breathe
        body_r = ring_r * 0.90

        self._draw_glow(painter, centre, ring_r, accent)
        self._draw_body(painter, centre, body_r, accent)

        painter.save()
        clip = QPainterPath()
        clip.addEllipse(centre, body_r, body_r)
        painter.setClipPath(clip)
        self._draw_flow(painter, centre, body_r, accent, t)
        painter.restore()

        self._draw_rim(painter, centre, body_r, accent)
        self._draw_ring(painter, centre, ring_r, accent)
        self._draw_dots(painter, centre, ring_r * 1.10, accent, t)
        painter.end()

    def _draw_glow(self, painter: QPainter, centre: QPointF, radius: float, accent: QColor) -> None:
        reach = radius * (1.75 + self._energy * 0.35)
        strength = (0.30 if self._active else 0.18) + self._energy * 0.22

        gradient = QRadialGradient(centre, reach)
        inner = QColor(accent)
        inner.setAlphaF(min(1.0, strength * 0.55))
        mid = QColor(accent)
        mid.setAlphaF(min(1.0, strength * 0.22))
        outer = QColor(accent)
        outer.setAlpha(0)
        gradient.setColorAt(0.0, inner)
        gradient.setColorAt(0.45, mid)
        gradient.setColorAt(1.0, outer)

        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(gradient)
        painter.drawEllipse(centre, reach, reach)

    def _draw_body(self, painter: QPainter, centre: QPointF, radius: float, accent: QColor) -> None:
        focus = QPointF(centre.x() - radius * 0.2, centre.y() - radius * 0.25)
        gradient = QRadialGradient(focus, radius * 1.4)
        gradient.setColorAt(0.0, _mix(QColor("#071523"), accent, 0.10))
        gradient.setColorAt(0.70, QColor("#040A14"))
        gradient.setColorAt(1.0, QColor("#02060C"))

        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(gradient)
        painter.drawEllipse(centre, radius, radius)

    def _draw_flow(self, painter: QPainter, centre: QPointF, radius: float, accent: QColor, t: float) -> None:
        layers = LAYERS // 2 if self._reduced else LAYERS
        points = POINTS // 2 if self._reduced else POINTS
        swing = (0.16 if self._active else 0.09) + self._energy * 0.30
        speed = 0.35 if self._reduced else 1.0

        painter.setBrush(Qt.BrushStyle.NoBrush)

        for i in range(layers):
            f = i / max(1, layers - 1)
            base = radius * (0.22 + 0.76 * f)
            phase = t * speed * (0.9 + f * 0.5) + i * 0.38

            path = QPainterPath()
            for j in range(points + 1):
                a = (j / points) * 2 * math.pi
                # Three harmonics: a slow lobe, a faster ripple, and a
                # counter-rotating one. Together they read as flowing silk
                # rather than a repeating loop.
                wobble = (
                    0.52 * math.sin(3 * a + phase)
                    + 0.30 * math.sin(5 * a - phase * 1.27)
                    + 0.18 * math.sin(2 * a + phase * 0.63)
                )
                r = base * (1 + swing * wobble)
                x = centre.x() + math.cos(a) * r
                # Squashed vertically: a sphere seen slightly from above.
                y = centre.y() + math.sin(a) * r * 0.93
                if j == 0:
                    path.moveTo(x, y)
                else:
                    path.lineTo(x, y)
            path.closeSubpath()

            lit = max(0.0, min(1.0, 1 - abs(f - 0.55) * 1.6))
            alpha = (0.10 + 0.34 * lit) * (0.75 + self._energy * 0.5)
            colour = _mix(accent, QColor("#FFFFFF"), 0.18 * lit)
            colour.setAlphaF(max(0.0, min(0.85, alpha)))

            painter.setPen(QPen(colour, 1.15))
            painter.drawPath(path)

    def _draw_rim(self, painter: QPainter, centre: QPointF, radius: float, accent: QColor) -> None:
        gradient = QRadialGradient(centre, radius)
        transparent = QColor(accent)
        transparent.setAlpha(0)
        edge = QColor(accent)
        edge.setAlphaF(min(1.0, 0.30 + self._energy * 0.25))
        lip = _mix(accent, QColor("#FFFFFF"), 0.5)
        lip.setAlphaF(min(1.0, 0.55 + self._energy * 0.3))

        gradient.setColorAt(0.80, transparent)
        gradient.setColorAt(0.96, edge)
        gradient.setColorAt(1.0, lip)

        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(gradient)
        painter.drawEllipse(centre, radius, radius)

        # The specular catch, low and to one side.
        spec = _mix(accent, QColor("#FFFFFF"), 0.65)
        spec.setAlphaF(0.75)
        painter.setBrush(Qt.BrushStyle.NoBrush)
        painter.setPen(QPen(spec, 2.2))
        box = QRectF(centre.x() - radius, centre.y() - radius, radius * 2, radius * 2)
        painter.drawArc(box, int(118 * 16), int(70 * 16))

    def _draw_ring(self, painter: QPainter, centre: QPointF, radius: float, accent: QColor) -> None:
        painter.setBrush(Qt.BrushStyle.NoBrush)
        # Several wider, fainter passes stand in for a bloom.
        for width, alpha in ((9.0, 0.07), (5.0, 0.13), (2.6, 0.28)):
            colour = QColor(accent)
            colour.setAlphaF(min(1.0, alpha * (0.8 + self._energy * 0.6)))
            painter.setPen(QPen(colour, width))
            painter.drawEllipse(centre, radius, radius)

        hard = _mix(accent, QColor("#FFFFFF"), 0.35)
        hard.setAlphaF(0.95)
        painter.setPen(QPen(hard, 1.4))
        painter.drawEllipse(centre, radius, radius)

    def _draw_dots(self, painter: QPainter, centre: QPointF, radius: float, accent: QColor, t: float) -> None:
        dots = DOTS // 2 if self._reduced else DOTS
        sweep_speed = 0.3 if self._reduced else 0.9

        painter.save()
        painter.translate(centre)
        painter.rotate(t * 6)
        painter.setPen(Qt.PenStyle.NoPen)

        for i in range(dots):
            a = (i / dots) * 2 * math.pi
            # Brightness sweeps around, which reads as rotation without
            # every dot blinking independently.
            wave = math.sin(a - t * sweep_speed * 2)
            alpha = (0.12 + 0.55 * ((wave + 1) / 2)) * (0.7 + self._energy * 0.6)
            colour = QColor(accent)
            colour.setAlphaF(max(0.0, min(0.9, alpha)))
            painter.setBrush(colour)
            painter.drawEllipse(
                QPointF(math.cos(a) * radius, math.sin(a) * radius * 0.97), 1.5, 1.5
            )
        painter.restore()
