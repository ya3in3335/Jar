package com.jarvis.ai.core.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * UI automation through the accessibility APIs.
 *
 * This is how the assistant actually touches the screen: finding a visible
 * control, tapping it, scrolling a list, typing into a field, going back.
 * Opening an app with an intent gets you to a screen; everything after that
 * needs this.
 *
 * Only ever used for automation the user asked for and can see. The service
 * never reads password fields, never captures key events (the config does
 * not request it), and never tries to get past a lock screen or an
 * authentication prompt. It is off until the user turns it on in system
 * settings, and the app does not pretend otherwise: every tool reports
 * honestly when the service is not connected.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foregroundPackage = packageName
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "accessibility service interrupted")
    }

    // ---------------------------------------------------------------- reading

    /**
     * What is on screen right now, as the model needs to see it.
     *
     * Only nodes a human could actually act on or read: visible, with text
     * or a content description. Password fields are skipped entirely — not
     * masked, not summarised, skipped.
     */
    fun describeScreen(limit: Int = MAX_RESULTS): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<ScreenElement>()
        val bounds = Rect()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || out.size >= limit) return
            if (!node.isPassword && node.isVisibleToUser) {
                val label = listOfNotNull(node.text, node.contentDescription)
                    .joinToString(" ") { it.toString() }
                    .trim()
                if (label.isNotEmpty()) {
                    node.getBoundsInScreen(bounds)
                    out += ScreenElement(
                        text = label.take(MAX_LABEL),
                        clickable = node.isClickable || hasClickableAncestor(node),
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        checked = if (node.isCheckable) node.isChecked else null,
                        centerX = bounds.centerX(),
                        centerY = bounds.centerY(),
                    )
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)
        return out
    }

    /**
     * Find visible nodes whose text or content description contains [query].
     *
     * Matching is on what the user can actually see, so a caller can say
     * "tap إرسال" without knowing view ids. An exact match is preferred
     * over a substring one: on a screen with both "حفظ" and "حفظ باسم",
     * asking for "حفظ" should get the button that says exactly that.
     */
    fun findByText(query: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val needle = IntentRouter.normalize(query)
        if (needle.isEmpty()) return emptyList()

        val exact = mutableListOf<AccessibilityNodeInfo>()
        val partial = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (exact.size + partial.size >= MAX_RESULTS) return
            if (!node.isPassword) {
                val haystack = listOfNotNull(node.text, node.contentDescription)
                    .joinToString(" ") { IntentRouter.normalize(it.toString()) }
                when {
                    haystack == needle -> exact.add(node)
                    haystack.contains(needle) -> partial.add(node)
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)
        return exact + partial
    }

    // ---------------------------------------------------------------- acting

    /** Click the nearest clickable ancestor of [node], or the node itself. */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_CLICK_HOPS) {
            if (current.isClickable && current.isEnabled) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            hops++
        }
        return false
    }

    /**
     * Tap the first element matching [query].
     *
     * Falls back to a tap at the element's centre when no ancestor declares
     * itself clickable, which is common in Compose and Flutter screens where
     * the touch target is a gesture modifier rather than a clickable view.
     */
    suspend fun tapText(query: String): TapOutcome {
        val matches = findByText(query)
        if (matches.isEmpty()) return TapOutcome.NotFound
        val node = matches.first()

        if (clickNode(node)) return TapOutcome.Clicked

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return TapOutcome.NotActionable
        return if (tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())) {
            TapOutcome.Tapped
        } else {
            TapOutcome.NotActionable
        }
    }

    /** A synthetic tap at screen coordinates. Requires canPerformGestures. */
    suspend fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_MS)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Scroll the screen.
     *
     * Tries the scrollable node's own action first, because that respects
     * the app's own paging and fling behaviour. A swipe is the fallback for
     * surfaces that do not expose scroll actions.
     */
    suspend fun scroll(direction: ScrollDirection, screenWidth: Int, screenHeight: Int): Boolean {
        val scrollable = findScrollable(rootInActiveWindow, 0)
        if (scrollable != null) {
            val action = when (direction) {
                ScrollDirection.DOWN, ScrollDirection.RIGHT ->
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                ScrollDirection.UP, ScrollDirection.LEFT ->
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (scrollable.performAction(action)) return true
        }

        // Swipe the opposite way to the content movement: to see what is
        // below, the finger goes up.
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val dx = screenWidth * SWIPE_FRACTION / 2f
        val dy = screenHeight * SWIPE_FRACTION / 2f
        val (from, to) = when (direction) {
            ScrollDirection.DOWN -> (cx to cy + dy) to (cx to cy - dy)
            ScrollDirection.UP -> (cx to cy - dy) to (cx to cy + dy)
            ScrollDirection.LEFT -> (cx - dx to cy) to (cx + dx to cy)
            ScrollDirection.RIGHT -> (cx + dx to cy) to (cx - dx to cy)
        }

        val path = Path().apply {
            moveTo(from.first, from.second)
            lineTo(to.first, to.second)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, SWIPE_MS)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Put [text] into the focused editable field, or the first one on screen.
     *
     * Uses ACTION_SET_TEXT rather than synthesising key events, so it works
     * with any keyboard and cannot be mistaken for key capture.
     */
    fun typeText(text: String, replace: Boolean): Boolean {
        val target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable && !it.isPassword }
            ?: findEditable(rootInActiveWindow, 0)
            ?: return false

        val value = if (replace) text else (target.text?.toString() ?: "") + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Back, home, recents, notification shade. */
    fun globalAction(action: GlobalAction): Boolean = performGlobalAction(
        when (action) {
            GlobalAction.BACK -> GLOBAL_ACTION_BACK
            GlobalAction.HOME -> GLOBAL_ACTION_HOME
            GlobalAction.RECENTS -> GLOBAL_ACTION_RECENTS
            GlobalAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
        },
    )

    /**
     * Wait until [query] appears on screen, up to [timeoutMs].
     *
     * The step that makes multi-screen work possible: open an app, wait for
     * its own screen to draw, then tap. Without it every tap races the app's
     * startup and fails about half the time.
     */
    suspend fun waitForText(query: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (findByText(query).isNotEmpty()) return true
            delay(POLL_MS)
        }
        return false
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { continuation ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val accepted = runCatching { dispatchGesture(gesture, callback, null) }
                .getOrDefault(false)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current = node.parent
        var hops = 0
        while (current != null && hops < MAX_CLICK_HOPS) {
            if (current.isClickable) return true
            current = current.parent
            hops++
        }
        return false
    }

    private fun findScrollable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null
        if (node.isEditable && node.isVisibleToUser && !node.isPassword) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    /** One actionable thing on screen, flattened for the model. */
    data class ScreenElement(
        val text: String,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checked: Boolean?,
        val centerX: Int,
        val centerY: Int,
    ) {
        /** Compact enough that a whole screen fits in a tool result. */
        fun describe(): String = buildString {
            append(text)
            val flags = buildList {
                if (clickable) add("قابل للنقر")
                if (editable) add("حقل إدخال")
                if (scrollable) add("قابل للتمرير")
                checked?.let { add(if (it) "مُحدَّد" else "غير مُحدَّد") }
            }
            if (flags.isNotEmpty()) append(" [").append(flags.joinToString("، ")).append("]")
        }
    }

    enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

    enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS }

    /** Why a tap did or did not land, so the model is told the truth. */
    enum class TapOutcome { Clicked, Tapped, NotFound, NotActionable }

    companion object {
        private const val TAG = "JarvisA11y"
        private const val MAX_DEPTH = 40
        private const val MAX_RESULTS = 60
        private const val MAX_CLICK_HOPS = 6
        private const val MAX_LABEL = 80
        private const val TAP_MS = 60L
        private const val SWIPE_MS = 220L
        private const val SWIPE_FRACTION = 0.7f
        private const val POLL_MS = 150L

        /** Null whenever the user has not enabled the service. */
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        @Volatile
        var foregroundPackage: String? = null
            private set

        val isEnabled: Boolean get() = instance != null
    }
}
