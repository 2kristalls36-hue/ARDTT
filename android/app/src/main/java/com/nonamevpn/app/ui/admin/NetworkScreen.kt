package com.nonamevpn.app.ui.admin

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.EgressIpProbe
import com.nonamevpn.app.core.IpApiInfo
import com.nonamevpn.app.core.IpApiLookup
import com.nonamevpn.app.core.NetworkClass
import com.nonamevpn.app.core.VpnPath
import com.nonamevpn.app.deploy.ServersRepository
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.NvpnBottomChrome
import com.nonamevpn.app.ui.components.PullRefreshHost
import com.nonamevpn.app.ui.components.TabFeedHeader
import com.nonamevpn.app.ui.components.rememberPullRefresh
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val MAP_REFRESH_MS = 8_000L

private data class HopView(
    val hop: NetworkMapHop,
    val info: IpApiInfo,
    val loading: Boolean,
)

@Composable
fun NetworkScreen(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    serversRepo: ServersRepository,
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val profile by profiles.profile.collectAsStateWithLifecycle(initialValue = null)
    val servers by serversRepo.servers.collectAsStateWithLifecycle(initialValue = serversRepo.snapshot())
    val hideIp by settings.hideIpEnabled.collectAsStateWithLifecycle(initialValue = false)

    val sessionUp = ui.state == ConnState.Connected || ui.state == ConnState.PausedTrustedWifi
    val viaVpn = ui.activePath == VpnPath.Bypass ||
        ui.probe?.networkClass == NetworkClass.NeedBypass ||
        ui.probe?.networkClass == NetworkClass.OpenNeedBypass
    val profileHost = activeProfileHost(profile)
    val server = remember(servers, profileHost) { findMatchingDeployServer(servers, profileHost) }
    val layout = remember(profileHost, server, hideIp) {
        buildNetworkMapLayout(profileHost, server, hideIp)
    }

    var loaded by remember { mutableStateOf<List<HopView>>(emptyList()) }
    val hops = remember(layout, loaded) { syncHopViews(layout, loaded) }
    val hopsLatest = rememberUpdatedState(hops)

    val refreshInputs = rememberUpdatedState(
        NetworkRefreshInputs(
            layout = layout,
            hideIp = hideIp,
            entryProvision = profile?.provisionBaseUrl,
            exitProvision = provisionUrlForHost(layout.vps2Host),
            deviceId = profile?.deviceId,
            viaVpn = viaVpn,
        ),
    )

    suspend fun refreshAll() {
        val inputs = refreshInputs.value
        loaded = loadHopViews(context, inputs, hopsLatest.value)
    }

    LaunchedEffect(
        layout,
        sessionUp,
        hideIp,
        profile?.provisionBaseUrl,
        profile?.deviceId,
        viaVpn,
        ui.probe?.elapsedMs,
        ui.state,
    ) {
        refreshAll()
        while (isActive) {
            delay(MAP_REFRESH_MS)
            refreshAll()
        }
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
            TabFeedHeader(
                title = "Сеть",
                subtitle = NetworkMapCopy.SUBTITLE,
            )
            hops.forEach { view ->
                IpInfoCard(
                    title = view.hop.title,
                    info = view.info,
                    loading = view.loading,
                    emptyHint = "Не удалось определить IP",
                )
            }
        }
    }
}

private data class NetworkRefreshInputs(
    val layout: NetworkMapLayout,
    val hideIp: Boolean,
    val entryProvision: String?,
    val exitProvision: String?,
    val deviceId: String?,
    val viaVpn: Boolean,
)

private fun syncHopViews(layout: NetworkMapLayout, previous: List<HopView>): List<HopView> {
    return layout.hops.map { hop ->
        val old = previous.firstOrNull { it.hop.kind == hop.kind }
        val known = hop.knownHost
        val sameKnown = known != null && sameHopHost(old?.info?.ip, known)
        val info = when {
            known != null && sameKnown -> old?.info ?: IpApiInfo(ip = known, subtitle = "")
            known != null -> IpApiInfo(
                ip = known,
                subtitle = old?.info?.subtitle.orEmpty(),
            )
            else -> old?.info ?: IpApiInfo.Empty
        }
        HopView(hop = hop, info = info, loading = info.ip.isBlank())
    }
}

private suspend fun loadHopViews(
    context: Context,
    inputs: NetworkRefreshInputs,
    previous: List<HopView>,
): List<HopView> = coroutineScope {
    val jobs = inputs.layout.hops.map { hop ->
        async {
            val old = previous.firstOrNull { it.hop.kind == hop.kind }
            val info = loadHop(context, hop, inputs, old?.info ?: IpApiInfo.Empty)
            HopView(hop = hop, info = info, loading = false)
        }
    }
    jobs.map { it.await() }
}

private suspend fun loadHop(
    context: Context,
    hop: NetworkMapHop,
    inputs: NetworkRefreshInputs,
    previous: IpApiInfo,
): IpApiInfo {
    val loaded = try {
        when (hop.kind) {
            NetworkMapHopKind.Provider -> loadProvider(context)
            NetworkMapHopKind.Vps, NetworkMapHopKind.Vps1, NetworkMapHopKind.Vps2 ->
                loadKnownHost(context, hop.knownHost)
            NetworkMapHopKind.Cloudflare -> loadCloudflare(
                context = context,
                entryProvision = inputs.entryProvision,
                exitProvision = inputs.exitProvision,
                deviceId = inputs.deviceId,
                viaVpn = inputs.viaVpn,
                hideIp = inputs.hideIp,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (previous.ip.isNotBlank()) previous
        else IpApiInfo.Empty.copy(error = IpApiLookup.friendlyError(e.message))
    }
    return mergeHopInfo(previous, loaded)
}

private fun mergeHopInfo(previous: IpApiInfo, loaded: IpApiInfo): IpApiInfo {
    if (loaded.ip.isNotBlank()) {
        if (sameHopHost(previous.ip, loaded.ip) && loaded.subtitle.isBlank() && previous.subtitle.isNotBlank()) {
            return loaded.copy(subtitle = previous.subtitle)
        }
        return loaded
    }
    if (previous.ip.isNotBlank()) return previous.copy(error = loaded.error)
    return loaded
}

private suspend fun loadProvider(context: Context): IpApiInfo =
    try {
        IpApiLookup.fetchUnderlay(context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        IpApiInfo.Empty.copy(error = IpApiLookup.friendlyError(e.message))
    }

private suspend fun loadKnownHost(context: Context, host: String?): IpApiInfo {
    val ip = hopHost(host)
    if (ip.isNullOrBlank()) {
        return IpApiInfo.Empty.copy(error = "Не удалось определить IP")
    }
    return try {
        IpApiLookup.lookupAddress(context, ip)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        IpApiInfo(ip = ip, subtitle = "")
    }
}

private suspend fun loadCloudflare(
    context: Context,
    entryProvision: String?,
    exitProvision: String?,
    deviceId: String?,
    viaVpn: Boolean,
    hideIp: Boolean,
): IpApiInfo {
    val urls = linkedSetOf<String>()
    entryProvision?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { urls += it }
    exitProvision?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { urls += it }
    var lastIp: String? = null
    for (base in urls) {
        val ip = EgressIpProbe.probeProvision(
            viaWarp = true,
            provisionBaseUrl = base,
            deviceId = deviceId,
            context = context,
            viaVpn = viaVpn,
        )
        if (ip.isNullOrBlank()) continue
        lastIp = ip
        if (EgressIpProbe.isLikelyCloudflare(ip) || urls.size == 1) {
            if (hideIp) EgressIpProbe.remember(ip, "provision/warp")
            return IpApiLookup.lookupAddress(context, ip)
        }
    }
    if (!lastIp.isNullOrBlank()) {
        if (hideIp) EgressIpProbe.remember(lastIp, "provision/warp")
        return IpApiLookup.lookupAddress(context, lastIp)
    }
    return IpApiInfo.Empty.copy(error = "Не удалось определить IP")
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
        val showIp = info.ip.isNotBlank()
        when {
            loading && !showIp -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
                        .padding(vertical = 4.dp),
                    strokeWidth = 2.dp,
                )
            }
            showIp -> {
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
            info.error != null -> {
                Text(
                    info.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            else -> {
                Text(
                    emptyHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
