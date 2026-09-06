package com.ardtt.app.deploy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ardtt.app.BuildConfig
import com.ardtt.app.update.GitHubReleaseUpdate
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

class DeployPayload(
    val stackBytes: ByteArray,
    val installBytes: ByteArray,
    val sourceLabel: String,
    val gitRef: String,
)

/**
 * Downloads `server/` from GitHub for admin deploy. Release asset first,
 * then the tag/source tarball so a public clone works before the asset exists.
 */
class DeployStackFetcher(
    private val context: Context,
    private val expectedVersion: String = DeployBundle.expectedVersion(context),
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun fetch(onProgress: (Float) -> Unit = {}): DeployPayload {
        val tag = DeployStackSource.gitRef()
        val errors = mutableListOf<String>()

        resolveReleaseAssetUrl(tag)?.let { url ->
            runCatching {
                return assemble(url, "GitHub Releases $tag / ${DeployStackSource.stackAssetName(expectedVersion)}", tag, onProgress)
            }.onFailure { errors.add(it.message ?: it.javaClass.simpleName) }
        }

        for (candidate in sourceArchiveCandidates(tag)) {
            runCatching {
                return assemble(
                    url = candidate.url,
                    label = candidate.label,
                    gitRef = candidate.ref,
                    onProgress = onProgress,
                    githubApi = candidate.githubApi,
                )
            }.onFailure { errors.add("${candidate.label}: ${it.message ?: it.javaClass.simpleName}") }
        }

        loadBundledFallback()?.let { return it }

        val hint = errors.take(5).joinToString("; ").ifBlank { "нет ответа" }
        error(
            "Не удалось скачать стек $expectedVersion из GitHub ($hint). " +
                "Нужен доступ с телефона к github.com. Репозиторий публичный, PAT не требуется.",
        )
    }

    private fun assemble(
        url: String,
        label: String,
        gitRef: String,
        onProgress: (Float) -> Unit,
        githubApi: Boolean = false,
    ): DeployPayload {
        onProgress(0.05f)
        val stack = downloadBytes(url, githubApi = githubApi, githubDownload = !githubApi) { frac ->
            onProgress(0.05f + frac * 0.8f)
        }
        if (stack.size < 256) error("архив стека слишком короткий (${stack.size} B)")
        if (!looksLikeGzip(stack)) error("ответ GitHub не gzip (не архив стека)")
        onProgress(0.88f)
        val install = DeployStackArchive.extractInstallScript(stack)
            ?: downloadInstallScript(gitRef)
        onProgress(1f)
        return DeployPayload(
            stackBytes = stack,
            installBytes = install,
            sourceLabel = label,
            gitRef = gitRef,
        )
    }

    private fun downloadInstallScript(gitRef: String): ByteArray {
        val refs = listOf(gitRef, "main").distinct()
        var lastError: Throwable? = null
        for (ref in refs) {
            val bytes = runCatching {
                downloadBytes(DeployStackSource.rawInstallUrl(ref), githubDownload = true)
            }.onFailure { lastError = it }.getOrNull()
            if (bytes != null) {
                if (looksLikeInstaller(bytes)) return bytes
                lastError = IllegalStateException(
                    "server/install.sh с $ref не похож на установщик (${bytes.size} B)",
                )
            }
        }
        loadAsset("deploy/install.sh")?.takeIf { looksLikeInstaller(it) }?.let { return it }
        throw lastError ?: IllegalStateException("Не удалось скачать server/install.sh из GitHub")
    }

    private fun resolveReleaseAssetUrl(tag: String): String? {
        fetchText(GitHubReleaseUpdate.releaseByTagApiUrl(tag), githubApi = true)
            ?.let { DeployStackSource.pickStackAssetUrl(it, expectedVersion) }
            ?.let { return it }
        fetchText(GitHubReleaseUpdate.releasesListApiUrl(), githubApi = true)?.let { raw ->
            val releases = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return@let
            for (i in 0 until releases.length()) {
                val json = releases.optJSONObject(i) ?: continue
                DeployStackSource.pickStackAssetUrl(json.toString(), expectedVersion)?.let { return it }
            }
        }
        return null
    }

    private data class ArchiveCandidate(
        val url: String,
        val label: String,
        val ref: String,
        val githubApi: Boolean,
    )

    private fun sourceArchiveCandidates(tag: String): List<ArchiveCandidate> = listOf(
        ArchiveCandidate(
            url = DeployStackSource.apiTarballUrl(tag),
            label = "GitHub tarball $tag",
            ref = tag,
            githubApi = true,
        ),
        ArchiveCandidate(
            url = DeployStackSource.publicTagArchiveUrl(tag),
            label = "GitHub archive $tag",
            ref = tag,
            githubApi = false,
        ),
        ArchiveCandidate(
            url = DeployStackSource.apiTarballUrl("main"),
            label = "GitHub tarball main",
            ref = "main",
            githubApi = true,
        ),
        ArchiveCandidate(
            url = DeployStackSource.publicHeadArchiveUrl("main"),
            label = "GitHub archive main",
            ref = "main",
            githubApi = false,
        ),
    )

    private fun downloadBytes(
        url: String,
        githubApi: Boolean = false,
        githubDownload: Boolean = false,
        onProgress: (Float) -> Unit = {},
    ): ByteArray {
        val request = requestBuilder(url, githubApi, githubDownload).build()
        var lastError: Throwable? = null
        for (candidate in httpClients()) {
            val bytes = runCatching {
                candidate.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("пустой ответ")
                    val total = body.contentLength()
                    val out = java.io.ByteArrayOutputStream(
                        if (total > 0) total.toInt().coerceAtLeast(4096) else 64 * 1024,
                    )
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            copied += read
                            if (total > 0) onProgress((copied.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                    out.toByteArray()
                }
            }.onFailure { lastError = it }.getOrNull()
            if (bytes != null) return bytes
        }
        throw (lastError ?: IllegalStateException("нет ответа"))
    }

    private fun fetchText(url: String, githubApi: Boolean): String? {
        val request = requestBuilder(url, githubApi = githubApi, githubDownload = false).build()
        for (candidate in httpClients()) {
            val body = runCatching {
                candidate.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
            }.getOrNull()
            if (!body.isNullOrBlank()) return body
        }
        return null
    }

    private fun requestBuilder(
        url: String,
        githubApi: Boolean,
        githubDownload: Boolean,
    ): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", GitHubReleaseUpdate.userAgent())
            .header("Cache-Control", "no-cache")
        if (githubApi) {
            builder.header("Accept", "application/vnd.github+json")
            builder.header("X-GitHub-Api-Version", GitHubReleaseUpdate.API_VERSION)
        }
        val token = BuildConfig.GITHUB_API_TOKEN.trim()
        if (token.isNotEmpty() && (githubApi || githubDownload || url.contains("github.com", ignoreCase = true))) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    private fun httpClients(): List<OkHttpClient> = buildList {
        add(client)
        vpnBoundClientOrNull()?.let { add(it) }
    }

    private fun vpnBoundClientOrNull(): OkHttpClient? {
        val vpn = pickVpnNetwork() ?: return null
        return client.newBuilder()
            .socketFactory(vpn.socketFactory)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        vpn.getAllByName(hostname).toList()
                },
            )
            .build()
    }

    private fun pickVpnNetwork(): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun loadBundledFallback(): DeployPayload? {
        val names = listOf(
            "deploy/stack.tar.gz.bin",
            "deploy/stack.tar.gz",
            "deploy/stack.tar",
        )
        for (name in names) {
            val bytes = loadAsset(name) ?: continue
            if (bytes.isEmpty()) continue
            val stack = if (name.endsWith(".tar") && !name.endsWith(".tar.gz")) {
                gzipBytes(bytes)
            } else {
                bytes
            }
            val install = loadAsset("deploy/install.sh") ?: continue
            if (!looksLikeInstaller(install)) continue
            return DeployPayload(
                stackBytes = stack,
                installBytes = install,
                sourceLabel = "локальный бандл APK ($name)",
                gitRef = DeployStackSource.gitRef(),
            )
        }
        return null
    }

    private fun loadAsset(name: String): ByteArray? =
        runCatching { context.assets.open(name).use { it.readBytes() } }.getOrNull()

    private fun gzipBytes(raw: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(raw.size / 2)
        java.util.zip.GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    companion object {
        fun looksLikeInstaller(bytes: ByteArray): Boolean {
            val text = bytes.decodeToString().take(SNIFF_CHARS)
            return text.contains("ARDTT_PROGRESS|") && text.contains("ARDTT_DONE|")
        }

        /** APK ≤0.5.245 used 400; keep protocol markers near the top of install.sh. */
        const val SNIFF_CHARS = 64 * 1024

        fun looksLikeGzip(bytes: ByteArray): Boolean =
            bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    }
}
