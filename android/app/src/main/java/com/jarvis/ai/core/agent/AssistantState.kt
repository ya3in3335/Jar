package com.jarvis.ai.core.agent

import androidx.annotation.StringRes
import com.jarvis.ai.R

/**
 * The nine assistant states from the specification.
 *
 * Kept in step with the desktop app's `jarvis_win.core.state` so both
 * platforms show the same thing in the same situation.
 */
enum class AssistantState(@StringRes val statusRes: Int) {
    IDLE(R.string.state_idle),
    LISTENING(R.string.state_listening),
    UNDERSTANDING(R.string.state_understanding),
    THINKING(R.string.state_thinking),
    SPEAKING(R.string.state_speaking),
    EXECUTING(R.string.state_executing),
    SUCCESS(R.string.state_success),
    ERROR(R.string.state_error),
    DISCONNECTED(R.string.state_disconnected),
    ;

    /** True while work is in flight, so the UI can offer Cancel. */
    val isBusy: Boolean
        get() = this in setOf(LISTENING, UNDERSTANDING, THINKING, SPEAKING, EXECUTING)
}

/**
 * Reachable from every state: ERROR and DISCONNECTED can happen at any
 * moment, and IDLE must always be reachable because cancelling is a hard
 * requirement — a running action the user cannot abort is a bug.
 */
private val UNIVERSAL = setOf(AssistantState.IDLE, AssistantState.ERROR, AssistantState.DISCONNECTED)

private val FORWARD: Map<AssistantState, Set<AssistantState>> = mapOf(
    AssistantState.IDLE to setOf(AssistantState.LISTENING, AssistantState.EXECUTING, AssistantState.THINKING),
    // LISTENING -> EXECUTING is legal: the local intent router can resolve a
    // command with no model call, skipping UNDERSTANDING entirely.
    AssistantState.LISTENING to setOf(AssistantState.UNDERSTANDING, AssistantState.EXECUTING),
    AssistantState.UNDERSTANDING to setOf(AssistantState.THINKING, AssistantState.EXECUTING),
    AssistantState.THINKING to setOf(AssistantState.SPEAKING, AssistantState.EXECUTING, AssistantState.SUCCESS),
    AssistantState.SPEAKING to setOf(AssistantState.LISTENING, AssistantState.EXECUTING),
    AssistantState.EXECUTING to setOf(AssistantState.SUCCESS, AssistantState.THINKING, AssistantState.SPEAKING),
    AssistantState.SUCCESS to setOf(AssistantState.SPEAKING, AssistantState.LISTENING),
    AssistantState.ERROR to setOf(AssistantState.LISTENING),
    AssistantState.DISCONNECTED to emptySet(),
)

val TRANSITIONS: Map<AssistantState, Set<AssistantState>> =
    FORWARD.mapValues { (_, forward) -> forward + UNIVERSAL }

fun AssistantState.canGoTo(target: AssistantState): Boolean =
    target == this || target in (TRANSITIONS[this] ?: emptySet())
