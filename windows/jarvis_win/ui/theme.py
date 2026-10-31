"""Visual language for the desktop app.

Dark, premium, minimal. Depth comes from layered translucency and soft
glow rather than sci-fi HUD ornament, per the interface restrictions in
spec section 6.
"""

from __future__ import annotations

from dataclasses import dataclass

from jarvis_win.core.state import AssistantState


@dataclass(frozen=True)
class Palette:
    bg_deep: str = "#05070d"
    bg_panel: str = "#0b1018"
    bg_raised: str = "#121a26"
    border: str = "#1e2a3a"
    text: str = "#e6eef8"
    text_dim: str = "#8fa3b8"
    accent: str = "#3ddcff"
    accent_soft: str = "#1b6b85"
    success: str = "#3ddc97"
    warn: str = "#ffc857"
    error: str = "#ff5d6c"


PALETTE = Palette()

#: Accent colour per assistant state — the one signal the eye tracks.
STATE_COLOR: dict[AssistantState, str] = {
    AssistantState.IDLE: PALETTE.accent_soft,
    AssistantState.LISTENING: PALETTE.accent,
    AssistantState.UNDERSTANDING: "#7aa2ff",
    AssistantState.THINKING: "#a07aff",
    AssistantState.SPEAKING: PALETTE.accent,
    AssistantState.EXECUTING: PALETTE.warn,
    AssistantState.SUCCESS: PALETTE.success,
    AssistantState.ERROR: PALETTE.error,
    AssistantState.DISCONNECTED: "#4a5668",
}

FONT_STACK = '"Segoe UI", "Noto Sans Arabic", "Cairo", sans-serif'


def stylesheet() -> str:
    p = PALETTE
    return f"""
    QWidget {{
        background: {p.bg_deep};
        color: {p.text};
        font-family: {FONT_STACK};
        font-size: 14px;
    }}
    QFrame#panel {{
        background: {p.bg_panel};
        border: 1px solid {p.border};
        border-radius: 14px;
    }}
    QLabel#title {{
        font-size: 22px;
        font-weight: 600;
        letter-spacing: 3px;
    }}
    QLabel#status {{
        color: {p.text_dim};
        font-size: 15px;
    }}
    QLabel#transcript {{
        color: {p.text};
        font-size: 15px;
        line-height: 150%;
    }}
    QPushButton {{
        background: {p.bg_raised};
        border: 1px solid {p.border};
        border-radius: 10px;
        padding: 9px 16px;
        color: {p.text};
    }}
    QPushButton:hover {{
        background: #182333;
        border-color: {p.accent_soft};
    }}
    QPushButton:pressed {{
        background: #0e1520;
    }}
    QPushButton#primary {{
        background: {p.accent_soft};
        border: 1px solid {p.accent};
        font-weight: 600;
    }}
    QPushButton#primary:hover {{
        background: #227f9e;
    }}
    QPushButton#danger {{
        border-color: {p.error};
        color: {p.error};
    }}
    QLineEdit, QComboBox, QSpinBox {{
        background: {p.bg_raised};
        border: 1px solid {p.border};
        border-radius: 8px;
        padding: 8px 10px;
        selection-background-color: {p.accent_soft};
    }}
    QLineEdit:focus, QComboBox:focus {{
        border-color: {p.accent};
    }}
    QTabWidget::pane {{
        border: 1px solid {p.border};
        border-radius: 12px;
        top: -1px;
    }}
    QTabBar::tab {{
        background: transparent;
        padding: 9px 18px;
        color: {p.text_dim};
    }}
    QTabBar::tab:selected {{
        color: {p.text};
        border-bottom: 2px solid {p.accent};
    }}
    QCheckBox {{ spacing: 8px; }}
    """
