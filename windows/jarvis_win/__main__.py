"""Entry point for the JARVIS desktop app.

Wires the window, the system tray and the global hotkey. The assistant
pipeline (speech, model, tools) is not connected yet — see
docs/BUILD_STATUS.md for exactly what is and is not implemented.
"""

from __future__ import annotations

import logging
import sys

from PySide6.QtCore import Qt
from PySide6.QtGui import QAction, QIcon, QKeySequence, QPainter, QPixmap, QShortcut
from PySide6.QtWidgets import QApplication, QMenu, QSystemTrayIcon

from jarvis_win.core.config import Settings, secrets_available
from jarvis_win.core.state import AssistantState
from jarvis_win.ui.main_window import MainWindow
from jarvis_win.ui.theme import PALETTE

log = logging.getLogger("jarvis")


def _tray_icon() -> QIcon:
    """Draw a simple glyph so the build needs no binary asset."""
    pixmap = QPixmap(64, 64)
    pixmap.fill(Qt.GlobalColor.transparent)
    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)
    painter.setBrush(Qt.GlobalColor.transparent)
    pen = painter.pen()
    pen.setColor(PALETTE.accent)
    pen.setWidth(6)
    painter.setPen(pen)
    painter.drawEllipse(10, 10, 44, 44)
    painter.drawLine(32, 22, 32, 42)
    painter.end()
    return QIcon(pixmap)


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")

    app = QApplication(argv if argv is not None else sys.argv)
    app.setApplicationName("JARVIS")
    app.setQuitOnLastWindowClosed(False)  # closing the window keeps the tray

    settings = Settings.load()
    window = MainWindow(settings)

    if not secrets_available():
        window.show_text(
            "تنبيه: لا يوجد مخزن بيانات اعتماد في هذا النظام، "
            "لذلك لن يُحفَظ رمز الجهاز ولا مفتاح OpenRouter بين الجلسات."
        )

    # --- tray ------------------------------------------------------------
    tray = QSystemTrayIcon(_tray_icon(), app)
    tray.setToolTip("JARVIS")
    menu = QMenu()

    show_action = QAction("إظهار النافذة", menu)
    show_action.triggered.connect(lambda: (window.showNormal(), window.raise_(), window.activateWindow()))
    menu.addAction(show_action)

    listen_action = QAction("استماع", menu)
    listen_action.triggered.connect(lambda: window.set_state(AssistantState.LISTENING))
    menu.addAction(listen_action)

    menu.addSeparator()
    quit_action = QAction("إغلاق", menu)
    quit_action.triggered.connect(app.quit)
    menu.addAction(quit_action)

    tray.setContextMenu(menu)
    tray.activated.connect(
        lambda reason: window.showNormal() if reason == QSystemTrayIcon.ActivationReason.Trigger else None
    )
    tray.show()

    # --- activation shortcut ---------------------------------------------
    # Window-scoped: a true system-wide hotkey needs an OS hook and is
    # tracked as unimplemented rather than faked here.
    QShortcut(
        QKeySequence(settings.push_to_talk_hotkey),
        window,
        activated=lambda: window.set_state(AssistantState.LISTENING),
    )

    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
