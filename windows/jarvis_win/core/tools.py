"""Tools the Windows assistant can run, with their schemas.

Mirrors the Android tool contract so both platforms describe themselves to
the model the same way. The deletion tool is the one that needs a two-step
shape: it plans, the user approves the exact list, and only then does it
act — see ``files`` for the policy behind that.
"""

from __future__ import annotations

import os
import subprocess
import sys
import webbrowser
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Sequence
from urllib.parse import quote

from . import files


@dataclass(frozen=True)
class Param:
    name: str
    type: str
    description: str
    required: bool = True
    enum: list[str] | None = None


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    parameters: list[Param]
    run: Callable[[dict[str, Any]], "ToolResult"]
    #: Needs explicit user approval before it runs. Not advisory.
    sensitive: bool = False

    def schema(self) -> dict[str, Any]:
        properties: dict[str, Any] = {}
        for param in self.parameters:
            entry: dict[str, Any] = {"type": param.type, "description": param.description}
            if param.enum:
                entry["enum"] = param.enum
            properties[param.name] = entry
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": {
                    "type": "object",
                    "properties": properties,
                    "required": [p.name for p in self.parameters if p.required],
                    "additionalProperties": False,
                },
            },
        }

    def validate(self, args: dict[str, Any]) -> str | None:
        """Arabic message naming the problem, or None when the args are fine."""
        for param in self.parameters:
            if param.name not in args or args[param.name] in (None, ""):
                if param.required:
                    return f"الوسيط '{param.name}' مطلوب للأداة '{self.name}'"
                continue
            value = args[param.name]
            if param.type == "number" and not _is_number(value):
                return f"الوسيط '{param.name}' يجب أن يكون رقمًا"
            if param.type == "boolean" and not isinstance(value, bool):
                return f"الوسيط '{param.name}' يجب أن يكون صح/خطأ"
            if param.enum and str(value) not in param.enum:
                return f"الوسيط '{param.name}' يجب أن يكون أحد: {', '.join(param.enum)}"
        unknown = set(args) - {p.name for p in self.parameters}
        if unknown:
            return f"وسائط غير معروفة: {', '.join(sorted(unknown))}"
        return None


def _is_number(value: Any) -> bool:
    if isinstance(value, bool):
        return False
    if isinstance(value, (int, float)):
        return True
    try:
        float(str(value))
        return True
    except ValueError:
        return False


@dataclass
class ToolResult:
    ok: bool
    summary: str
    detail: str | None = None
    #: Set when the tool needs approval before it can finish.
    pending: Any | None = None

    @classmethod
    def success(cls, summary: str, detail: str | None = None) -> "ToolResult":
        return cls(True, summary, detail)

    @classmethod
    def failure(cls, summary: str, detail: str | None = None) -> "ToolResult":
        return cls(False, summary, detail)

    @classmethod
    def needs_approval(cls, summary: str, pending: Any) -> "ToolResult":
        return cls(False, summary, pending=pending)


# ----------------------------------------------------------------- the tools


def _open_url(args: dict[str, Any]) -> ToolResult:
    url = str(args.get("url", "")).strip()
    if not url.lower().startswith(("http://", "https://")):
        return ToolResult.failure("يُسمح بروابط http و https فقط")
    webbrowser.open(url)
    return ToolResult.success("فتحت الرابط")


def _search_web(args: dict[str, Any]) -> ToolResult:
    query = str(args.get("query", "")).strip()
    if not query:
        return ToolResult.failure("لم تُحدَّد عبارة البحث")
    webbrowser.open(f"https://www.google.com/search?q={quote(query)}")
    return ToolResult.success(f"بحثت عن: {query}")


def _open_application(args: dict[str, Any]) -> ToolResult:
    name = str(args.get("app", "")).strip()
    if not name:
        return ToolResult.failure("لم يُحدَّد التطبيق")

    known = {
        "notepad": "notepad.exe",
        "calculator": "calc.exe",
        "explorer": "explorer.exe",
        "settings": "ms-settings:",
        "browser": None,
        "terminal": "wt.exe",
        "paint": "mspaint.exe",
    }
    if name == "browser":
        webbrowser.open("https://www.google.com")
        return ToolResult.success("فتحت المتصفح")

    target = known.get(name, name)
    try:
        if sys.platform == "win32":
            os.startfile(target)  # type: ignore[attr-defined]
        else:
            subprocess.Popen([target], start_new_session=True)
    except (OSError, FileNotFoundError):
        return ToolResult.failure(f"تعذّر فتح '{name}'")
    return ToolResult.success(f"فتحت {name}")


def _list_files(args: dict[str, Any]) -> ToolResult:
    pattern = str(args.get("pattern", "*")).strip()
    roots = files.default_allowed_roots()
    if not roots:
        return ToolResult.failure("لا توجد مجلدات مسموح بقراءتها")

    found: list[Path] = []
    for root in roots:
        try:
            found.extend(sorted(root.glob(pattern))[:50])
        except (OSError, ValueError, NotImplementedError):
            continue

    if not found:
        return ToolResult.success("لا توجد ملفات مطابقة")
    names = "\n".join(f"- {p.name}  ({p.parent.name})" for p in found[:40])
    return ToolResult.success(f"وجدت {len(found)} ملفًا:\n{names}")


def _delete_files(args: dict[str, Any]) -> ToolResult:
    """Plan only. Nothing is deleted until the user approves the plan."""
    raw = args.get("patterns")
    patterns: Sequence[str]
    if isinstance(raw, str):
        patterns = [raw]
    elif isinstance(raw, (list, tuple)):
        patterns = [str(x) for x in raw]
    else:
        return ToolResult.failure("لم تُحدَّد الملفات")

    permanent = bool(args.get("permanent", False))
    include_dirs = bool(args.get("include_folders", False))

    try:
        plan = files.build_plan(patterns, permanent=permanent, include_dirs=include_dirs)
    except files.DeletionRefused as exc:
        return ToolResult.failure(str(exc))

    if plan.is_empty:
        reasons = "، ".join(f"{Path(p).name}: {why}" for p, why in plan.skipped[:5])
        return ToolResult.failure(f"لا شيء للحذف. {reasons}" if reasons else "لا توجد ملفات مطابقة")

    listing = "\n".join(f"- {c.path.name}" for c in plan.candidates[:20])
    more = f"\n… و{len(plan.candidates) - 20} غيرها" if len(plan.candidates) > 20 else ""
    return ToolResult.needs_approval(f"{plan.summary_ar()}:\n{listing}{more}", pending=plan)


DELETE_FILES = Tool(
    name="delete_files",
    description=(
        "يحذف ملفات داخل مجلدات المستخدم المسموح بها. "
        "يعرض القائمة أولًا وينتظر موافقة صريحة، ويرسلها إلى سلّة المحذوفات لا نهائيًا."
    ),
    parameters=[
        Param("patterns", "string", "اسم الملف أو نمط مثل *.pdf"),
        Param("permanent", "boolean", "حذف نهائي بدل سلّة المحذوفات", required=False),
        Param("include_folders", "boolean", "شمل المجلدات", required=False),
    ],
    run=_delete_files,
    sensitive=True,
)


def default_tools() -> list[Tool]:
    return [
        Tool(
            "open_url",
            "يفتح رابطًا في المتصفح.",
            [Param("url", "string", "الرابط الكامل")],
            _open_url,
        ),
        Tool(
            "search_web",
            "يبحث في الويب.",
            [Param("query", "string", "عبارة البحث")],
            _search_web,
        ),
        Tool(
            "open_application",
            "يفتح تطبيقًا على الحاسوب.",
            [
                Param(
                    "app", "string", "اسم التطبيق",
                    enum=["notepad", "calculator", "explorer", "settings", "browser", "terminal", "paint"],
                )
            ],
            _open_application,
        ),
        Tool(
            "list_files",
            "يسرد الملفات داخل مجلدات المستخدم.",
            [Param("pattern", "string", "نمط مثل *.pdf", required=False)],
            _list_files,
        ),
        DELETE_FILES,
    ]


@dataclass
class ToolRegistry:
    tools: list[Tool] = field(default_factory=default_tools)

    def find(self, name: str) -> Tool | None:
        return next((t for t in self.tools if t.name == name), None)

    def schemas(self) -> list[dict[str, Any]]:
        return [t.schema() for t in self.tools]

    @property
    def names(self) -> list[str]:
        return [t.name for t in self.tools]
