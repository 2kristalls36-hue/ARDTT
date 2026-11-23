package com.ardtt.app.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.ui.components.layout.ArdttNavItem
import com.ardtt.app.ui.components.layout.ArdttNavigationBar
import com.ardtt.app.ui.exceptions.ExceptionsScreen
import com.ardtt.app.ui.settings.SettingsScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real tab screens with the tab bar on top, light / dark, 360 / 411 dp, font ×1 / ×1.3,
 * glass on / off. PNGs land in build/outputs/roborazzi/glass for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GlassTour {
    @get:Rule val compose = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val tabs = listOf(
        ArdttNavItem("tunnel", "Туннель", Icons.Outlined.VpnKey),
        ArdttNavItem("profiles", "Профили", Icons.Outlined.Folder),
        ArdttNavItem("exceptions", "Исключения", Icons.Outlined.FilterList),
        ArdttNavItem("network", "Сеть", Icons.Outlined.Wifi),
        ArdttNavItem("settings", "Настройки", Icons.Outlined.Settings),
    )

    private fun shot(screen: String, theme: String, width: Int, font: Float, glass: Boolean, scroll: Boolean) {
        RuntimeEnvironment.setQualifiers("w${width}dp-h780dp-xxhdpi")
        RuntimeEnvironment.setFontScale(font)
        val settings = AppSettingsRepository(ctx)
        runBlocking { settings.setLiquidGlass(glass) }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GlassHost(
                theme = theme,
                glass = glass,
                backdrop = {
                    when (screen) {
                        "settings" -> SettingsScreen(settings = settings)
                        else -> ExceptionsScreen(settings = settings, onBack = null)
                    }
                },
            ) {
                ArdttNavigationBar(
                    items = tabs,
                    selectedRoute = screen,
                    onSelect = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        if (scroll) {
            compose.onRoot().performTouchInput {
                swipe(Offset(centerX, height * 0.7f), Offset(centerX, height * 0.35f), durationMillis = 300)
            }
            compose.mainClock.advanceTimeBy(1500)
        }
        val tag = "${screen}_${theme}_${width}dp_x$font" + (if (glass) "_glass" else "") + (if (scroll) "_scrolled" else "")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/glass/$tag.png")
    }

    @Test fun settingsGlassLight360() = shot("settings", "light", 360, 1f, glass = true, scroll = true)
    @Test fun settingsGlassDark411() = shot("settings", "dark", 411, 1f, glass = true, scroll = true)
    @Test fun settingsGlassLight360Font() = shot("settings", "light", 360, 1.3f, glass = true, scroll = false)
    @Test fun settingsPlainDark360Font() = shot("settings", "dark", 360, 1.3f, glass = false, scroll = true)
    @Test fun settingsPlainLight411() = shot("settings", "light", 411, 1f, glass = false, scroll = false)
    // Robolectric native rendering exits with SIGSEGV on this frame (search bar on screen
    // glass over the full exceptions screen); smaller scenes with the same parts render.
    // Checked on a device instead.
    @org.junit.Ignore("Robolectric native crash, see comment") @Test fun exceptionsGlassLight411() = shot("exceptions", "light", 411, 1f, glass = true, scroll = false)
    // Robolectric native rendering exits with SIGSEGV on this frame (search bar on screen
    // glass over the full exceptions screen); smaller scenes with the same parts render.
    // Checked on a device instead.
    @org.junit.Ignore("Robolectric native crash, see comment") @Test fun exceptionsGlassDark360Font() = shot("exceptions", "dark", 360, 1.3f, glass = true, scroll = false)
    @Test fun exceptionsPlainDark411Font() = shot("exceptions", "dark", 411, 1.3f, glass = false, scroll = false)
    @Test fun exceptionsPlainLight360() = shot("exceptions", "light", 360, 1f, glass = false, scroll = false)
}
