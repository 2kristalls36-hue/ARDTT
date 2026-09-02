package com.nonamevpn.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.IpApiInfo
import com.nonamevpn.app.core.IpApiLookup
import com.nonamevpn.app.core.NetworkClass
import com.nonamevpn.app.core.VpnPath
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.HideIpCopy
import com.nonamevpn.app.ui.components.AppPageHeader
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.NvpnBottomChrome
import com.nonamevpn.app.ui.components.PullRefreshHost
import com.nonamevpn.app.ui.components.EdgeFeedTopInset
import com.nonamevpn.app.ui.components.rememberPullRefresh
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun NetworkScreen(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val profile by profiles.profile.collectAsStateWithLifecycle(initialValue = null)
    val hideIp by settings.hideIpEnabled.collectAsStateWithLifecycle(initialValue = false)

    var provider by remember { mutableStateOf(IpApiInfo.Empty) }
    var tunnel by remember { mutableStateOf(IpApiInfo.Empty) }
    var providerLoading by remember { mutableStateOf(false) }
    var tunnelLoading by remember { mutableStateOf(false) }

    val sessionUp = ui.state == ConnState.Connected || ui.state == ConnState.PausedTrustedWifi
    val viaVpn = ui.activePath == VpnPath.Bypass ||
        ui.probe?.networkClass == NetworkClass.NeedBypass ||
        ui.probe?.networkClass == NetworkClass.OpenNeedBypass

    val refreshInputs = rememberUpdatedState(
        NetworkRefreshInputs(
            sessionUp = sessionUp,
            hideIp = hideIp,
            provisionBaseUrl = profile?.provisionBaseUrl,
            deviceId = profile?.deviceId,
            viaVpn = viaVpn,
        ),
    )

    suspend fun refreshAll() {
        val inputs = refreshInputs.value
        providerLoading = true
        tunnelLoading = inputs.sessionUp && !inputs.hideIp
        try {
            provider = loadProvider(context)
            tunnel = when {
                !inputs.sessionUp -> IpApiInfo.Empty
                inputs.hideIp -> IpApiInfo.Empty
                else -> loadTunnel(
                    context = context,
                    hideIp = inputs.hideIp,
                    provisionBaseUrl = inputs.provisionBaseUrl,
                    deviceId = inputs.deviceId,
                    viaVpn = inputs.viaVpn,
                )
            }
        } finally {
            providerLoading = false
            tunnelLoading = false
        }
    }

    LaunchedEffect(
        sessionUp,
        hideIp,
        profile?.provisionBaseUrl,
        profile?.deviceId,
        viaVpn,
    ) {
        refreshAll()
    }

    val pull = rememberPullRefresh { refreshAll() }

    PullRefreshHost(
        refreshing = pull.refreshing,
        onRefresh = pull.onRefresh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = NvpnBottomChrome.navigationReserve() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EdgeFeedTopInset()
            AppPageHeader(
                title = "Сеть",
                subtitle = "Публичный IP провайдера и туннеля",
            )
            IpInfoCard(
                title = "IP провайдера",
                info = provider,
                loading = providerLoading,
                emptyHint = "Не удалось определить IP",
            )

            if (sessionUp) {
                when {
                    hideIp -> IpPlaceholderCard(
                        title = "IP туннеля",
                        message = HideIpCopy.STATUS_HIDDEN,
                    )
                    else -> IpInfoCard(
                        title = "IP туннеля",
                        info = tunnel,
                        loading = tunnelLoading,
                        emptyHint = "Не удалось определить IP",
                    )
                }
            }
        }
    }
}

private data class NetworkRefreshInputs(
    val sessionUp: Boolean,
    val hideIp: Boolean,
    val provisionBaseUrl: String?,
    val deviceId: String?,
    val viaVpn: Boolean,
)

private suspend fun loadProvider(context: Context): IpApiInfo =
    try {
        IpApiLookup.fetchUnderlay(context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        IpApiInfo.Empty.copy(error = IpApiLookup.friendlyError(e.message))
    }

private suspend fun loadTunnel(
    context: Context,
    hideIp: Boolean,
    provisionBaseUrl: String?,
    deviceId: String?,
    viaVpn: Boolean,
): IpApiInfo =
    try {
        IpApiLookup.fetchTunnelEgress(
            context = context,
            hideIp = hideIp,
            provisionBaseUrl = provisionBaseUrl,
            deviceId = deviceId,
            viaVpn = viaVpn,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        IpApiInfo.Empty.copy(error = IpApiLookup.friendlyError(e.message))
    }

@Composable
private fun IpInfoCard(
    title: String,
    info: IpApiInfo,
    loading: Boolean,
    emptyHint: String,
) {
    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            loading -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
                        .padding(vertical = 4.dp),
                    strokeWidth = 2.dp,
                )
            }
            info.error != null -> {
                Text(
                    info.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            info.ip.isBlank() -> {
                Text(
                    emptyHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                Text(
                    info.ip,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (info.subtitle.isNotBlank()) {
                    Text(
                        info.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun IpPlaceholderCard(
    title: String,
    message: String,
) {
    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
