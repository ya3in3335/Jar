package com.jarvis.ai.feature.home

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.jarvis.ai.core.agent.AssistantState
import com.jarvis.ai.ui.theme.colorFor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * The assistant orb: a glowing sphere whose interior flows with the voice.
 *
 * Drawn entirely with maths — no bitmap, no video, nothing to scale badly on
 * a different screen. The interior is a stack of closed harmonic curves whose
 * distortion is driven by real audio amplitude, so the surface moves with
 * what is actually being heard or said rather than on a fixed loop.
 */

private const val LAYERS = 18
private const val POINTS = 96
private const val DOTS = 72

@Composable
fun JarvisOrb(
    state: AssistantState,
    amplitude: Float,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = colorFor(state)

    // One frame-clock subscription drives the whole orb.
    val frame: State<Long> = produceState(0L, reducedMotion) {
        while (true) {
            withInfiniteAnimationFrameMillis { value = it }
            if (reducedMotion) delay(60)
        }
    }
    val millis by frame
    val t = millis / 1000f

    // Smoothed level, so a spiky microphone does not make the orb jitter.
    val level = remember { FloatArray(1) }
    val target = amplitude.coerceIn(0f, 1f)
    level[0] += (target - level[0]) * if (target > level[0]) 0.35f else 0.08f
    val energy = level[0]

    val active = state in setOf(
        AssistantState.LISTENING,
        AssistantState.SPEAKING,
        AssistantState.THINKING,
        AssistantState.EXECUTING,
    )

    // Reused across frames: rebuilding these per frame would churn memory.
    val clipShape = remember { Path() }
    val flowShape = remember { Path() }

    Canvas(modifier = modifier) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val maxR = minOf(size.width, size.height) / 2f

        // Breathing: slow when idle, pushed outward by the voice.
        val breathe = 1f + 0.02f * sin(t * 1.1f) + energy * 0.05f
        val ringR = maxR * 0.78f * breathe
        val bodyR = ringR * 0.90f

        drawAmbientGlow(centre, ringR, accent, energy, active)
        drawSphereBody(centre, bodyR, accent)

        clipPath(circlePath(clipShape, centre, bodyR)) {
            drawFlowField(flowShape, centre, bodyR, accent, t, energy, active, reducedMotion)
        }

        drawRimLight(centre, bodyR, accent, energy)
        drawOuterRing(centre, ringR, accent, energy)
        drawDotRing(centre, ringR * 1.10f, accent, t, energy, reducedMotion)
    }
}

// --------------------------------------------------------------------- pieces

/** Soft bloom behind everything, so the orb sits in light rather than on black. */
private fun DrawScope.drawAmbientGlow(
    centre: Offset,
    radius: Float,
    accent: Color,
    energy: Float,
    active: Boolean,
) {
    val reach = radius * (1.75f + energy * 0.35f)
    val strength = (if (active) 0.30f else 0.18f) + energy * 0.22f
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to accent.copy(alpha = strength * 0.55f),
            0.45f to accent.copy(alpha = strength * 0.22f),
            1.0f to Color.Transparent,
            center = centre,
            radius = reach,
        ),
        radius = reach,
        center = centre,
    )
}

/** The dark glassy interior the flow lines are drawn onto. */
private fun DrawScope.drawSphereBody(centre: Offset, radius: Float, accent: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to lerp(Color(0xFF071523), accent, 0.10f),
            0.70f to Color(0xFF040A14),
            1.0f to Color(0xFF02060C),
            center = Offset(centre.x - radius * 0.2f, centre.y - radius * 0.25f),
            radius = radius * 1.4f,
        ),
        radius = radius,
        center = centre,
    )
}

/**
 * The flowing interior: nested closed curves whose radius is modulated by a
 * few harmonics. Phase advances with time; amplitude widens the swing, which
 * is what makes the surface look like it is reacting rather than looping.
 */
private fun DrawScope.drawFlowField(
    path: Path,
    centre: Offset,
    radius: Float,
    accent: Color,
    t: Float,
    energy: Float,
    active: Boolean,
    reducedMotion: Boolean,
) {
    val layers = if (reducedMotion) LAYERS / 2 else LAYERS
    val points = if (reducedMotion) POINTS / 2 else POINTS
    val swing = (if (active) 0.16f else 0.09f) + energy * 0.30f
    val speed = if (reducedMotion) 0.35f else 1f

    for (i in 0 until layers) {
        val f = i / (layers - 1f)
        val base = radius * (0.22f + 0.76f * f)
        val phase = t * speed * (0.9f + f * 0.5f) + i * 0.38f

        path.rewind()
        for (j in 0..points) {
            val a = (j.toFloat() / points) * 2f * PI.toFloat()

            // Three harmonics: a slow lobe, a faster ripple, a counter-rotation.
            val wobble =
                0.52f * sin(3f * a + phase) +
                    0.30f * sin(5f * a - phase * 1.27f) +
                    0.18f * sin(2f * a + phase * 0.63f)

            val r = base * (1f + swing * wobble)
            val x = centre.x + cos(a) * r
            // Squashed vertically: reads as a sphere seen slightly from above.
            val y = centre.y + sin(a) * r * 0.93f

            if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        // Mid layers brightest, so the sphere has a lit core.
        val lit = 1f - kotlin.math.abs(f - 0.55f) * 1.6f
        val alpha = (0.10f + 0.34f * lit.coerceIn(0f, 1f)) * (0.75f + energy * 0.5f)

        drawPath(
            path = path,
            color = lerp(accent, Color.White, 0.18f * lit.coerceIn(0f, 1f)).copy(
                alpha = alpha.coerceIn(0f, 0.85f),
            ),
            style = Stroke(width = 1.15f.dp.toPx()),
        )
    }
}

/** Inner edge light, brightest low-left, giving the sphere its volume. */
private fun DrawScope.drawRimLight(centre: Offset, radius: Float, accent: Color, energy: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            0.80f to Color.Transparent,
            0.96f to accent.copy(alpha = 0.30f + energy * 0.25f),
            1.0f to lerp(accent, Color.White, 0.5f).copy(alpha = 0.55f + energy * 0.3f),
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
    )

    // A short bright arc, the specular catch.
    val sweep = 70f
    drawArc(
        color = lerp(accent, Color.White, 0.65f).copy(alpha = 0.75f),
        startAngle = 118f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = 2.2f.dp.toPx()),
    )
}

/** The hard outer ring, with a few wider passes standing in for a bloom. */
private fun DrawScope.drawOuterRing(centre: Offset, radius: Float, accent: Color, energy: Float) {
    val passes = listOf(
        9f to 0.07f,
        5f to 0.13f,
        2.6f to 0.28f,
    )
    for ((width, alpha) in passes) {
        drawCircle(
            color = accent.copy(alpha = alpha * (0.8f + energy * 0.6f)),
            radius = radius,
            center = centre,
            style = Stroke(width = width.dp.toPx()),
        )
    }
    drawCircle(
        color = lerp(accent, Color.White, 0.35f).copy(alpha = 0.95f),
        radius = radius,
        center = centre,
        style = Stroke(width = 1.4f.dp.toPx()),
    )
}

/**
 * The dotted ring. Brightness sweeps around rather than every dot blinking,
 * which reads as rotation without actually rotating anything.
 */
private fun DrawScope.drawDotRing(
    centre: Offset,
    radius: Float,
    accent: Color,
    t: Float,
    energy: Float,
    reducedMotion: Boolean,
) {
    val dots = if (reducedMotion) DOTS / 2 else DOTS
    val sweepSpeed = if (reducedMotion) 0.3f else 0.9f
    val dotR = 1.5f.dp.toPx()

    rotate(degrees = t * 6f, pivot = centre) {
        for (i in 0 until dots) {
            val a = (i.toFloat() / dots) * 2f * PI.toFloat()
            val wave = sin(a * 1f - t * sweepSpeed * 2f)
            val alpha = (0.12f + 0.55f * ((wave + 1f) / 2f)) * (0.7f + energy * 0.6f)
            drawCircle(
                color = accent.copy(alpha = alpha.coerceIn(0f, 0.9f)),
                radius = dotR,
                center = Offset(centre.x + cos(a) * radius, centre.y + sin(a) * radius * 0.97f),
            )
        }
    }
}

private fun circlePath(path: Path, centre: Offset, radius: Float): Path {
    path.rewind()
    path.addOval(
        Rect(
            left = centre.x - radius,
            top = centre.y - radius,
            right = centre.x + radius,
            bottom = centre.y + radius,
        ),
    )
    return path
}
