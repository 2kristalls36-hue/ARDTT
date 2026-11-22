package com.ardtt.app.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.ardtt.app.ui.components.layout.ArdttNavItem
import com.ardtt.app.ui.components.layout.ArdttNavigationBar
import com.ardtt.app.ui.components.layout.tabMagnet
import com.ardtt.app.ui.motion.Magnet
import com.ardtt.app.ui.theme.ArdttTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h300dp-xhdpi")
class NavDragTest {
    @get:Rule val compose = createComposeRule()

    private fun bar(glass: Boolean, onRoute: (String) -> Unit, route: () -> String) {
        compose.setContent {
            GlassHost(theme = "light", glass = glass) {
                Box(Modifier.fillMaxWidth()) {
                    ArdttNavigationBar(
                        items = listOf("a", "b", "c", "d").map { ArdttNavItem(it, "Tab $it", Icons.Outlined.Home) },
                        selectedRoute = route(),
                        onSelect = onRoute,
                    )
                }
            }
        }
    }

    private fun dragAcross(glass: Boolean) {
        var route by mutableStateOf("a")
        bar(glass, { route = it }) { route }
        val a = compose.onNodeWithText("Tab a").fetchSemanticsNode().boundsInRoot.center
        val c = compose.onNodeWithText("Tab c").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { swipe(Offset(a.x, a.y), Offset(c.x + 10f, c.y), durationMillis = 400) }
        compose.waitForIdle()
        assertEquals("c", route)
    }

    @Test fun dragAcrossTabsSelectsWhereReleased() = dragAcross(glass = false)
    @Test fun dragAcrossTabsOnGlass() = dragAcross(glass = true)

    @Test fun dragBackAfterSwitchingTabs() {
        // Regression: tap to the second tab, then drag back to the first one.
        var route by mutableStateOf("a")
        bar(false, { route = it }) { route }
        compose.onNodeWithText("Tab b").performClick()
        compose.waitForIdle()
        assertEquals("b", route)
        val a = compose.onNodeWithText("Tab a").fetchSemanticsNode().boundsInRoot.center
        val b = compose.onNodeWithText("Tab b").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { swipe(Offset(b.x, b.y), Offset(a.x - 10f, a.y), durationMillis = 400) }
        compose.waitForIdle()
        assertEquals("a", route)
    }

    @Test fun tapStillSelects() {
        var route by mutableStateOf("a")
        compose.setContent {
            ArdttTheme {
                ArdttNavigationBar(
                    items = listOf("a", "b").map { ArdttNavItem(it, "Tab $it", Icons.Outlined.Home) },
                    selectedRoute = route,
                    onSelect = { route = it },
                )
            }
        }
        compose.onNodeWithText("Tab b").performClick()
        compose.waitForIdle()
        assertEquals("b", route)
    }
}

class MagnetTest {
    @Test fun magnetBetweenTabs() {
        assertEquals(2f, tabMagnet(2.05f), 1e-6f)
        assertEquals(1.5f, tabMagnet(1.5f), 1e-6f)
        val far = tabMagnet(1.62f) - tabMagnet(1.6f)
        val near = tabMagnet(1.88f) - tabMagnet(1.86f)
        assertTrue(near > far)
        var last = -1f
        for (i in 0..3000) { val v = tabMagnet(i / 1000f); assertTrue(v >= last - 1e-6f); last = v }
    }

    @Test fun releaseAccelerates() {
        assertTrue(Magnet.Easing.transform(0.5f) < 0.2f)
        assertTrue(Magnet.durationMillis(0.5f, 1f) > Magnet.durationMillis(0.1f, 1f))
    }
}
