package com.nonamevpn.app.ui.admin

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.EgressIpProbe
import com.nonamevpn.app.core.IpApiInfo
import com.nonamevpn.app.core.IpApiLookup
import com.nonamevpn.app.deploy.DeployHop
import com.nonamevpn.app.deploy.DeployTarget
import com.nonamevpn.app.deploy.ProvisionAdminApi
import com.nonamevpn.app.deploy.ServersRepository
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.NvpnBottomChrome
import com.nonamevpn.app.ui.components.PullRefreshHost
import com.nonamevpn.app.ui.components.TabFeedHeader
import com.nonamevpn.app.ui.components.illustratedBackdropActive
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

    val sessionUp = networkMapShowsVpnHops(ui.state)
    val profileHost = activeProfileHost(profile)
    val server = remember(servers, profileHost) { findMatchingDeployServer(servers, profileHost) }
    var observedLastHop by remember { mutableStateOf<String?>(null) }
    var liveCascade by remember { mutableStateOf(ProvisionAdminApi.LiveCascadeInfo(enabled = false)) }
    val layout = remember(
        profileHost,
        server,
        hideIp,
        sessionUp,
        observedLastHop,
        liveCascade,
        servers,
    ) {
        buildNetworkMapLayout(
            profileHost = profileHost,
            server = server,
            hideIp = hideIp,
            sessionUp = sessionUp,
            observedLastHop = observedLastHop,
            liveCascadeHost = liveCascade.host,
            cascadeLive = liveCascade.enabled,
            servers = servers,
        )
    }

    var loaded by remember { mutableStateOf<List<HopView>>(emptyList()) }
    val hops = remember(layout, loaded) { syncHopViews(layout, loaded) }
    val hopsLatest = rememberUpdatedState(hops)
    val visibleHops = remember(hops) {
        val earlier = ArrayList<String>(hops.size)
        hops.mapNotNull { view ->
            if (!shouldShowFilledHop(view.hop.kind, view.info.ip, earlier)) {
                null
            } else {
                earlier += view.info.ip
                view
            }
        }
    }

    val refreshInputs = rememberUpdatedState(
        NetworkRefreshInputs(
            profileHost = profileHost,
            server = server,
            hideIp = hideIp,
            sessionUp = sessionUp,
            liveCascade = liveCascade,
            servers = servers,
            entryProvision = profile?.provisionBaseUrl,
            deviceId = profile?.deviceId,
        ),
    )

    suspend fun refreshAll() {
        val inputs = refreshInputs.value
        if (!inputs.sessionUp) {
            if (observedLastHop != null) observedLastHop = null
            if (liveCascade.enabled || liveCascade.host != null) {
                liveCascade = ProvisionAdminApi.LiveCascadeInfo(enabled = false)
            }
            loaded = loadHopViews(context, inputs.disconnected(), hopsLatest.value)
            return
        }
        val live = ProvisionAdminApi.liveCascade(inputs.entryProvision)
        if (live != liveCascade) liveCascade = live
        val vps1 = entryHost(inputs.profileHost, inputs.server)
        val knownExit = resolveCascadeExitHost(
            servers = inputs.servers,
            matched = inputs.server,
            profileHost = inputs.profileHost,
            vps1 = vps1,
            observedLastHop = null,
            liveCascadeHost = live.host,
            cascadeLive = live.enabled,
        )
        val lastHop = knownExit
            ?: EgressIpProbe.probeLastHopWan(
                context = context,
                exitProvisionBaseUrl = DeployHop.exitProvisionUrl(inputs.server),
                deviceId = inputs.deviceId,
                viaVpn = true,
                bindVpnIfNoExit = true,
            )?.takeIf { lastHopCanBeVps2(vps1, it) }
        if (lastHop != observedLastHop) observedLastHop = lastHop
        loaded = loadHopViews(
            context,
            inputs.copy(liveCascade = live).connected(lastHop),
            hopsLatest.value,
        )
    }

    LaunchedEffect(
        sessionUp,
        hideIp,
        profileHost,
        server?.id,
        server?.cascadeEnabled,
        server?.cascadeHost,
        profile?.provisionBaseUrl,
        profile?.deviceId,
        ui.probe?.elapsedMs,
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
            Column {
                visibleHops.forEachIndexed { index, view ->
                    if (index > 0) {
                        HopConnector()
                    }
                    IpInfoCard(
                        title = view.hop.title,
                        info = view.info,
                    )
                }
            }
        }
    }
}

private data class NetworkRefreshInputs(
    val profileHost: String?,
    val server: DeployTarget?,
    val hideIp: Boolean,
    val sessionUp: Boolean,
    val liveCascade: ProvisionAdminApi.LiveCascadeInfo,
    val servers: List<DeployTarget>,
    val entryProvision: String?,
    val deviceId: String?,
    val layout: NetworkMapLayout = NetworkMapLayout(emptyList()),
) {
    val cascade: Boolean
        get() = liveCascade.enabled ||
            liveCascade.host != null ||
            layout.vps2Host != null ||
            (server != null && DeployHop.isCascadeEntry(server, profileHost))

    val exitProvision: String?
        get() = DeployHop.exitProvisionUrl(server) ?: provisionUrlForHost(layout.vps2Host)

    fun disconnected() = copy(
        sessionUp = false,
        liveCascade = ProvisionAdminApi.LiveCascadeInfo(enabled = false),
        layout = buildNetworkMapLayout(
            profileHost = profileHost,
            server = server,
            hideIp = hideIp,
            sessionUp = false,
            servers = servers,
        ),
    )

    fun connected(lastHop: String?) = copy(
        sessionUp = true,
        layout = buildNetworkMapLayout(
            profileHost = profileHost,
            server = server,
            hideIp = hideIp,
            sessionUp = true,
            observedLastHop = lastHop,
            liveCascadeHost = liveCascade.host,
            cascadeLive = liveCascade.enabled,
            servers = servers,
        ),
    )
}

private fun syncHopViews(layout: NetworkMapLayout, previous: List<HopView>): List<HopView> {
    return layout.hops.map { hop ->
        val old = previous.firstOrNull { it.hop.kind == hop.kind }
        val known = hop.knownHost
        val info = when {
            known != null && sameHopHost(old?.info?.ip, known) ->
                old?.info ?: IpApiInfo(ip = known, subtitle = "")
            known != null -> IpApiInfo(ip = known, subtitle = old?.info?.subtitle.orEmpty())
            else -> old?.info ?: IpApiInfo.Empty
        }
        HopView(hop = hop, info = info)
    }
}

private suspend fun loadHopViews(
    context: Context,
    inputs: NetworkRefreshInputs,
    previous: List<HopView>,
): List<HopView> = coroutineScope {
    inputs.layout.hops.map { hop ->
        async {
            val old = previous.firstOrNull { it.hop.kind == hop.kind }
            HopView(hop = hop, info = loadHop(context, hop, inputs, old?.info ?: IpApiInfo.Empty))
        }
    }.map { it.await() }
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
            NetworkMapHopKind.Vps, NetworkMapHopKind.Vps1, NetworkMapHopKind.Vps2 -> {
                if (sameHopHost(previous.ip, hop.knownHost) && previous.subtitle.isNotBlank()) {
                    previous
                } else {
                    loadKnownHost(context, hop.knownHost)
                }
            }
            NetworkMapHopKind.Cloudflare -> loadCloudflare(
                context = context,
                provisionBaseUrl = lastHopProvisionUrl(
                    inputs.entryProvision,
                    inputs.exitProvision,
                    cascade = inputs.cascade,
                ),
                deviceId = inputs.deviceId,
                viaVpn = inputs.sessionUp,
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
    provisionBaseUrl: String?,
    deviceId: String?,
    viaVpn: Boolean,
): IpApiInfo {
    val ip = EgressIpProbe.probeProvision(
        viaWarp = true,
        provisionBaseUrl = provisionBaseUrl,
        deviceId = deviceId,
        context = context,
        viaVpn = viaVpn,
    )
    if (ip.isNullOrBlank()) {
        return IpApiInfo.Empty.copy(error = "Не удалось определить IP")
    }
    EgressIpProbe.remember(ip, "provision/warp")
    return IpApiLookup.lookupAddress(context, ip)
}

@Composable
private fun HopConnector() {
    val onWallpaper = illustratedBackdropActive()
    val color = if (onWallpaper) {
        Color(0xFFD6E2F0)
    } else {
        MaterialTheme.colorScheme.primary
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .semantics { contentDescription = "связь" },
    ) {
        val x = size.width / 2f
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun IpInfoCard(
    title: String,
    info: IpApiInfo,
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
