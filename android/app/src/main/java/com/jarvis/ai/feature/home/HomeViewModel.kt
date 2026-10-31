package com.jarvis.ai.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.ai.core.agent.AssistantState
import com.jarvis.ai.core.agent.IntentRouter
import com.jarvis.ai.core.agent.canGoTo
import com.jarvis.ai.core.data.JarvisSettings
import com.jarvis.ai.core.data.SecureStore
import com.jarvis.ai.core.data.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val state: AssistantState = AssistantState.IDLE,
    val transcript: String = "",
    val micOn: Boolean = false,
    val amplitude: Float = 0f,
    /** Set when a pending action needs explicit confirmation (spec s11). */
    val pendingConfirmation: PendingAction? = null,
    val secureStorageAvailable: Boolean = true,
) {
    val canCancel: Boolean get() = state.isBusy
}

data class PendingAction(val description: String, val tool: String, val args: Map<String, Any?>)

@HiltViewModel
class HomeViewModel @Inject constructor(
    settingsStore: SettingsStore,
    secureStore: SecureStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(HomeUiState(secureStorageAvailable = secureStore.isAvailable))
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    val settings: StateFlow<JarvisSettings> =
        settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, JarvisSettings())

    /**
     * Route an utterance.
     *
     * A locally resolvable command goes straight to EXECUTING with no model
     * call; anything else goes to THINKING. The agent runtime that actually
     * performs the tool call lands in phase 4 — this method currently only
     * drives state and surfaces the routing decision.
     */
    fun submit(utterance: String) {
        if (utterance.isBlank()) return
        _ui.value = _ui.value.copy(transcript = "")

        val intent = IntentRouter.route(utterance)
        if (intent == null) {
            go(AssistantState.THINKING)
            return
        }
        if (intent.sensitive && settings.value.confirmSensitiveActions) {
            _ui.value = _ui.value.copy(
                pendingConfirmation = PendingAction(utterance, intent.tool, intent.args),
            )
            return
        }
        go(AssistantState.EXECUTING)
    }

    fun confirmPending() {
        _ui.value = _ui.value.copy(pendingConfirmation = null)
        go(AssistantState.EXECUTING)
    }

    fun rejectPending() {
        _ui.value = _ui.value.copy(pendingConfirmation = null)
        go(AssistantState.IDLE)
    }

    fun toggleMic(on: Boolean) {
        _ui.value = _ui.value.copy(micOn = on)
        go(if (on) AssistantState.LISTENING else AssistantState.IDLE)
    }

    /** Always permitted: an action the user cannot abort is a bug. */
    fun cancel() {
        _ui.value = _ui.value.copy(micOn = false, pendingConfirmation = null)
        go(AssistantState.IDLE)
    }

    fun setConnected(connected: Boolean) {
        if (!connected) {
            go(AssistantState.DISCONNECTED)
        } else if (_ui.value.state == AssistantState.DISCONNECTED) {
            go(AssistantState.IDLE)
        }
    }

    fun pushAmplitude(rms: Float) {
        _ui.value = _ui.value.copy(amplitude = rms.coerceIn(0f, 1f))
    }

    fun appendTranscript(chunk: String) {
        _ui.value = _ui.value.copy(transcript = _ui.value.transcript + chunk)
    }

    /** Apply a transition, ignoring one the machine does not allow. */
    private fun go(target: AssistantState) {
        val current = _ui.value.state
        if (current == target) return
        if (!current.canGoTo(target)) return
        _ui.value = _ui.value.copy(state = target)
    }
}
