package com.ardtt.app.ui.components.control

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.ardtt.app.ui.theme.ArdttMotion
import com.ardtt.app.ui.theme.ArdttShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one text input of the app.
 *
 * Twenty-eight call sites configured `OutlinedTextField` by hand with the same
 * shape, single-line default and full width; forms in the deploy flow also
 * repeated a focus-scroll modifier. [ArdttPasswordField] adds the visibility
 * toggle password inputs were missing, [ArdttDigitsField] the numeric keyboard
 * and digit filter ports / limits used to reimplement inline.
 */
@Composable
fun ArdttTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    scrollIntoViewOnFocus: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .then(if (scrollIntoViewOnFocus) Modifier.bringIntoViewWhenFocused() else Modifier),
        enabled = enabled,
        readOnly = readOnly,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = ArdttShapes.Field,
    )
}

/** Password / passphrase input with a show-hide toggle. */
@Composable
fun ArdttPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    scrollIntoViewOnFocus: Boolean = true,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    ArdttTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        supportingText = supportingText,
        isError = isError,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            ArdttButton(
                onClick = { visible = !visible },
                variant = ArdttButtonVariant.Icon,
                icon = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (visible) "Скрыть пароль" else "Показать пароль",
                enabled = enabled,
            )
        },
        scrollIntoViewOnFocus = scrollIntoViewOnFocus,
    )
}

/** Digits-only input (ports, day counts, limits) with the numeric keyboard. */
@Composable
fun ArdttDigitsField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    maxLength: Int = Int.MAX_VALUE,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    scrollIntoViewOnFocus: Boolean = true,
) {
    ArdttTextField(
        value = value,
        onValueChange = { raw -> onValueChange(ardttDigitsOnly(raw, maxLength)) },
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        supportingText = supportingText,
        isError = isError,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        keyboardActions = keyboardActions,
        scrollIntoViewOnFocus = scrollIntoViewOnFocus,
    )
}

/** Keeps digits only, capped at [maxLength]; the filter every numeric field used to inline. */
internal fun ardttDigitsOnly(raw: String, maxLength: Int): String =
    raw.filter { it.isDigit() }.take(maxLength.coerceAtLeast(0))

/**
 * Scroll the focused field above the keyboard once the IME has had a moment
 * to appear — the deploy form used to carry a private copy of this.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.bringIntoViewWhenFocused(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .bringIntoViewRequester(requester)
        .onFocusEvent { state ->
            if (!state.isFocused) return@onFocusEvent
            scope.launch {
                delay(ArdttTextFieldDefaults.FocusScrollDelayMs)
                runCatching { requester.bringIntoView() }
            }
        }
}

object ArdttTextFieldDefaults {
    /** Wait for the IME animation before scrolling the field into view. */
    const val FocusScrollDelayMs: Long = (ArdttMotion.Fast + ArdttMotion.Quick / 2).toLong()
}
