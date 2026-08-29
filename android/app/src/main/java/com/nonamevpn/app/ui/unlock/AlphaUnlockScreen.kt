package com.nonamevpn.app.ui.unlock

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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

    LaunchedEffect(settings) {
        challenge = settings.ensureAlphaChallengeHex()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppBackdrop(modifier = Modifier.fillMaxSize())
        val hex = challenge
        if (hex == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            return@Box
        }

        val imeVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 8.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.ime),
                ),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppPageHeader(
                    title = "ARDTT",
                    subtitle = DeviceUnlockCopy.SUBTITLE,
                )
                AppSectionCard {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        DeviceUnlockCopy.INTRO,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        DeviceUnlockCopy.STEPS,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppSectionCard {
                    Text(
                        DeviceUnlockCopy.DEVICE_CODE_TITLE,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        AlphaGate.formatDisplay(hex),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                        ),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(hex))
                            Toast.makeText(
                                context,
                                DeviceUnlockCopy.CODE_COPIED,
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(DeviceUnlockCopy.COPY_CODE)
                    }
                }
                if (!imeVisible) {
                    Text(
                        DeviceUnlockCopy.FOOTNOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            AppSectionCard(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp),
            ) {
                Text(
                    DeviceUnlockCopy.CONFIRMATION_TITLE,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = otp,
                    onValueChange = {
                        otp = it.filter { ch -> ch.isDigit() }.take(AlphaGate.OTP_LEN)
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    placeholder = { Text(DeviceUnlockCopy.CONFIRMATION_PLACEHOLDER) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!busy && otp.length == AlphaGate.OTP_LEN) {
                                scope.launch {
                                    submitUnlock(
                                        settings = settings,
                                        otp = otp,
                                        setBusy = { busy = it },
                                        setError = { error = it },
                                    )
                                }
                            }
                        },
                    ),
                    isError = error != null,
                )
                error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = {
                        scope.launch {
                            submitUnlock(
                                settings = settings,
                                otp = otp,
                                setBusy = { busy = it },
                                setError = { error = it },
                            )
                        }
                    },
                    enabled = !busy && otp.length == AlphaGate.OTP_LEN,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(DeviceUnlockCopy.CONFIRM)
                    }
                }
            }
        }
    }
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
