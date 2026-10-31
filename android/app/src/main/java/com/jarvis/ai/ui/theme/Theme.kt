package com.jarvis.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jarvis.ai.core.agent.AssistantState

// Dark, premium, minimal. Depth comes from layered translucency and soft
// glow rather than sci-fi HUD ornament (spec section 6).
val BgDeep = Color(0xFF05070D)
val BgPanel = Color(0xFF0B1018)
val BgRaised = Color(0xFF121A26)
val BorderSubtle = Color(0xFF1E2A3A)
val TextPrimary = Color(0xFFE6EEF8)
val TextDim = Color(0xFF8FA3B8)
val Accent = Color(0xFF3DDCFF)
val AccentSoft = Color(0xFF1B6B85)
val SuccessGreen = Color(0xFF3DDC97)
val WarnAmber = Color(0xFFFFC857)
val ErrorRed = Color(0xFFFF5D6C)

/** Accent colour per state — the one signal the eye tracks. */
fun colorFor(state: AssistantState): Color = when (state) {
    AssistantState.IDLE -> AccentSoft
    AssistantState.LISTENING -> Accent
    AssistantState.UNDERSTANDING -> Color(0xFF7AA2FF)
    AssistantState.THINKING -> Color(0xFFA07AFF)
    AssistantState.SPEAKING -> Accent
    AssistantState.EXECUTING -> WarnAmber
    AssistantState.SUCCESS -> SuccessGreen
    AssistantState.ERROR -> ErrorRed
    AssistantState.DISCONNECTED -> Color(0xFF4A5668)
}

private val JarvisColors = darkColorScheme(
    primary = Accent,
    onPrimary = BgDeep,
    primaryContainer = AccentSoft,
    secondary = Color(0xFF7AA2FF),
    background = BgDeep,
    onBackground = TextPrimary,
    surface = BgPanel,
    onSurface = TextPrimary,
    surfaceVariant = BgRaised,
    onSurfaceVariant = TextDim,
    outline = BorderSubtle,
    error = ErrorRed,
)

private val JarvisTypography = Typography(
    displaySmall = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

/**
 * The app is dark-only on purpose: a light variant of this interface would
 * need a second, separately designed visual language, and the spec asks for
 * a dark premium surface.
 */
@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JarvisColors, typography = JarvisTypography, content = content)
}
