package com.nonamevpn.app.ui.admin

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.R
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
import com.nonamevpn.app.ui.theme.NvpnColors
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

    val sessionUp = networkMapShowsVpnHops(ui.state)
    val viaVpn = sessionUp
    val profileHost = activeProfileHost(profile)
    val server = remember(servers, profileHost) { findMatchingDeployServer(servers, profileHost) }
    var observedLastHop by remember { mutableStateOf<String?>(null) }
    var liveCascadeHost by remember { mutableStateOf<String?>(null) }
    var cascadeLive by remember { mutableStateOf(false) }
    var hopPings by remember { mutableStateOf(HopHealthPings()) }
    val layout = remember(
        profileHost,
        server,
        hideIp,
        sessionUp,
        observedLastHop,
        liveCascadeHost,
        cascadeLive,
        servers,
    ) {
        buildNetworkMapLayout(
            profileHost = profileHost,
            server = server,
            hideIp = hideIp,
            sessionUp = sessionUp,
            observedLastHop = observedLastHop,
            liveCascadeHost = liveCascadeHost,
            cascadeLive = cascadeLive,
            servers = servers,
        )
    }

    var loaded by remember { mutableStateOf<List<HopView>>(emptyList()) }
    val hops = remember(layout, loaded) { syncHopViews(layout, loaded) }
    val hopsLatest = rememberUpdatedState(hops)
    val visibleHops = remember(hops) {
        val earlier = mutableListOf<String>()
        hops.mapNotNull { view ->
            if (!shouldShowFilledHop(view.hop.kind, view.info.ip, earlier)) {
                null
            } else {
                if (view.info.ip.isNotBlank()) earlier += view.info.ip
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
            observedLastHop = observedLastHop,
            liveCascadeHost = liveCascadeHost,
            cascadeLive = cascadeLive,
            servers = servers,
            entryProvision = profile?.provisionBaseUrl,
            deviceId = profile?.deviceId,
            viaVpn = viaVpn,
        ),
    )

    suspend fun refreshAll() {
        val inputs = refreshInputs.value
        if (!inputs.sessionUp) {
            if (observedLastHop != null) observedLastHop = null
            if (liveCascadeHost != null) liveCascadeHost = null
            if (cascadeLive) cascadeLive = false
            if (hopPings != HopHealthPings()) hopPings = HopHealthPings()
        }
        val entryHealth = if (inputs.sessionUp) {
            fetchEntryHealth(inputs.entryProvision)
        } else {
            EntryHealthSnapshot()
        }
        val live = entryHealth.cascade
        if (live.host != liveCascadeHost) {
            liveCascadeHost = live.host
        }
        if (live.enabled != cascadeLive) {
            cascadeLive = live.enabled
        }
        val lastHop = if (inputs.sessionUp) {
            EgressIpProbe.probeLastHopWan(
                context = context,
                exitProvisionBaseUrl = DeployHop.exitProvisionUrl(inputs.server)
                    ?: provisionUrlForHost(
                        resolveCascadeExitHost(
                            servers = inputs.servers,
                            matched = inputs.server,
                            profileHost = inputs.profileHost,
                            vps1 = hopHost(inputs.profileHost),
                            observedLastHop = null,
                            liveCascadeHost = live.host,
                            cascadeLive = live.enabled,
                        ),
                    ),
                deviceId = inputs.deviceId,
                viaVpn = true,
                bindVpnIfNoExit = true,
            )
        } else {
            null
        }
        if (lastHop != observedLastHop) {
            observedLastHop = lastHop
        }
        val resolved = buildNetworkMapLayout(
            profileHost = inputs.profileHost,
            server = inputs.server,
            hideIp = inputs.hideIp,
            sessionUp = inputs.sessionUp,
            observedLastHop = lastHop,
            liveCascadeHost = live.host,
            cascadeLive = live.enabled,
            servers = inputs.servers,
        )
        val nextPings = if (inputs.sessionUp) {
            val exitUrl = DeployHop.exitProvisionUrl(inputs.server)
                ?: provisionUrlForHost(resolved.vps2Host)
            val exitPing = when {
                resolved.vps2Host.isNullOrBlank() -> -1L
                sameProvisionBase(inputs.entryProvision, exitUrl) -> entryHealth.pingMs
                else -> fetchHopPingMs(exitUrl)
            }
            HopHealthPings(entryMs = entryHealth.pingMs, exitMs = exitPing)
        } else {
            HopHealthPings()
        }
        if (nextPings != hopPings) hopPings = nextPings
        loaded = loadHopViews(
            context,
            inputs.copy(
                layout = resolved,
                liveCascadeHost = live.host,
                cascadeLive = live.enabled,
            ),
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
            Column {
                visibleHops.forEachIndexed { index, view ->
                    if (index > 0) {
                        HopConnector()
                    }
                    IpInfoCard(
                        title = view.hop.title,
                        kind = view.hop.kind,
                        info = view.info,
                        highlighted = hopCardOutline(index, visibleHops.size) == HopCardOutline.Last,
                        pingLabel = hopPingLabel(view.hop.kind, hopPings),
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
    val observedLastHop: String?,
    val liveCascadeHost: String?,
    val cascadeLive: Boolean,
    val servers: List<DeployTarget>,
    val entryProvision: String?,
    val deviceId: String?,
    val viaVpn: Boolean,
    val layout: NetworkMapLayout = buildNetworkMapLayout(
        profileHost = profileHost,
        server = server,
        hideIp = hideIp,
        sessionUp = sessionUp,
        observedLastHop = observedLastHop,
        liveCascadeHost = liveCascadeHost,
        cascadeLive = cascadeLive,
        servers = servers,
    ),
) {
    val exitProvision: String?
        get() = DeployHop.exitProvisionUrl(server) ?: provisionUrlForHost(layout.vps2Host)
}

private data class EntryHealthSnapshot(
    val cascade: ProvisionAdminApi.LiveCascadeInfo = ProvisionAdminApi.LiveCascadeInfo(enabled = false),
    val pingMs: Long = -1L,
)

private suspend fun fetchEntryHealth(entryProvision: String?): EntryHealthSnapshot {
    val base = entryProvision?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        ?: return EntryHealthSnapshot()
    val health = ProvisionAdminApi.health(base).getOrNull() ?: return EntryHealthSnapshot()
    return EntryHealthSnapshot(
        cascade = ProvisionAdminApi.liveCascadeInfo(health),
        pingMs = if (health.ok) health.pingMs else -1L,
    )
}

private suspend fun fetchHopPingMs(provisionUrl: String?): Long {
    val base = provisionUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return -1L
    val health = ProvisionAdminApi.health(base).getOrNull() ?: return -1L
    return if (health.ok) health.pingMs else -1L
}

private fun sameProvisionBase(a: String?, b: String?): Boolean {
    val left = a?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return false
    val right = b?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return false
    return left.equals(right, ignoreCase = true)
}

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
            NetworkMapHopKind.Provider -> loadProvider(
                context,
                rejectIps = inputs.layout.hops.mapNotNull { hopHost(it.knownHost) },
            )
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

private suspend fun loadProvider(
    context: Context,
    rejectIps: Collection<String> = emptyList(),
): IpApiInfo =
    try {
        IpApiLookup.fetchUnderlay(context, rejectIps)
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
    exitProvision?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { urls += it }
    entryProvision?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { urls += it }
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
    kind: NetworkMapHopKind,
    info: IpApiInfo,
    highlighted: Boolean = false,
    pingLabel: String = "",
) {
    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        border = BorderStroke(
            2.dp,
            if (highlighted) NvpnColors.connected else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HopCardTitle(
                title = title,
                kind = kind,
                modifier = Modifier.weight(1f),
            )
            if (pingLabel.isNotEmpty()) {
                Text(
                    pingLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NvpnColors.connected,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Text(
            hopCardPrimaryText(info),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (info.ip.isNotBlank() && info.subtitle.isNotBlank()) {
            Text(
                info.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HopCardTitle(
    title: String,
    kind: NetworkMapHopKind,
    modifier: Modifier = Modifier,
) {
    val layout = hopTitleLayout(kind, title)
    val textStyle = MaterialTheme.typography.titleMedium
    if (!layout.showCloudflareMark) {
        Text(
            layout.leadingText,
            style = textStyle,
            fontWeight = FontWeight.SemiBold,
            modifier = modifier,
        )
        return
    }
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = NetworkMapCopy.CLOUDFLARE
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            layout.leadingText,
            style = textStyle,
            fontWeight = FontWeight.SemiBold,
        )
        Image(
            painter = painterResource(R.drawable.ic_cloudflare),
            contentDescription = null,
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .size(16.dp),
        )
        Text(
            layout.trailingText,
            style = textStyle,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
