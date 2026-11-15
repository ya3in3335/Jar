package com.jarvis.ai.feature.home

import androidx.lifecycle.ViewModel
import com.jarvis.ai.core.data.JarvisSettings
import com.jarvis.ai.feature.overlay.FloatingAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The home screen's window onto [JarvisBrain].
 *
 * It holds nothing of its own: the assistant outlives this screen so the
 * floating head can keep using it.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(private val brain: JarvisBrain) : ViewModel() {

    val ui: StateFlow<HomeUiState> = brain.ui
    val settings: StateFlow<JarvisSettings> = brain.settings

    fun refreshKeyState() = brain.refreshKeyState()
    fun submit(utterance: String) = brain.submit(utterance)
    fun confirmPending() = brain.confirmPending()
    fun rejectPending() = brain.rejectPending()
    fun toggleMic(on: Boolean) = brain.toggleMic(on)
    fun onMicPermissionDenied() = brain.onMicPermissionDenied()
    fun cancel() = brain.cancel()
    fun setConnected(connected: Boolean) = brain.setConnected(connected)
    fun pushAmplitude(rms: Float) = brain.pushAmplitude(rms)

    override fun onCleared() {
        // With the floating head up he is still on screen and may be mid-sentence.
        if (!FloatingAvatar.isShowing) brain.quiet()
        super.onCleared()
    }
}
