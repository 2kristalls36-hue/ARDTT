package com.nonamevpn.app.ui.unlock

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.AppBackdrop
import com.nonamevpn.app.ui.components.AppPageHeader
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.StickyPrimaryButton
import com.nonamevpn.app.unlock.AlphaGate
import com.nonamevpn.app.unlock.AlphaUnlockResult
import com.nonamevpn.app.unlock.DeviceUnlockCopy
import kotlinx.coroutines.launch

@Composable
fun AlphaUnlockScreen(settings: AppSettingsRepository) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var challenge by remember { mutableStateOf<String?>(null) }
    var otp by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val otpFocus = remember { FocusRequester() }

    LaunchedEffect(settings) {
        challenge = settings.ensureAlphaChallengeHex()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppBackdrop(modifier = Modifier.fillMaxSize())
        val hex = challenge
        if (hex == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        fun submit() {
            if (busy || otp.length != AlphaGate.OTP_LEN) return
            scope.launch {
                submitUnlock(
                    settings = settings,
                    otp = otp,
                    setBusy = { busy = it },
                    setError = { error = it },
                )
            }
        }

        fun pasteConfirmationCode() {
            val digits = AlphaGate.otpDigitsFromClipboard(clipboardText(context, clipboard))
            if (digits.isEmpty()) return
            otp = digits
            error = null
        }

        fun copyDeviceCode() {
            clipboard.setText(AnnotatedString(hex))
            Toast.makeText(context, DeviceUnlockCopy.CODE_COPIED, Toast.LENGTH_SHORT).show()
        }

        val imeVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 8.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.ime),
                )
                .padding(horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppPageHeader(
                    title = "ARDTT",
                    subtitle = DeviceUnlockCopy.SUBTITLE,
                )
                AppSectionCard {
                    Text(
                        DeviceUnlockCopy.CONFIRMATION_TITLE,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    SixDigitCodeField(
                        value = otp,
                        onValueChange = {
                            otp = it
                            error = null
                        },
                        isError = error != null,
                        enabled = !busy,
                        focusRequester = otpFocus,
                        clipboard = clipboard,
                        onPaste = { pasteConfirmationCode() },
                        onDone = { submit() },
                    )
                    error?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                AppSectionCard(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            DeviceUnlockCopy.DEVICE_CODE_TITLE,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(
                            onClick = { copyDeviceCode() },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = DeviceUnlockCopy.COPY_CODE,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Text(
                        AlphaGate.formatDisplay(hex),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            fontSize = 20.sp,
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (!imeVisible) {
                    AppSectionCard(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            DeviceUnlockCopy.INTRO,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            DeviceUnlockCopy.STEPS,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            DeviceUnlockCopy.FOOTNOTE,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            StickyPrimaryButton(
                text = DeviceUnlockCopy.CONFIRM,
                onClick = { submit() },
                enabled = !busy && otp.length == AlphaGate.OTP_LEN,
                busy = busy,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SixDigitCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester,
    clipboard: ClipboardManager,
    onPaste: () -> Unit,
    onDone: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val cellShape = RoundedCornerShape(16.dp)
    val pasteToolbar = remember(clipboard, onPaste) {
        ImmediatePasteTextToolbar(onPaste)
    }

    CompositionLocalProvider(LocalTextToolbar provides pasteToolbar) {
        BasicTextField(
            value = value,
            onValueChange = { raw ->
                onValueChange(AlphaGate.otpDigitsFromClipboard(raw))
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            enabled = enabled,
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            singleLine = true,
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            decorationBox = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            enabled = enabled,
                            onClick = { focusRequester.requestFocus() },
                            onLongClick = onPaste,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(AlphaGate.OTP_LEN) { index ->
                        val filled = index < value.length
                        val active = focused && (
                            index == value.length ||
                                (value.length == AlphaGate.OTP_LEN && index == AlphaGate.OTP_LEN - 1)
                            )
                        val borderColor = when {
                            isError -> colors.error
                            active -> colors.primary
                            filled -> colors.outline
                            else -> colors.outline.copy(alpha = 0.55f)
                        }
                        val borderWidth = if (active || isError) 2.dp else 1.dp
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(cellShape)
                                .background(colors.surface)
                                .border(borderWidth, borderColor, cellShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = value.getOrNull(index)?.toString().orEmpty(),
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = colors.onSurface,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            },
        )
    }
}

/**
 * Long-press must paste immediately. The system «Вставить» chip anchors to the
 * hidden 1.sp text and appears over the title.
 */
private class ImmediatePasteTextToolbar(
    private val paste: () -> Unit,
) : TextToolbar {
    override val status: TextToolbarStatus
        get() = TextToolbarStatus.Hidden

    override fun hide() = Unit

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        paste()
    }
}

private fun clipboardText(context: Context, clipboard: ClipboardManager): String? {
    clipboard.getText()?.text?.let { return it }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    return runCatching {
        cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()
}

private suspend fun submitUnlock(
    settings: AppSettingsRepository,
    otp: String,
    setBusy: (Boolean) -> Unit,
    setError: (String?) -> Unit,
) {
    setBusy(true)
    try {
        when (val result = settings.tryAlphaUnlock(otp)) {
            AlphaUnlockResult.Success -> setError(null)
            is AlphaUnlockResult.WrongCode -> setError(DeviceUnlockCopy.wrongCode(result.lockMs))
            is AlphaUnlockResult.Locked -> setError(DeviceUnlockCopy.locked(result.remainingMs))
        }
    } finally {
        setBusy(false)
    }
}
