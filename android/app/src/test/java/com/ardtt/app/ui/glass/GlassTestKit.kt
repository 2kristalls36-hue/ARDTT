package com.ardtt.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.rememberGraphicsLayer
import com.ardtt.app.ui.components.surface.GlassBackdrop
import com.ardtt.app.ui.components.surface.LocalGlassBackdrop
import com.ardtt.app.ui.components.surface.glassBackdropSource
import com.ardtt.app.ui.motion.LocalAppHaptics
import com.ardtt.app.ui.motion.rememberAppHaptics
import com.ardtt.app.ui.theme.ArdttTheme

/**
 * Screen host for tests: theme, optional glass backdrop recorded from [backdrop]
 * (what lies behind the chrome) and the chrome on top, outside the recording.
 */
@Composable
fun GlassHost(
    theme: String,
    glass: Boolean,
    transparency: Float = 0.5f,
    backdrop: @Composable BoxScope.() -> Unit = {},
    chrome: @Composable BoxScope.() -> Unit,
) {
    ArdttTheme(themeMode = theme) {
        val layer = rememberGraphicsLayer()
        val root = remember(layer) { GlassBackdrop(layer) }
        root.transparency = transparency
        root.rim = MaterialTheme.colorScheme.primary
        val provided = root.takeIf { glass }
        CompositionLocalProvider(
            LocalGlassBackdrop provides provided,
            LocalAppHaptics provides rememberAppHaptics(true),
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Box(Modifier.fillMaxSize().glassBackdropSource(provided), content = backdrop)
                chrome()
            }
        }
    }
}
