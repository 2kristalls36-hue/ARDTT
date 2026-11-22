package com.ardtt.app.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.ardtt.app.ui.exceptions.AppExceptionRow
import com.ardtt.app.ui.exceptions.AppExceptionRowStub
import com.ardtt.app.ui.exceptions.ExceptionAppItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The loading stub must be exactly as tall as a loaded app row, with and without glass. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xxhdpi")
class AppRowStubTest {
    @get:Rule val compose = createComposeRule()

    private fun check(label: String, glass: Boolean) {
        compose.setContent {
            GlassHost(theme = "light", glass = glass) {
                Column {
                    Box(Modifier.testTag("row")) {
                        AppExceptionRow(ExceptionAppItem("Пример", "org.example.app", null, false), isSelected = false, onClick = {})
                    }
                    Box(Modifier.testTag("stub")) {
                        AppExceptionRowStub(Brush.horizontalGradient(listOf(Color.Gray, Color.LightGray)), 0.6f, 0.4f)
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        val stub = compose.onNodeWithTag("stub").getUnclippedBoundsInRoot()
        assertEquals("height $label", (row.bottom - row.top).value, (stub.bottom - stub.top).value, 0.01f)
    }

    @Test fun sameHeight() = check("1.0", glass = false)
    @Test fun sameHeightGlass() = check("1.0 glass", glass = true)
    @Test @Config(fontScale = 1.3f) fun sameHeightLargeFont() = check("1.3", glass = false)
    @Test @Config(fontScale = 1.3f) fun sameHeightLargeFontGlass() = check("1.3 glass", glass = true)
}
