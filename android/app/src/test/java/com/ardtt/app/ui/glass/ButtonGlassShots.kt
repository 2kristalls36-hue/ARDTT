package com.ardtt.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.components.control.ArdttPrimaryButton
import com.ardtt.app.ui.components.layout.ArdttFeedScaffold
import com.ardtt.app.ui.components.layout.ArdttTabHeader
import com.ardtt.app.ui.components.surface.ArdttFloatingShell
import com.ardtt.app.ui.components.surface.GlassBackdrop
import com.ardtt.app.ui.components.surface.LocalButtonGlass
import com.ardtt.app.ui.components.surface.glassBackdropSource
import com.ardtt.app.ui.components.surface.ArdttSectionCard
import com.ardtt.app.ui.tunnel.PowerFace
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Glass controls keep their colours: sticky CTA over a feed, power button over a wallpaper. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class ButtonGlassShots {
    @get:Rule val compose = createComposeRule()

    private fun stickyShot(theme: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GlassHost(theme = theme, glass = true, backdrop = {
                ArdttFeedScaffold(
                    header = { ArdttTabHeader("Туннель") },
                    stickyContent = { ArdttPrimaryButton(text = "Подключить", onClick = {}) },
                ) {
                    repeat(6) { i ->
                        ArdttSectionCard {
                            Text("Карточка ${i + 1}", style = MaterialTheme.typography.titleMedium)
                            Text("Текст под кнопкой должен преломляться стеклом.")
                        }
                    }
                }
            }) {}
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/glass/sticky_button_$theme.png")
    }

    @Test fun stickyLight() = stickyShot("light")
    @Test fun stickyDark() = stickyShot("dark")

    private fun power(theme: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GlassHost(theme = theme, glass = true) {
                val layer = rememberGraphicsLayer()
                val wall = remember(layer) { GlassBackdrop(layer) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .glassBackdropSource(wall)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF0E7490), Color(0xFFF59E0B), Color(0xFF7C3AED)),
                            ),
                        ),
                )
                CompositionLocalProvider(LocalButtonGlass provides wall) {
                    PowerFace(
                        modifier = Modifier.align(Alignment.Center).size(180.dp).clip(CircleShape),
                        diameter = 180.dp,
                        shellColor = ArdttFloatingShell.shellColor(),
                        accentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(64.dp))
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/glass/power_button_$theme.png")
    }

    @Test fun powerLight() = power("light")
    @Test fun powerDark() = power("dark")
}
