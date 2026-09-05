package com.ardtt.app.ui.admin

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.R
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.EgressIpProbe
import com.ardtt.app.core.IpApiInfo
import com.ardtt.app.core.IpApiLookup
import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.deploy.ServersRepository
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.ui.components.feedback.ArdttPingDot
import com.ardtt.app.ui.components.layout.ArdttBottomChrome
import com.ardtt.app.ui.components.layout.ArdttFeedHeader
import com.ardtt.app.ui.components.layout.ArdttPullRefresh
import com.ardtt.app.ui.components.layout.rememberPullRefresh
import com.ardtt.app.ui.components.surface.ArdttSectionCard
import com.ardtt.app.ui.components.surface.ArdttSectionCardDefaults
import com.ardtt.app.ui.theme.ArdttColors
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Composable
fun NetworkScreen(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    serversRepo: ServersRepository,
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val mapSession = remember { NetworkMapSession.get(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val snapshot by mapSession.snapshot.collectAsStateWithLifecycle()
    val profile by profiles.profile.collectAsStateWithLifecycle(initialValue = null)
    val servers by serversRepo.servers.collectAsStateWithLifecycle(initialValue = serversRepo.snapshot())
    val hideIp by settings.hideIpEnabled.collectAsStateWithLifecycle(initialValue = false)

    val sessionUp = networkMapShowsVpnHops(ui.state)
    val viaVpn = sessionUp
    val profileHost = activeProfileHost(profile)
    val server = remember(servers, profileHost) { findMatchingDeployServer(servers, profileHost) }
    val liveCascade = snapshot.liveCascade
    val hopPings = snapshot.hopPings
    val cacheKey = remember(
        sessionUp,
        profileHost,
        hideIp,
        server?.id,
        server?.cascadeEnabled,
        server?.cascadeHost,
        profile?.provisionBaseUrl,
        profile?.deviceId,
    ) {
        networkMapCacheKey(
            sessionUp = sessionUp,
            profileHost = profileHost,
            hideIp = hideIp,
            server = server,
            provisionBase = profile?.provisionBaseUrl,
            deviceId = profile?.deviceId,
        )
    }
    val layout = remember(profileHost, server, hideIp, sessionUp, liveCascade) {
        buildNetworkMapLayout(
            profileHost = profileHost,
            server = server,
            hideIp = hideIp,
            sessionUp = sessionUp,
            liveCascade = liveCascade,
        )
    }

    val hops = remember(layout, snapshot.hops) { syncNetworkMapHopViews(layout, snapshot.hops) }
    val hopsLatest = rememberUpdatedState(hops)
    val terminalKind = remember(layout) { terminalHopKind(layout.hops) }
    val pathAccent = remember(ui.activePath) { hopCardAccentColor(ui.activePath) }
    val visibleHops = remember(hops, terminalKind) {
        val earlier = mutableListOf<String>()
        hops.mapNotNull { view ->
            val kind = view.hop.kind
            if (!shouldShowFilledHop(
                    kind = kind,
                    ip = view.info.ip,
                    earlierIps = earlier,
                    terminal = hopCardHighlighted(kind, terminalKind),
                )
            ) {
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
            liveCascade = liveCascade,
            entryProvision = profile?.provisionBaseUrl,
            deviceId = profile?.deviceId,
            viaVpn = viaVpn,
            cacheKey = cacheKey,
        ),
    )

    suspend fun refreshAll() {
        val inputs = refreshInputs.value
        val current = mapSession.snapshot.value
        val entryHealth = if (inputs.sessionUp) {
            fetchEntryHealth(inputs.entryProvision)
        } else {
            EntryHealthSnapshot()
        }
        val live = when {
            !inputs.sessionUp -> null
            entryHealth.known -> entryHealth.cascade
            else -> inputs.liveCascade
        }
        val resolved = buildNetworkMapLayout(
            profileHost = inputs.profileHost,
            server = inputs.server,
            hideIp = inputs.hideIp,
            sessionUp = inputs.sessionUp,
            liveCascade = live,
        )
        val pings = if (inputs.sessionUp) {
            val exitUrl = provisionUrlForHost(resolved.vps2Host)
            val exitPing = when {
                resolved.vps2Host.isNullOrBlank() -> -1L
                sameProvisionBase(inputs.entryProvision, exitUrl) -> entryHealth.pingMs
                else -> fetchHopPingMs(exitUrl)
            }
            HopHealthPings(entryMs = entryHealth.pingMs, exitMs = exitPing)
        } else {
            HopHealthPings()
        }
        mapSession.publish(
            current.copy(
                key = inputs.cacheKey,
                liveCascade = live,
                hopPings = pings,
            ),
        )
        val loaded = loadHopViews(
            context,
            inputs.copy(layout = resolved, liveCascade = live),
            hopsLatest.value,
            onHop = { view -> mapSession.replaceHop(view) },
        )
        mapSession.publish(
            mapSession.snapshot.value.copy(
                key = inputs.cacheKey,
                liveCascade = live,
                hopPings = pings,
                hops = loaded,
            ),
        )
    }

    LaunchedEffect(cacheKey, ui.state) {
        val snap = mapSession.snapshot.value
        if (shouldSkipNetworkMapAutoload(ui.state, snap.key, cacheKey, snap.hops)) {
            return@LaunchedEffect
        }
        refreshAll()
    }

    val pull = rememberPullRefresh { refreshAll() }

    ArdttPullRefresh(
        refreshing = pull.refreshing,
        onRefresh = pull.onRefresh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ArdttSpacing.Large)
                .padding(bottom = ArdttBottomChrome.navigationReserve() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.MediumPlus),
        ) {
            ArdttFeedHeader(
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
                        loading = view.loading,
                        highlighted = hopCardHighlighted(view.hop.kind, terminalKind),
                        pingMs = hopHealthPingMs(view.hop.kind, hopPings),
                        accentColor = pathAccent,
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
    val liveCascade: ProvisionAdminApi.LiveCascadeInfo?,
    val entryProvision: String?,
    val deviceId: String?,
    val viaVpn: Boolean,
    val cacheKey: NetworkMapCacheKey,
    val layout: NetworkMapLayout = buildNetworkMapLayout(
        profileHost = profileHost,
        server = server,
        hideIp = hideIp,
        sessionUp = sessionUp,
        liveCascade = liveCascade,
    ),
) {
    val exitProvision: String?
        get() = provisionUrlForHost(layout.vps2Host)
}

private data class EntryHealthSnapshot(
    val cascade: ProvisionAdminApi.LiveCascadeInfo = ProvisionAdminApi.LiveCascadeInfo(enabled = false),
    val pingMs: Long = -1L,
    val known: Boolean = false,
)

private suspend fun fetchEntryHealth(entryProvision: String?): EntryHealthSnapshot {
    val base = entryProvision?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        ?: return EntryHealthSnapshot()
    val health = ProvisionAdminApi.health(base).getOrNull() ?: return EntryHealthSnapshot()
    if (!health.ok) return EntryHealthSnapshot()
    return EntryHealthSnapshot(
        cascade = ProvisionAdminApi.liveCascadeInfo(health),
        pingMs = health.pingMs,
        known = true,
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

private suspend fun loadHopViews(
    context: Context,
    inputs: NetworkRefreshInputs,
    previous: List<NetworkMapHopView>,
    onHop: (NetworkMapHopView) -> Unit = {},
): List<NetworkMapHopView> = coroutineScope {
    val jobs = inputs.layout.hops.map { hop ->
        async {
            val old = previous.firstOrNull { it.hop.kind == hop.kind }
            val info = loadHop(context, hop, inputs, old?.info ?: IpApiInfo.Empty)
            val view = NetworkMapHopView(hop = hop, info = info, loading = false)
            onHop(view)
            view
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
    val color = hopMapGrayStroke(MaterialTheme.colorScheme.outline)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .semantics { contentDescription = "связь" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(color, ArdttShapes.Pill),
        )
    }
}

@Composable
private fun IpInfoCard(
    title: String,
    kind: NetworkMapHopKind,
    info: IpApiInfo,
    loading: Boolean = false,
    highlighted: Boolean = false,
    pingMs: Long = -1L,
    accentColor: Color = ArdttColors.Connected,
) {
    val pingLabel = formatHealthPingMs(pingMs)
    val pingColor = when (pingLatencyTier(pingMs)) {
        PingLatencyTier.Good -> ArdttColors.Connected
        PingLatencyTier.Fair -> ArdttColors.Warning
        PingLatencyTier.Poor -> MaterialTheme.colorScheme.error
        null -> accentColor
    }
    ArdttSectionCard(
        contentPadding = PaddingValues(horizontal = ArdttSpacing.LargePlus, vertical = ArdttSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
        shape = ArdttShapes.Panel,
        shadowElevation = ArdttElevation.None,
        tonalElevation = ArdttElevation.None,
        border = BorderStroke(
            ArdttSectionCardDefaults.ContourWidth,
            hopCardStrokeColor(
                highlighted = highlighted,
                outline = MaterialTheme.colorScheme.outline,
                connected = accentColor,
            ),
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
                ArdttPingDot(
                    pingKey = pingLabel,
                    modifier = Modifier.padding(start = ArdttSpacing.Small, end = ArdttSpacing.Tiny),
                    color = pingColor,
                )
                Text(
                    pingLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = pingColor,
                )
            }
        }
        Text(
            hopCardPrimaryText(info, loading = loading),
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
                .padding(horizontal = ArdttSpacing.TinyPlus)
                .size(ArdttSize.IconSmall),
        )
        Text(
            layout.trailingText,
            style = textStyle,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
