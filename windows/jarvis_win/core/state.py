"""Assistant state machine.

The nine states in the specification, plus the legal transitions between
them. Kept free of Qt so it can be tested headlessly, and so the Android
app can mirror the same names.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Callable


class AssistantState(Enum):
    IDLE = "idle"
    LISTENING = "listening"
    UNDERSTANDING = "understanding"
    THINKING = "thinking"
    SPEAKING = "speaking"
    EXECUTING = "executing"
    SUCCESS = "success"
    ERROR = "error"
    DISCONNECTED = "disconnected"


#: Arabic status line shown for each state (spec section 19).
STATUS_AR: dict[AssistantState, str] = {
    AssistantState.IDLE: "جاهز للاستماع",
    AssistantState.LISTENING: "أستمع إليك...",
    AssistantState.UNDERSTANDING: "أفهم طلبك...",
    AssistantState.THINKING: "أفكّر...",
    AssistantState.SPEAKING: "أتحدّث...",
    AssistantState.EXECUTING: "أنفّذ الأمر...",
    AssistantState.SUCCESS: "تم التنفيذ بنجاح",
    AssistantState.ERROR: "تعذّر تنفيذ الأمر",
    AssistantState.DISCONNECTED: "غير متصل",
}

#: Reachable from every state: ERROR and DISCONNECTED can happen at any
#: moment, and IDLE must always be reachable because cancelling is a hard
#: requirement — a running action the user cannot abort is a bug.
_UNIVERSAL: frozenset[AssistantState] = frozenset(
    {AssistantState.IDLE, AssistantState.ERROR, AssistantState.DISCONNECTED}
)

#: Forward transitions, per state. _UNIVERSAL is added to each at build time.
_BASE_TRANSITIONS: dict[AssistantState, set[AssistantState]] = {
    AssistantState.IDLE: {AssistantState.LISTENING, AssistantState.EXECUTING, AssistantState.THINKING},
    # LISTENING -> EXECUTING is legal: the local intent router can resolve a
    # command with no model call, skipping UNDERSTANDING entirely.
    AssistantState.LISTENING: {AssistantState.UNDERSTANDING, AssistantState.EXECUTING},
    AssistantState.UNDERSTANDING: {AssistantState.THINKING, AssistantState.EXECUTING},
    AssistantState.THINKING: {AssistantState.SPEAKING, AssistantState.EXECUTING, AssistantState.SUCCESS},
    AssistantState.SPEAKING: {AssistantState.LISTENING, AssistantState.EXECUTING},
    AssistantState.EXECUTING: {AssistantState.SUCCESS, AssistantState.THINKING, AssistantState.SPEAKING},
    AssistantState.SUCCESS: {AssistantState.SPEAKING, AssistantState.LISTENING},
    AssistantState.ERROR: {AssistantState.LISTENING},
    AssistantState.DISCONNECTED: set(),
}

TRANSITIONS: dict[AssistantState, frozenset[AssistantState]] = {
    state: frozenset(allowed) | _UNIVERSAL for state, allowed in _BASE_TRANSITIONS.items()
}


class IllegalTransition(RuntimeError):
    """Raised when code asks for a transition the machine does not allow."""


@dataclass
class StateMachine:
    """Tracks the assistant's state and notifies listeners on every change.

    Listeners receive ``(previous, current)``. A transition to the current
    state is a no-op rather than an error, so repeated signals are harmless.
    """

    state: AssistantState = AssistantState.IDLE
    _listeners: list[Callable[[AssistantState, AssistantState], None]] = field(
        default_factory=list, repr=False
    )

    def subscribe(self, listener: Callable[[AssistantState, AssistantState], None]) -> None:
        self._listeners.append(listener)

    def can_go(self, target: AssistantState) -> bool:
        return target is self.state or target in TRANSITIONS[self.state]

    def go(self, target: AssistantState) -> None:
        if target is self.state:
            return
        if target not in TRANSITIONS[self.state]:
            raise IllegalTransition(f"{self.state.value} -> {target.value} is not allowed")
        previous, self.state = self.state, target
        for listener in list(self._listeners):
            listener(previous, target)

    @property
    def status_ar(self) -> str:
        return STATUS_AR[self.state]

    @property
    def busy(self) -> bool:
        """True while work is in flight, so the UI can offer Cancel."""
        return self.state in {
            AssistantState.LISTENING,
            AssistantState.UNDERSTANDING,
            AssistantState.THINKING,
            AssistantState.SPEAKING,
            AssistantState.EXECUTING,
        }
