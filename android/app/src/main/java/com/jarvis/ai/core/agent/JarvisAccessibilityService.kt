package com.jarvis.ai.core.agent

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * UI automation through the accessibility APIs.
 *
 * Only ever used for automation the user asked for and can see. The service
 * does not hide its activity, does not read password fields, and does not
 * attempt to bypass authentication or protected screens.
 *
 * Phase 4 will connect this to the tool dispatcher. Right now it tracks the
 * foreground package and exposes node lookup, which is what the screen
 * reader and "tap that button" tools will build on. Nothing here is wired
 * to the agent yet — see docs/BUILD_STATUS.md.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected")
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

    /**
     * Find visible nodes whose text or content description contains [query].
     *
     * Matching is on what the user can actually see, so a caller can say
     * "tap إرسال" without knowing view ids.
     */
    fun findByText(query: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val needle = IntentRouter.normalize(query)
        val found = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || found.size >= MAX_RESULTS) return
            // Never inspect password fields.
            if (!node.isPassword) {
                val haystack = listOfNotNull(node.text, node.contentDescription)
                    .joinToString(" ") { IntentRouter.normalize(it.toString()) }
                if (needle.isNotEmpty() && haystack.contains(needle)) found.add(node)
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)
        return found
    }

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

    companion object {
        private const val TAG = "JarvisA11y"
        private const val MAX_DEPTH = 40
        private const val MAX_RESULTS = 20
        private const val MAX_CLICK_HOPS = 6

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
