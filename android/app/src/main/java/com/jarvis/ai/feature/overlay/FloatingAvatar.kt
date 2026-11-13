package com.jarvis.ai.feature.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.ai.MainActivity
import com.jarvis.ai.core.agent.AssistantState
import com.jarvis.ai.core.agent.JarvisAccessibilityService
import com.jarvis.ai.core.audio.GroqTtsProvider
import com.jarvis.ai.feature.home.AvatarBridge
import com.jarvis.ai.feature.home.HomeUiState
import com.jarvis.ai.feature.home.JarvisBrain
import com.jarvis.ai.feature.home.createAvatarView
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How the accessibility service, which Hilt does not build, reaches the assistant. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BrainEntryPoint {
    fun brain(): JarvisBrain
}

/**
 * The head, floating over the home screen and every other app.
 *
 * It is drawn by the accessibility service as an accessibility overlay, so
 * it needs no "display over other apps" permission of its own, and it is the
 * same assistant as the one in the app: both read and drive [JarvisBrain].
 *
 * - tap: start or stop listening (or cancel what he is doing)
 * - long press: look at the screen now and say what matters
 * - drag: move it
 *
 * The window is never focusable, so it cannot take the keyboard or the back
 * button from the app underneath, and it hides while the app itself is open.
 */
class FloatingAvatar private constructor(
    private val service: AccessibilityService,
    private val brain: JarvisBrain,
) {

    private val windows = service.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bridge = AvatarBridge()

    private var root: LinearLayout? = null
    private var web: WebView? = null
    private var bubble: TextView? = null
    private var hideBubble: Job? = null
    private var shownText = ""

    private var wanted = true      // the setting
    private var inOwnApp = false   // the app itself is in front
    private val launcher: String? = runCatching {
        service.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            0,
        )?.activityInfo?.packageName
    }.getOrNull()

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Not focusable: keys and the keyboard stay with the app below.
        // Hardware accelerated: the head is WebGL and needs it.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(12)
        y = dp(220)
    }

    private fun start() {
        scope.launch {
            brain.settings.collect { settings ->
                wanted = settings.floatingAvatar
                sync()
            }
        }
        scope.launch { brain.ui.collect(::render) }
    }

    /** Called by the service whenever a different window comes to the front. */
    fun onWindowChanged(packageName: String?) {
        inOwnApp = packageName == service.packageName
        sync()
        brain.onScreenChanged(packageName, isLauncher = packageName != null && packageName == launcher)
    }

    private fun sync() {
        if (wanted && !inOwnApp) show() else hide()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (root != null) return
        val context = service

        val head = FrameLayout(context)
        val view = createAvatarView(context, bridge)
        head.addView(view, FrameLayout.LayoutParams(dp(HEAD_DP), dp(HEAD_DP)))
        // A clear sheet over the page takes every touch, so a drag moves the
        // window instead of turning the head, and a tap is ours to interpret.
        val touch = View(context)
        head.addView(touch, FrameLayout.LayoutParams(dp(HEAD_DP), dp(HEAD_DP)))
        touch.setOnTouchListener(Gestures())

        val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 5
            maxWidth = dp(230)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(225, 10, 18, 32))
                setStroke(dp(1), Color.argb(150, 79, 216, 255))
            }
            visibility = View.GONE
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(text)
        }

        runCatching { windows.addView(column, params) }
            .onFailure {
                runCatching { view.destroy() }
                return
            }
        root = column
        web = view
        bubble = text
        isShowing = true
        render(brain.ui.value)
    }

    private fun hide() {
        val column = root ?: return
        hideBubble?.cancel()
        runCatching { windows.removeView(column) }
        runCatching { web?.destroy() }
        root = null
        web = null
        bubble = null
        shownText = ""
        isShowing = false
        // A page that is gone starts again from scratch when it comes back.
        bridge.reset()
    }

    fun destroy() {
        hide()
        scope.cancel()
    }

    private fun render(ui: HomeUiState) {
        val view = web ?: return
        bridge.push(view, ui.state, ui.amplitude)

        val text = when {
            ui.pendingConfirmation != null -> "يحتاج تأكيدك — انقر لفتح جارفيس"
            !ui.lastError.isNullOrBlank() -> ui.lastError
            !ui.activity.isNullOrBlank() -> ui.activity
            else -> ui.transcript.takeLast(BUBBLE_CHARS)
        }.orEmpty().trim()

        val label = bubble ?: return
        if (text.isEmpty()) {
            if (ui.state == AssistantState.IDLE && shownText.isEmpty()) label.visibility = View.GONE
            return
        }
        if (text != shownText) {
            shownText = text
            label.text = text
            label.visibility = View.VISIBLE
        }
        // Stay while he is working; fade a few seconds after he is done.
        hideBubble?.cancel()
        if (ui.state == AssistantState.IDLE || ui.state == AssistantState.SUCCESS || ui.state == AssistantState.ERROR) {
            hideBubble = scope.launch {
                delay(BUBBLE_LINGER_MS)
                label.visibility = View.GONE
                shownText = ""
            }
        }
    }

    private fun onTap() {
        val ui = brain.ui.value
        when {
            ui.pendingConfirmation != null -> openApp()
            ui.state.isBusy -> brain.cancel()
            else -> brain.toggleMic(!ui.micOn)
        }
    }

    private fun onLongPress() {
        if (brain.ui.value.state.isBusy) return
        val english = GroqTtsProvider.isEnglishVoice(brain.settings.value.voice)
        brain.submit(
            if (english) {
                "Look at my screen now and tell me, briefly, what matters on it."
            } else {
                "انظر إلى شاشتي الآن وأخبرني باختصار بالمهمّ فيها."
            },
        )
    }

    private fun openApp() {
        runCatching {
            service.startActivity(
                Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), service.resources.displayMetrics).toInt()

    /** Tells a tap from a long press from a drag. */
    private inner class Gestures : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downAt = 0L
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    downAt = event.eventTime
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        root?.let { runCatching { windows.updateViewLayout(it, params) } }
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) {
                    if (event.eventTime - downAt >= LONG_PRESS_MS) onLongPress() else onTap()
                }
            }
            return true
        }
    }

    companion object {
        private const val HEAD_DP = 132
        private const val BUBBLE_CHARS = 170
        private const val BUBBLE_LINGER_MS = 7_000L
        private const val LONG_PRESS_MS = 550L

        /** True while the head is on screen outside the app. */
        @Volatile
        var isShowing: Boolean = false
            private set

        fun attach(service: JarvisAccessibilityService): FloatingAvatar {
            val brain = EntryPointAccessors
                .fromApplication(service.applicationContext, BrainEntryPoint::class.java)
                .brain()
            return FloatingAvatar(service, brain).also { it.start() }
        }
    }
}
