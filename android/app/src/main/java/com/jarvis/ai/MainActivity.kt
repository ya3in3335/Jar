package com.jarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.jarvis.ai.feature.home.HomeScreen
import com.jarvis.ai.feature.settings.SettingsScreen
import com.jarvis.ai.ui.theme.JarvisTheme
import dagger.hilt.android.AndroidEntryPoint

private enum class Screen { HOME, SETTINGS }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JarvisTheme {
                // The interface is Arabic-first, so force RTL rather than
                // depending on the device locale.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

                        BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

                        AnimatedContent(
                            targetState = screen,
                            transitionSpec = {
                                // Settings arrives from the leading edge; RTL
                                // flips the direction for us.
                                val forward = targetState == Screen.SETTINGS
                                val width = if (forward) 1 else -1
                                (
                                    slideInHorizontally(tween(320)) { full -> width * full } +
                                        fadeIn(tween(220))
                                    ) togetherWith (
                                    slideOutHorizontally(tween(320)) { full -> -width * full } +
                                        fadeOut(tween(180))
                                    )
                            },
                            label = "screen",
                        ) { target ->
                            when (target) {
                                Screen.HOME -> HomeScreen(onOpenSettings = { screen = Screen.SETTINGS })
                                Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
                            }
                        }
                    }
                }
            }
        }
    }
}
