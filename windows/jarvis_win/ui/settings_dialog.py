"""The settings dialog: the Groq key and the model.

The key is the one thing the app cannot work without, and until this dialog
existed there was no way to enter it. It goes to the Windows credential
store, never to the settings file, and is never shown back.
"""

from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QFormLayout,
    QLabel,
    QLineEdit,
    QVBoxLayout,
    QWidget,
)

from jarvis_win.core.config import Settings, get_groq_key, secrets_available, set_groq_key
from jarvis_win.core.groq import SUGGESTED_MODELS


class SettingsDialog(QDialog):
    def __init__(self, settings: Settings, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.settings = settings
        self.setWindowTitle("إعدادات جارفيس")
        self.setLayoutDirection(Qt.LayoutDirection.RightToLeft)
        self.setMinimumWidth(440)

        layout = QVBoxLayout(self)
        form = QFormLayout()

        self.key_input = QLineEdit()
        self.key_input.setEchoMode(QLineEdit.EchoMode.Password)
        self.key_input.setLayoutDirection(Qt.LayoutDirection.LeftToRight)
        self.key_input.setPlaceholderText("محفوظ — اتركه فارغًا للإبقاء عليه" if get_groq_key() else "gsk_...")
        form.addRow("مفتاح Groq", self.key_input)

        self.model_input = QComboBox()
        self.model_input.setEditable(True)
        self.model_input.setLayoutDirection(Qt.LayoutDirection.LeftToRight)
        self.model_input.addItems(list(SUGGESTED_MODELS))
        self.model_input.setCurrentText(settings.model)
        form.addRow("النموذج", self.model_input)

        self.auto_speak = QCheckBox("ينطق الردود تلقائيًا")
        self.auto_speak.setChecked(settings.auto_speak)
        form.addRow("", self.auto_speak)
        layout.addLayout(form)

        self.note = QLabel(
            "مفتاح واحد من console.groq.com يكفي للنموذج والتعرّف على الكلام والنطق. "
            "يُحفَظ في مخزن بيانات اعتماد ويندوز."
        )
        self.note.setWordWrap(True)
        self.note.setObjectName("status")
        layout.addWidget(self.note)

        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Save).setText("حفظ")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("إلغاء")
        buttons.accepted.connect(self._save)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def _save(self) -> None:
        problem = self.apply(self.key_input.text(), self.model_input.currentText(), self.auto_speak.isChecked())
        if problem:
            self.note.setText(problem)
            return
        self.accept()

    def apply(self, key: str, model: str, auto_speak: bool) -> str | None:
        """Store the values. Returns an Arabic message when something is wrong."""
        key = key.strip()
        if key:
            if not key.startswith("gsk_"):
                return "مفتاح Groq يبدأ بـ gsk_ — تأكّد أنك نسخته كاملًا."
            if not secrets_available():
                return "لا يوجد مخزن بيانات اعتماد في هذا النظام، فتعذّر حفظ المفتاح."
            if not set_groq_key(key):
                return "تعذّر حفظ المفتاح في مخزن بيانات الاعتماد."
        if model.strip():
            self.settings.model = model.strip()
        self.settings.auto_speak = auto_speak
        self.settings.save()
        return None
