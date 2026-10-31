"""Entry point for the JARVIS desktop app."""

from __future__ import annotations

import logging
import sys

from PySide6.QtCore import Qt
from PySide6.QtGui import QAction, QIcon, QKeySequence, QPainter, QPixmap, QShortcut
from PySide6.QtWidgets import QApplication, QMenu, QMessageBox, QSystemTrayIcon

from jarvis_win.core.config import Settings, secrets_available
from jarvis_win.core.controller import Controller
from jarvis_win.core.files import DeletionPlan
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


def _confirm_deletion(window: MainWindow, summary: str, plan: DeletionPlan) -> bool:
    """Show exactly what will go, and default to not deleting.

    Deletion is the one action with no undo, so the dialog lists every file
    rather than a count, and Cancel is the default button.
    """
    box = QMessageBox(window)
    box.setWindowTitle("تأكيد الحذف")
    box.setIcon(QMessageBox.Icon.Warning)
    box.setText(summary)
    box.setInformativeText(
        "نهائيًا — لا يمكن التراجع." if plan.permanent else "ستنتقل إلى سلّة المحذوفات ويمكن استرجاعها."
    )
    box.setDetailedText("\n".join(c.display for c in plan.candidates))

    delete_button = box.addButton("حذف", QMessageBox.ButtonRole.DestructiveRole)
    cancel_button = box.addButton("إلغاء", QMessageBox.ButtonRole.RejectRole)
    box.setDefaultButton(cancel_button)  # the safe option is the default
    box.setEscapeButton(cancel_button)
    box.exec()
    return box.clickedButton() is delete_button


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")

    app = QApplication(argv if argv is not None else sys.argv)
    app.setApplicationName("JARVIS")
    app.setQuitOnLastWindowClosed(False)  # closing the window keeps the tray

    settings = Settings.load()
    window = MainWindow(settings)
    controller = Controller(settings)

    # --- controller to window --------------------------------------------
    controller.state_changed.connect(window.set_state)
    controller.level_changed.connect(window.push_amplitude)
    controller.text_shown.connect(window.show_text)
    controller.error_shown.connect(window.show_error)

    def on_approval(summary: str, plan: DeletionPlan) -> None:
        if _confirm_deletion(window, summary, plan):
            controller.approve_deletion(plan)
        else:
            controller.reject_deletion()

    controller.approval_requested.connect(on_approval)

    # --- window to controller --------------------------------------------
    window.utterance_submitted.connect(controller.handle_utterance)
    window.mic_toggled.connect(controller.set_listening)
    window.cancel_requested.connect(controller.cancel)

    if not secrets_available():
        window.show_error(
            "لا يوجد مخزن بيانات اعتماد في هذا النظام، "
            "فلن تُحفَظ المفاتيح بين الجلسات."
        )

    recogniser = controller.stt_available()
    if recogniser is None:
        window.show_error(
            "لا يوجد محرّك تعرّف على الكلام. "
            "ثبّت faster-whisper: pip install -r requirements-stt.txt"
        )

    window.populate_voices(controller.available_voices())

    # --- tray --------------------------------------------------------------
    tray = QSystemTrayIcon(_tray_icon(), app)
    tray.setToolTip("JARVIS")
    menu = QMenu()

    show_action = QAction("إظهار النافذة", menu)
    show_action.triggered.connect(lambda: (window.showNormal(), window.raise_(), window.activateWindow()))
    menu.addAction(show_action)

    listen_action = QAction("استماع", menu)
    listen_action.triggered.connect(lambda: controller.set_listening(True))
    menu.addAction(listen_action)

    stop_action = QAction("إيقاف", menu)
    stop_action.triggered.connect(controller.cancel)
    menu.addAction(stop_action)

    menu.addSeparator()
    quit_action = QAction("إغلاق", menu)
    quit_action.triggered.connect(app.quit)
    menu.addAction(quit_action)

    tray.setContextMenu(menu)
    tray.activated.connect(
        lambda reason: window.showNormal() if reason == QSystemTrayIcon.ActivationReason.Trigger else None
    )
    tray.show()

    # Window-scoped: a true system-wide hotkey needs an OS hook and is
    # tracked as unimplemented rather than faked here.
    QShortcut(
        QKeySequence(settings.push_to_talk_hotkey),
        window,
        activated=lambda: controller.set_listening(True),
    )

    app.aboutToQuit.connect(controller.shutdown)
    window.set_state(AssistantState.IDLE)
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
