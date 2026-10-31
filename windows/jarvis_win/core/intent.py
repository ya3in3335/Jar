"""Local intent router — spec section 16B.

Unambiguous commands are handled by deterministic rules and never reach a
language model. "افتح يوتيوب" costs nothing and answers instantly; only
ambiguity, reasoning or planning is worth a model call.

``route()`` returns an :class:`Intent` for a confident match, or ``None``
to mean "send this to the model".
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from typing import Final

# --------------------------------------------------------- Arabic normalising

_DIACRITICS: Final = re.compile(r"[ً-ْٰـ]")
_TATWEEL: Final = "ـ"

_FOLD: Final = str.maketrans(
    {
        "أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا",
        "ى": "ي", "ئ": "ي",
        "ة": "ه",
        "ؤ": "و",
        "ﻻ": "لا",
    }
)


def normalize_ar(text: str) -> str:
    """Fold Arabic orthographic variation so one rule matches many spellings.

    Removes diacritics and tatweel, unifies alef/ya/ta-marbuta forms, strips
    Arabic-Indic digits to ASCII, and collapses whitespace.
    """
    text = unicodedata.normalize("NFKC", text)
    text = _DIACRITICS.sub("", text).replace(_TATWEEL, "")
    text = text.translate(_FOLD)
    # Arabic-Indic and Eastern Arabic-Indic digits -> ASCII
    out = []
    for ch in text:
        if "٠" <= ch <= "٩":
            out.append(chr(ord(ch) - 0x0660 + ord("0")))
        elif "۰" <= ch <= "۹":
            out.append(chr(ord(ch) - 0x06F0 + ord("0")))
        else:
            out.append(ch)
    return re.sub(r"\s+", " ", "".join(out)).strip().lower()


@dataclass(frozen=True)
class Intent:
    """A resolved local command."""

    tool: str
    args: dict[str, object] = field(default_factory=dict)
    #: True when the action changes something the user would not want undone
    #: silently (spec section 11). The caller must confirm before executing.
    sensitive: bool = False


# ------------------------------------------------------------- app catalogue

#: Spoken name -> canonical app id. Written naturally and normalised at
#: import, so a key here can never drift from what normalize_ar() produces.
_RAW_ALIASES: Final[dict[str, str]] = {
    "يوتيوب": "youtube",
    "اليوتيوب": "youtube",
    "youtube": "youtube",
    "انستغرام": "instagram",
    "انستقرام": "instagram",
    "الانستغرام": "instagram",
    "instagram": "instagram",
    "فيسبوك": "facebook",
    "الفيسبوك": "facebook",
    "facebook": "facebook",
    "واتساب": "whatsapp",
    "الواتساب": "whatsapp",
    "whatsapp": "whatsapp",
    "تيليجرام": "telegram",
    "تلغرام": "telegram",
    "telegram": "telegram",
    "الاعدادات": "settings",
    "اعدادات": "settings",
    "settings": "settings",
    "الكاميرا": "camera",
    "كاميرا": "camera",
    "camera": "camera",
    "المتصفح": "browser",
    "متصفح": "browser",
    "كروم": "browser",
    "chrome": "browser",
    "browser": "browser",
    "الموسيقى": "music",
    "موسيقى": "music",
    "music": "music",
    "الهاتف": "phone",
    "الرسائل": "messages",
    "messages": "messages",
    "الحاسبة": "calculator",
    "الالة الحاسبة": "calculator",
    "calculator": "calculator",
}

APP_ALIASES: Final[dict[str, str]] = {normalize_ar(k): v for k, v in _RAW_ALIASES.items()}

# Patterns below are written in NORMALISED Arabic, because route() folds the
# input first: alef variants -> ا, ى -> ي, ة -> ه.
_OPEN_VERBS: Final = r"(?:افتح|اضغط|شغل|ادخل|open|launch|start)"
_SEARCH_VERBS: Final = r"(?:ابحث|بحث|search|google)"
_MUSIC: Final = r"(?:موسيقي|الموسيقي|اغنيه|الاغنيه|music|song)"
_SCREEN_WORDS: Final = r"(?:شاشه|الشاشه|screen)"
_LOOK_VERBS: Final = r"(?:شوف|انظر|حلل|اقرا|افحص|look|analyze)"


def _app_id(raw: str) -> str | None:
    """Resolve a spoken app name, tolerating attached ال / ل prefixes."""
    key = normalize_ar(raw)
    for candidate in (key, key.removeprefix("ال"), key.removeprefix("ل"), key.removeprefix("لل")):
        if candidate in APP_ALIASES:
            return APP_ALIASES[candidate]
    return None


# ------------------------------------------------------------------- rules


def route(utterance: str) -> Intent | None:
    """Resolve an utterance locally, or return None to defer to the model."""
    if not utterance or not utterance.strip():
        return None

    text = normalize_ar(utterance)

    # --- volume ---------------------------------------------------------
    if re.search(r"(?:ارفع|زيد|علي)\s+(?:ال)?صوت|volume up", text):
        return Intent("control_volume", {"direction": "up", "step": 2})
    if re.search(r"(?:خفض|نقص|نزل)\s+(?:ال)?صوت|volume down", text):
        return Intent("control_volume", {"direction": "down", "step": 2})
    if re.search(r"(?:اكتم|اسكت|كتم)\s*(?:ال)?صوت|^mute$", text):
        return Intent("control_volume", {"direction": "mute"})

    # --- media ----------------------------------------------------------
    if re.search(rf"(?:اوقف|وقف|stop|pause)\s*{_MUSIC}?\s*$", text):
        return Intent("pause_media", {})
    if re.search(rf"{_OPEN_VERBS}\s+{_MUSIC}\s*$", text):
        return Intent("play_media", {})

    # --- screen ---------------------------------------------------------
    # Keyword co-occurrence rather than word order: Algerian phrasing varies
    # ("شوف واش ظاهر في الشاشة", "حلل الشاشة", "انظر إلى الشاشة").
    if re.search(_LOOK_VERBS, text) and re.search(_SCREEN_WORDS, text):
        return Intent("analyze_screen", {})

    # --- open an app, possibly with a search inside it ------------------
    # Checked before the generic search rule, so "افتح يوتيوب وابحث عن X"
    # is one app action and not a bare web search.
    m = re.match(rf"{_OPEN_VERBS}\s+(.+)$", text)
    if m:
        rest = m.group(1).strip()

        if app := _app_id(rest):
            return Intent("open_application", {"app": app})

        if re.fullmatch(r"https?://\S+", rest):
            return Intent("open_url", {"url": rest})

        # "<app> وابحث عن <query>" / "<app> و ابحث على <query>"
        parts = re.split(r"\s+و\s*(?=ابحث|بحث|search)", rest, maxsplit=1)
        if len(parts) == 2:
            app = _app_id(parts[0].strip())
            inner = route(parts[1].strip())
            if app and inner is not None and inner.tool == "search_web":
                return Intent("open_application", {"app": app, "search": inner.args.get("query")})

        return None  # an app we do not know: let the model decide

    # --- plain search ---------------------------------------------------
    m = re.search(rf"{_SEARCH_VERBS}\s+(?:عن|علي|في|for)?\s*(.+)$", text)
    if m:
        query = m.group(1).strip()
        # "ابحث عن X في يوتيوب" -> search inside that app, not the web.
        app_in = re.search(r"\s(?:في|علي)\s+(\S+)$", query)
        if app_in and (app := _app_id(app_in.group(1))):
            return Intent("open_application", {"app": app, "search": query[: app_in.start()].strip()})
        if query:
            return Intent("search_web", {"query": query})

    return None
