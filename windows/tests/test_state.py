import pytest

from jarvis_win.core.state import (
    STATUS_AR,
    AssistantState,
    IllegalTransition,
    StateMachine,
)


def test_starts_idle():
    assert StateMachine().state is AssistantState.IDLE


def test_every_state_has_arabic_status():
    for state in AssistantState:
        assert STATUS_AR[state].strip()


def test_normal_voice_cycle():
    m = StateMachine()
    for target in (
        AssistantState.LISTENING,
        AssistantState.UNDERSTANDING,
        AssistantState.THINKING,
        AssistantState.SPEAKING,
        AssistantState.IDLE,
    ):
        m.go(target)
    assert m.state is AssistantState.IDLE


def test_illegal_transition_raises():
    m = StateMachine()
    with pytest.raises(IllegalTransition):
        m.go(AssistantState.SUCCESS)  # idle -> success is not a real path


def test_error_reachable_from_anywhere():
    for state in AssistantState:
        m = StateMachine(state=state)
        assert m.can_go(AssistantState.ERROR)
        assert m.can_go(AssistantState.DISCONNECTED)


def test_idle_always_reachable_so_cancel_always_works():
    """A running action the user cannot abort is a bug, not a state."""
    for state in AssistantState:
        m = StateMachine(state=state)
        assert m.can_go(AssistantState.IDLE), state
        m.go(AssistantState.IDLE)
        assert m.state is AssistantState.IDLE


def test_local_router_may_skip_understanding():
    m = StateMachine(state=AssistantState.LISTENING)
    m.go(AssistantState.EXECUTING)
    assert m.state is AssistantState.EXECUTING


def test_same_state_is_noop():
    m = StateMachine()
    seen: list[tuple] = []
    m.subscribe(lambda a, b: seen.append((a, b)))
    m.go(AssistantState.IDLE)
    assert seen == []


def test_listeners_get_previous_and_current():
    m = StateMachine()
    seen: list[tuple] = []
    m.subscribe(lambda a, b: seen.append((a, b)))
    m.go(AssistantState.LISTENING)
    assert seen == [(AssistantState.IDLE, AssistantState.LISTENING)]


@pytest.mark.parametrize(
    "state,busy",
    [
        (AssistantState.IDLE, False),
        (AssistantState.SUCCESS, False),
        (AssistantState.ERROR, False),
        (AssistantState.DISCONNECTED, False),
        (AssistantState.LISTENING, True),
        (AssistantState.THINKING, True),
        (AssistantState.EXECUTING, True),
        (AssistantState.SPEAKING, True),
        (AssistantState.UNDERSTANDING, True),
    ],
)
def test_busy_flag_drives_cancel_button(state, busy):
    assert StateMachine(state=state).busy is busy
