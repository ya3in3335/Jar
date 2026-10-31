"""Headless checks that the window builds and its state wiring holds.

Runs under the Qt offscreen platform, so it works in CI with no display.
"""

import os

import pytest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

pytest.importorskip("PySide6")

from PySide6.QtWidgets import QApplication  # noqa: E402

from jarvis_win.core.config import Settings  # noqa: E402
from jarvis_win.core.state import AssistantState  # noqa: E402
from jarvis_win.ui.main_window import MainWindow  # noqa: E402


@pytest.fixture(scope="module")
def qapp():
    app = QApplication.instance() or QApplication([])
    yield app


@pytest.fixture
def window(qapp):
    w = MainWindow(Settings())
    yield w
    w.close()
    w.deleteLater()


def test_window_builds(window):
    assert window.windowTitle() == "JARVIS"
    assert window.machine.state is AssistantState.IDLE


def test_arabic_layout_is_right_to_left(qapp):
    from PySide6.QtCore import Qt

    w = MainWindow(Settings(language="ar"))
    assert w.layoutDirection() == Qt.LayoutDirection.RightToLeft
    w.deleteLater()


def test_status_text_follows_state(window):
    window.set_state(AssistantState.LISTENING)
    assert window.status_label.text() == "أستمع إليك..."
    window.set_state(AssistantState.EXECUTING)
    assert window.status_label.text() == "أنفّذ الأمر..."


def test_cancel_enabled_only_while_busy(window):
    assert window.cancel_btn.isEnabled() is False
    window.set_state(AssistantState.THINKING)
    assert window.cancel_btn.isEnabled() is True
    window.set_state(AssistantState.SUCCESS)
    assert window.cancel_btn.isEnabled() is False


def test_cancel_emits_and_returns_to_idle(window):
    window.set_state(AssistantState.EXECUTING)
    seen = []
    window.cancel_requested.connect(lambda: seen.append(True))
    window._on_cancel()
    assert seen == [True]
    assert window.machine.state is AssistantState.IDLE


def test_disconnect_shows_disconnected(window):
    window.set_connected(False)
    assert window.machine.state is AssistantState.DISCONNECTED
    assert window.status_label.text() == "غير متصل"
    window.set_connected(True)
    assert window.machine.state is AssistantState.IDLE


def test_local_command_goes_straight_to_executing(window):
    emitted = []
    window.utterance_submitted.connect(emitted.append)
    window.input.setText("افتح يوتيوب")
    window._on_submit()
    assert emitted == ["افتح يوتيوب"]
    # Routed locally, so no model call: straight to EXECUTING.
    assert window.machine.state is AssistantState.EXECUTING


def test_open_question_goes_to_thinking(window):
    window.input.setText("لماذا السماء زرقاء")
    window._on_submit()
    assert window.machine.state is AssistantState.THINKING


def test_visualizer_accepts_amplitude(window):
    window.push_amplitude(0.7)
    window.visualizer._tick()
    assert max(window.visualizer._levels) > 0
