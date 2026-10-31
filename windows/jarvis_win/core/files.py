"""File deletion, with the guard rails a destructive tool needs.

Deletion is the one assistant action with no undo, and it is driven by a
language model parsing a spoken sentence. So the design assumes the model
will eventually produce something wrong, and makes that survivable:

- only inside roots the user explicitly allows,
- never a system location, a drive root, or a path that escapes via symlink,
- capped in count and total size,
- planned first and shown in full, executed only after confirmation,
- sent to the Recycle Bin, not erased, unless permanence is asked for twice,
- every deletion recorded.

``build_plan`` never deletes. ``execute`` deletes only what a plan listed.
"""

from __future__ import annotations

import os
import platform
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Final, Iterable, Sequence

# ----------------------------------------------------------------- policy

#: Nothing outside these may ever be touched, whatever the model asks.
DEFAULT_ALLOWED_SUBDIRS: Final = (
    "Desktop",
    "Documents",
    "Downloads",
    "Pictures",
    "Videos",
    "Music",
)

#: Refused even when inside an allowed root — these break a running system.
BLOCKED_NAMES: Final = frozenset(
    {
        "windows", "system32", "syswow64", "program files", "program files (x86)",
        "programdata", "appdata", "$recycle.bin", "system volume information",
        "boot", "recovery", "perflogs", ".ssh", ".gnupg", ".aws", ".config",
        "node_modules", ".git",
    }
)

#: Names that disqualify a folder from being an allowed root at all.
ROOT_BLOCKED_NAMES: Final = BLOCKED_NAMES - {"appdata"}

#: Extensions that are almost never what a user means to delete by voice.
SENSITIVE_SUFFIXES: Final = frozenset(
    {".exe", ".dll", ".sys", ".msi", ".bat", ".cmd", ".ps1", ".reg", ".key", ".pem"}
)

MAX_FILES: Final = 100
MAX_TOTAL_BYTES: Final = 2 * 1024**3  # 2 GB


class DeletionRefused(Exception):
    """Raised when a request is outside what this tool will ever do."""


@dataclass(frozen=True)
class Candidate:
    path: Path
    size: int
    is_dir: bool

    @property
    def display(self) -> str:
        return str(self.path)


@dataclass
class DeletionPlan:
    """What would be deleted. Produced without touching anything."""

    candidates: list[Candidate] = field(default_factory=list)
    skipped: list[tuple[str, str]] = field(default_factory=list)  # (path, reason)
    permanent: bool = False

    @property
    def total_bytes(self) -> int:
        return sum(c.size for c in self.candidates)

    @property
    def is_empty(self) -> bool:
        return not self.candidates

    def summary_ar(self) -> str:
        """One line the user can judge before approving."""
        if self.is_empty:
            return "لا توجد ملفات مطابقة"
        count = len(self.candidates)
        mb = self.total_bytes / 1024**2
        where = "نهائيًا" if self.permanent else "إلى سلّة المحذوفات"
        return f"سيُحذف {count} عنصرًا ({mb:.1f} ميغابايت) {where}"


@dataclass
class DeletionResult:
    deleted: list[str] = field(default_factory=list)
    failed: list[tuple[str, str]] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return bool(self.deleted) and not self.failed


def default_allowed_roots(home: Path | None = None) -> list[Path]:
    """The folders deletion is permitted in, out of the box."""
    base = home or Path.home()
    roots = [base / name for name in DEFAULT_ALLOWED_SUBDIRS]
    return [r for r in roots if r.is_dir() and validate_root(r) is None]


# ------------------------------------------------------------- validation


def _is_drive_root(path: Path) -> bool:
    """C:\\ or / — never a deletion target."""
    resolved = path.resolve()
    return resolved == resolved.parent


def _blocked_component(parts: Sequence[str]) -> str | None:
    for part in parts:
        if part.strip("\\/").lower() in BLOCKED_NAMES:
            return part
    return None


def containing_root(path: Path, roots: Sequence[Path]) -> Path | None:
    """The allowed root ``path`` really sits in, or None.

    Compares fully resolved paths, so a symlink or junction pointing out of
    an allowed root does not slip through.
    """
    try:
        resolved = path.resolve()
    except OSError:
        return None
    for root in roots:
        try:
            resolved.relative_to(root.resolve())
            return root
        except (ValueError, OSError):
            continue
    return None


def validate_root(root: Path) -> str | None:
    """Reject a root that must never be opened up, whatever the user asks.

    Checked when roots are configured rather than per file, because the
    answer is the same for every path underneath.
    """
    try:
        resolved = root.resolve()
    except OSError:
        return "مسار غير صالح"
    if resolved == resolved.parent:
        return "جذر القرص لا يصلح مجلدًا مسموحًا"
    # AppData is dropped from consideration rather than short-circuited:
    # a legitimate root can live under it (temp dirs, portable installs),
    # but a System32 deeper in the same path must still be caught.
    blocked = next(
        (part for part in resolved.parts if part.strip("\\/").lower() in ROOT_BLOCKED_NAMES),
        None,
    )
    if blocked:
        return f"مجلد نظام لا يصلح مجلدًا مسموحًا: {blocked}"
    return None


def check_path(path: Path, roots: Sequence[Path]) -> str | None:
    """Return a refusal reason, or None when the path may be considered."""
    if not roots:
        return "لم تُحدَّد أي مجلدات مسموح بالحذف داخلها"
    if _is_drive_root(path):
        return "جذر القرص لا يُحذف"

    root = containing_root(path, roots)
    if root is None:
        return "خارج المجلدات المسموح بها"

    # Only the part *below* the trusted root is inspected for blocked names.
    # Checking the whole absolute path would refuse every file whenever the
    # root itself happened to sit under one (on Windows, anything beneath
    # AppData), which made nothing deletable at all.
    try:
        relative = path.resolve().relative_to(root.resolve())
    except (ValueError, OSError):
        return "تعذّر تحديد موقع الملف"

    blocked = _blocked_component(relative.parts)
    if blocked:
        return f"مسار محمي: {blocked}"

    if path.is_symlink():
        return "اختصار/رابط رمزي لا يُحذف"
    if path.suffix.lower() in SENSITIVE_SUFFIXES:
        return f"نوع ملف حسّاس ({path.suffix})"
    return None


def _dir_size(path: Path) -> int:
    total = 0
    for root, _dirs, files in os.walk(path, followlinks=False):
        for name in files:
            try:
                total += (Path(root) / name).stat().st_size
            except OSError:
                continue
    return total


# ------------------------------------------------------------------ plan


def build_plan(
    patterns: Iterable[str],
    roots: Sequence[Path] | None = None,
    permanent: bool = False,
    include_dirs: bool = False,
) -> DeletionPlan:
    """Resolve what would be deleted. Touches nothing.

    ``patterns`` may be absolute paths or globs. A bare glob is matched
    inside each allowed root rather than across the whole filesystem.
    """
    allowed = list(roots) if roots is not None else default_allowed_roots()
    plan = DeletionPlan(permanent=permanent)
    seen: set[Path] = set()

    for raw in patterns:
        pattern = str(raw).strip().strip('"').strip("'")
        if not pattern:
            continue

        # A pattern that would sweep an entire root is refused outright: no
        # amount of confirmation makes "delete everything" a safe voice command.
        if pattern in {"*", "*.*", "**", "**/*", "/", "\\"}:
            plan.skipped.append((pattern, "نمط عام جدًا"))
            continue

        for match in _expand(pattern, allowed):
            if match in seen:
                continue
            seen.add(match)

            reason = check_path(match, allowed)
            if reason:
                plan.skipped.append((str(match), reason))
                continue
            if not match.exists():
                plan.skipped.append((str(match), "غير موجود"))
                continue
            if match.is_dir():
                if not include_dirs:
                    plan.skipped.append((str(match), "مجلد — يحتاج طلبًا صريحًا"))
                    continue
                plan.candidates.append(Candidate(match, _dir_size(match), True))
                continue

            try:
                size = match.stat().st_size
            except OSError:
                size = 0
            plan.candidates.append(Candidate(match, size, False))

    if len(plan.candidates) > MAX_FILES:
        raise DeletionRefused(
            f"العدد {len(plan.candidates)} يتجاوز الحدّ المسموح ({MAX_FILES} عنصرًا)"
        )
    if plan.total_bytes > MAX_TOTAL_BYTES:
        gb = plan.total_bytes / 1024**3
        raise DeletionRefused(f"الحجم الإجمالي {gb:.1f} غيغابايت يتجاوز الحدّ المسموح")

    return plan


def _expand(pattern: str, roots: Sequence[Path]) -> list[Path]:
    """Turn one pattern into concrete paths, confined to the allowed roots."""
    candidate = Path(pattern).expanduser()

    if candidate.is_absolute():
        if any(ch in pattern for ch in "*?["):
            parent = candidate.parent
            if not parent.is_dir():
                return []
            return sorted(parent.glob(candidate.name))
        return [candidate]

    matches: list[Path] = []
    for root in roots:
        try:
            matches.extend(sorted(root.glob(pattern)))
        except (OSError, ValueError, NotImplementedError):
            continue
    return matches


# --------------------------------------------------------------- execute


def execute(plan: DeletionPlan, audit_path: Path | None = None) -> DeletionResult:
    """Carry out a plan that the user has approved.

    Recycle Bin unless the plan says permanent, so a wrong call stays
    recoverable. Requires ``send2trash``; without it, a non-permanent
    deletion is refused rather than silently made permanent.
    """
    result = DeletionResult()
    if plan.is_empty:
        return result

    trash = None
    if not plan.permanent:
        try:
            from send2trash import send2trash as trash  # type: ignore
        except ImportError as exc:  # pragma: no cover - depends on install
            raise DeletionRefused(
                "حزمة send2trash غير مثبّتة، ولن أحذف نهائيًا بدل سلّة المحذوفات"
            ) from exc

    for candidate in plan.candidates:
        try:
            if plan.permanent:
                if candidate.is_dir:
                    import shutil

                    shutil.rmtree(candidate.path)
                else:
                    candidate.path.unlink()
            else:
                trash(str(candidate.path))  # type: ignore[misc]
            result.deleted.append(str(candidate.path))
        except OSError as exc:
            result.failed.append((str(candidate.path), exc.strerror or type(exc).__name__))

    if audit_path is not None:
        _write_audit(audit_path, plan, result)
    return result


def _write_audit(audit_path: Path, plan: DeletionPlan, result: DeletionResult) -> None:
    """Append what happened. A destructive tool that keeps no record is worse."""
    audit_path.parent.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
    mode = "permanent" if plan.permanent else "trash"
    with audit_path.open("a", encoding="utf-8") as handle:
        for path in result.deleted:
            handle.write(f"{stamp}\t{mode}\tdeleted\t{path}\n")
        for path, reason in result.failed:
            handle.write(f"{stamp}\t{mode}\tfailed\t{path}\t{reason}\n")


def on_windows() -> bool:
    return platform.system() == "Windows"
