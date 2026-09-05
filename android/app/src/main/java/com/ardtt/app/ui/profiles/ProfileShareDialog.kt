package com.ardtt.app.ui.profiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.ardtt.app.profile.ProfileLinkCodec
import com.ardtt.app.profile.VpnProfile
import com.ardtt.app.ui.components.surface.ArdttLinkShareDialog

@Composable
fun ProfileShareDialog(
    profile: VpnProfile,
    onDismissRequest: () -> Unit,
    extraActions: @Composable () -> Unit = {},
) {
    val linkResult = remember(profile) {
        runCatching { ProfileLinkCodec.buildLink(profile) }
    }
    ArdttLinkShareDialog(
        title = "Ссылка ARDTT",
        description = "Зашифрованная ссылка и QR-код. Клиенту нужен ARDTT — импорт через «Добавить».",
        link = linkResult.getOrDefault(""),
        onDismissRequest = onDismissRequest,
        linkError = linkResult.exceptionOrNull()?.message,
        shareSubject = profile.name,
        clipLabel = "ARDTT link",
        qrContentDescription = "QR-код профиля",
        extraContent = extraActions,
    )
}
