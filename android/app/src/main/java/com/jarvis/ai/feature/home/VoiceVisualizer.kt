package com.jarvis.ai.feature.home

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.jarvis.ai.core.agent.AssistantState
import com.jarvis.ai.ui.theme.colorFor
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

private const val BAR_COUNT = 28

/**
 * Amplitude-reactive bars with eased motion and a soft glow pass.
 *
 * Driven by real audio amplitude when there is any, and by a slow breathing
 * wave otherwise, so the surface is never frozen. Deliberately not a
 * holographic sphere or radar sweep — spec section 6 rules those out.
 *
 * @param amplitude current RMS level in 0..1 from the audio pipeline.
 * @param reducedMotion when true, animate at a low frame rate and drop the
 *        glow pass, honouring the user's accessibility preference.
 */
@Composable
fun VoiceVisualizer(
    state: AssistantState,
    amplitude: Float,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val levels = remember { FloatArray(BAR_COUNT) }
    val color = colorFor(state)

    val active = state in setOf(
        AssistantState.LISTENING,
        AssistantState.SPEAKING,
        AssistantState.THINKING,
        AssistantState.EXECUTING,
    )

    // One frame-clock subscription; recomposes only this canvas.
    val frame: State<Long> = produceState(0L, reducedMotion) {
        while (true) {
            withInfiniteAnimationFrameMillis { value = it }
            if (reducedMotion) delay(70)
        }
    }
    val millis by frame
    val t = millis / 1000f

    for (i in 0 until BAR_COUNT) {
        // Bell curve so the centre is tallest.
        val centre = 1f - abs(i - (BAR_COUNT - 1) / 2f) / ((BAR_COUNT - 1) / 2f)
        val shape = centre.toDouble().pow(1.6).toFloat()

        val target = when {
            active && amplitude > 0.01f -> {
                val jitter = 0.5f + 0.5f * sin(t * 9f + i * 0.7f)
                amplitude * shape * jitter
            }
            // Working but silent: a travelling wave, not a frozen bar.
            active -> 0.18f * shape * (0.5f + 0.5f * sin(t * 3f - i * 0.35f))
            else -> 0.06f * shape * (0.6f + 0.4f * sin(t * 1.2f - i * 0.2f))
        }

        // Asymmetric easing: rise fast, fall slow — reads as responsive.
        val k = if (target > levels[i]) 0.45f else 0.12f
        levels[i] += (target - levels[i]) * k
    }

    Canvas(modifier = modifier) {
        val mid = size.height / 2f
        val gap = 4.dp.toPx()
        val barWidth = ((size.width - gap * (BAR_COUNT - 1)) / BAR_COUNT).coerceAtLeast(2f)

        if (!reducedMotion) {
            for (i in 0 until BAR_COUNT) {
                val x = i * (barWidth + gap) + barWidth / 2f
                val h = (levels[i] * size.height * 0.8f).coerceAtLeast(2f)
                drawLine(
                    color = color.copy(alpha = 0.18f),
                    start = Offset(x, mid - h / 2f),
                    end = Offset(x, mid + h / 2f),
                    strokeWidth = barWidth * 2.1f,
                    cap = StrokeCap.Round,
                )
            }
        }

        val brush = Brush.verticalGradient(
            0f to lerp(color, Color.White, 0.25f),
            0.5f to color,
            1f to lerp(color, Color.Black, 0.3f),
        )
        for (i in 0 until BAR_COUNT) {
            val x = i * (barWidth + gap) + barWidth / 2f
            val h = (levels[i] * size.height * 0.8f).coerceAtLeast(2f)
            drawLine(
                brush = brush,
                start = Offset(x, mid - h / 2f),
                end = Offset(x, mid + h / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Holder the audio pipeline writes into; the composable reads it each frame. */
class AmplitudeSource {
    private val _value = mutableStateOf(0f)
    val value: Float get() = _value.value

    fun push(rms: Float) {
        _value.value = rms.coerceIn(0f, 1f)
    }
}
