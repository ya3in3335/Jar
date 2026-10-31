"""Installing optional components on demand.

The heavy pieces — local speech recognition, the neural voice — are not in
the installer, because most of their size is paid for by users who would
never enable them. Instead the app offers to fetch a component the first
time it is actually needed.

Rules this follows:
- never install anything without the user asking for that component,
- never install from anywhere but PyPI, with pinned package names,
- refuse outright when running from a frozen build, where pip cannot
  install into the bundle and would appear to succeed while changing
  nothing.
"""

from __future__ import annotations

import logging
import subprocess
import sys
import threading
from dataclasses import dataclass, field
from importlib import import_module
from importlib.util import find_spec
from typing import Callable, Final, Sequence

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class Component:
    """An optional capability and the packages it needs."""

    key: str
    label: str
    #: What the user gains, in Arabic, shown before anything is downloaded.
    benefit: str
    packages: tuple[str, ...]
    #: Module that proves the component is present.
    probe: str
    approx_mb: int

    def is_installed(self) -> bool:
        return find_spec(self.probe) is not None


COMPONENTS: Final = (
    Component(
        key="stt",
        label="التعرّف على الكلام محليًا",
        benefit="يحوّل كلامك إلى نصّ على جهازك، بلا إنترنت وبلا مفتاح.",
        packages=("faster-whisper>=1.0",),
        probe="faster_whisper",
        approx_mb=120,
    ),
    Component(
        key="neural_voice",
        label="صوت عربي عصبي محلي",
        benefit="صوت أوضح من أصوات ويندوز، يعمل بلا إنترنت وبلا مفتاح.",
        packages=("piper-tts>=1.2",),
        probe="piper",
        approx_mb=90,
    ),
    Component(
        key="better_vad",
        label="كشف كلام أدقّ",
        benefit="يميّز الكلام عن ضجيج الغرفة أفضل من الكاشف المدمج.",
        packages=("webrtcvad-wheels>=2.0.14",),
        probe="webrtcvad",
        approx_mb=1,
    ),
)


def find_component(key: str) -> Component | None:
    return next((c for c in COMPONENTS if c.key == key), None)


def missing_components() -> list[Component]:
    return [c for c in COMPONENTS if not c.is_installed()]


def is_frozen() -> bool:
    """True inside a PyInstaller bundle, where pip cannot help."""
    return getattr(sys, "frozen", False) or hasattr(sys, "_MEIPASS")


class InstallError(RuntimeError):
    pass


@dataclass
class InstallReport:
    component: Component
    ok: bool
    message: str
    log_tail: list[str] = field(default_factory=list)


def install(
    component: Component,
    on_line: Callable[[str], None] | None = None,
    timeout: float = 900.0,
) -> InstallReport:
    """Install one component's packages with pip, reporting progress.

    Blocking: callers run it on a worker thread. Output is streamed through
    ``on_line`` so a window can show real progress rather than a spinner
    that says nothing.
    """
    if component.is_installed():
        return InstallReport(component, True, "مثبّت بالفعل")

    if is_frozen():
        # pip would write into a temporary extraction directory that
        # disappears on exit, so it would look installed and not be.
        return InstallReport(
            component,
            False,
            "لا يمكن التثبيت داخل النسخة المحزومة. شغّل من المصدر، أو نزّل نسخة تتضمّن هذا المكوّن.",
        )

    command: Sequence[str] = (
        sys.executable,
        "-m",
        "pip",
        "install",
        "--only-binary=:all:",  # no source builds: no compiler required
        "--disable-pip-version-check",
        "--no-input",
        *component.packages,
    )
    log.info("installing %s: %s", component.key, " ".join(component.packages))

    tail: list[str] = []
    try:
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except OSError as exc:
        return InstallReport(component, False, f"تعذّر تشغيل pip: {exc.strerror or type(exc).__name__}")

    timer = threading.Timer(timeout, process.kill)
    timer.start()
    try:
        assert process.stdout is not None
        for raw in process.stdout:
            line = raw.rstrip()
            if not line:
                continue
            tail.append(line)
            del tail[:-40]  # keep the end, which is where failures explain themselves
            if on_line:
                on_line(line)
        code = process.wait()
    finally:
        timer.cancel()

    if code != 0:
        return InstallReport(component, False, f"فشل التثبيت (رمز {code})", tail)

    # Trust the import, not pip's exit code: a wheel can install and still
    # fail to load on this machine.
    _invalidate_caches()
    if not component.is_installed():
        return InstallReport(component, False, "اكتمل التثبيت لكن المكتبة لا تُحمَّل", tail)

    return InstallReport(component, True, f"تم تثبيت {component.label}", tail)


def _invalidate_caches() -> None:
    """Make a freshly installed package importable without a restart."""
    import importlib
    import site

    importlib.invalidate_caches()
    try:
        site.main()  # re-reads site-packages paths
    except Exception:
        log.debug("site.main() unavailable")


def try_import(module: str):
    """Import a module, or None when it is not installed."""
    try:
        return import_module(module)
    except ImportError:
        return None
