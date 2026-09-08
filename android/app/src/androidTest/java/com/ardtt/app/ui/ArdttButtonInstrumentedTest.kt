package com.ardtt.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.theme.ArdttTheme
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented checks for shared controls. Requires a device/emulator;
 * this environment does not run them.
 */
class ArdttButtonInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun primaryButtonKeepsItsLabel() {
        composeRule.setContent {
            ArdttTheme {
                ArdttButton(
                    text = "Подключиться",
                    onClick = {},
                    variant = ArdttButtonVariant.Primary,
                )
            }
        }
        composeRule.onNodeWithText("Подключиться").assertIsDisplayed()
    }
}
