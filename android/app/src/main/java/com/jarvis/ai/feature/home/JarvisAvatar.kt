package com.jarvis.ai.feature.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.jarvis.ai.core.agent.AssistantState
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * The assistant's face: a 3D head that fades out below the neck.
 *
 * It replaces the orb on the home screen and takes the same inputs. The head
 * is a WebGL scene bundled in `assets/jarvis` (source in `android/avatar-web`)
 * and runs fully offline: every request that is not one of those assets is
 * answered with an empty response, so the view cannot reach the network.
 *
 * The orb stays as the fallback, used when the device has no usable WebView
 * and when the user asked for reduced motion.
 */
@Composable
fun JarvisAvatar(
    state: AssistantState,
    amplitude: Float,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    var failed by remember { mutableStateOf(false) }
    if (failed || reducedMotion) {
        JarvisOrb(state = state, amplitude = amplitude, reducedMotion = reducedMotion, modifier = modifier)
        return
    }

    val bridge = remember { AvatarBridge() }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            try {
                createAvatarView(context, bridge)
            } catch (_: Throwable) {
                // No WebView provider on this device (or it is mid-update).
                failed = true
                View(context)
            }
        },
        update = { view -> (view as? WebView)?.let { bridge.push(it, state, amplitude) } },
        onRelease = { view -> (view as? WebView)?.destroy() },
    )
}

private const val AVATAR_HOST = "appassets.androidplatform.net"
private const val AVATAR_URL = "https://$AVATAR_HOST/assets/jarvis/index.html"

/** Fewest milliseconds between two level updates; the page smooths between them. */
private const val LEVEL_INTERVAL_MS = 60L

/** Pushes state and voice level into the page, and only when they change. */
internal class AvatarBridge {
    private var ready = false
    private var sentState: AssistantState? = null
    private var wantedState: AssistantState = AssistantState.IDLE
    private var sentLevel = -1f
    private var levelAt = 0L

    /** The page was thrown away; the next one starts from nothing. */
    fun reset() {
        ready = false
        sentState = null
        sentLevel = -1f
    }

    fun onReady(view: WebView) {
        ready = true
        sentState = null
        sendState(view)
    }

    fun push(view: WebView, state: AssistantState, amplitude: Float) {
        wantedState = state
        if (!ready) return
        sendState(view)

        val level = amplitude.coerceIn(0f, 1f)
        val now = System.currentTimeMillis()
        val moved = kotlin.math.abs(level - sentLevel) > 0.015f
        // Keep re-sending a non-zero level: the page treats a stale one as silence.
        if (now - levelAt >= LEVEL_INTERVAL_MS && (moved || level > 0f)) {
            sentLevel = level
            levelAt = now
            view.evaluateJavascript(
                "window.Jarvis&&Jarvis.setLevel(${String.format(Locale.US, "%.3f", level)})",
                null,
            )
        }
    }

    private fun sendState(view: WebView) {
        val state = wantedState
        if (state == sentState) return
        sentState = state
        // Enum names are fixed identifiers, so this is safe to inline.
        val name = state.name.lowercase(Locale.US)
        view.evaluateJavascript(
            "window.Jarvis?Jarvis.setState('$name'):(window.JARVIS_INIT='$name')",
            null,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled") // The only script that runs is the one shipped in assets.
internal fun createAvatarView(context: Context, bridge: AvatarBridge): WebView {
    val assets = WebViewAssetLoader.Builder()
        .setDomain(AVATAR_HOST)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    return WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER

        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportZoom(false)
        settings.mediaPlaybackRequiresUserGesture = true

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                // blob: and data: are how the page hands textures to WebGL; they never leave the process.
                val scheme = request.url.scheme
                if (scheme == "blob" || scheme == "data") return null
                return assets.shouldInterceptRequest(request.url) ?: emptyResponse()
            }

            // The page has no links; refuse any navigation away from it.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageFinished(view: WebView, url: String?) {
                bridge.onReady(view)
            }
        }
        loadUrl(AVATAR_URL)
    }
}

private fun emptyResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
