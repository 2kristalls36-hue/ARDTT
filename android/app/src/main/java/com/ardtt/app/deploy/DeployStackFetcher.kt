package com.ardtt.app.deploy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ardtt.app.BuildConfig
import com.ardtt.app.update.GitHubReleaseUpdate
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

class DeployPayload(
    val packageFile: File,
    val sha256: String,
    val sizeBytes: Long,
    val arch: String,
    val sourceLabel: String,
    val gitRef: String,
)

/**
 * Downloads `ardtt-server-<ver>-linux-<arch>.tar.gz` from GitHub Releases
 * into a cache file. SHA-256 is computed on the stream. The docker image is
 * never held as a ByteArray.
 */
class DeployStackFetcher(
    private val context: Context,
    private val expectedVersion: String = DeployBundle.expectedVersion(context),
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun fetch(
        arch: String,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): DeployPayload {
        val tag = DeployStackSource.gitRef()
        val linuxArch = DeployStackSource.linuxArch(arch)
        if (linuxArch != "amd64" && linuxArch != "arm64") {
            error("Неподдерживаемая архитектура VPS ($arch). Нужен linux amd64 или arm64.")
        }
        val errors = mutableListOf<String>()
        resolveReleaseAsset(tag, linuxArch)?.let { asset ->
            runCatching {
                return downloadVerified(asset, "GitHub Releases $tag / ${asset.name}", tag, linuxArch, onProgress, isCancelled)
            }.onFailure { errors.add(it.message ?: it.javaClass.simpleName) }
        }
        val hint = errors.take(5).joinToString("; ").ifBlank { "нет актива ardtt-server-$expectedVersion-linux-$linuxArch.tar.gz" }
        error(
            "Не удалось скачать пакет $expectedVersion ($linuxArch) из GitHub Releases ($hint). " +
                "Нужен доступ с телефона к github.com. Старые APK, которые ждут ardtt-stack-*.tar.gz " +
                "или исходники main, этот пакет поставить не могут — обновите приложение.",
        )
    }

    private fun resolveReleaseAsset(tag: String, arch: String): DeployStackSource.ReleaseAsset? {
        val json = fetchText(GitHubReleaseUpdate.releaseByTagApiUrl(tag), githubApi = true)
            ?: fetchNewestMatchingRelease(arch)
            ?: return null
        return completeAsset(json, arch)
    }

    private fun fetchNewestMatchingRelease(arch: String): String? {
        val raw = fetchText(GitHubReleaseUpdate.releasesListApiUrl(), githubApi = true) ?: return null
        val releases = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return null
        for (i in 0 until releases.length()) {
            val json = releases.optJSONObject(i) ?: continue
            DeployStackSource.pickServerAsset(json.toString(), expectedVersion, arch)?.let {
                return json.toString()
            }
        }
        return null
    }

    private fun completeAsset(releaseJson: String, arch: String): DeployStackSource.ReleaseAsset? {
        val asset = DeployStackSource.pickServerAsset(releaseJson, expectedVersion, arch) ?: return null
        if (asset.sha256.isNotEmpty()) return asset
        val sumsUrl = DeployStackSource.sha256sumsUrl(releaseJson) ?: return null
        val sums = fetchText(sumsUrl, githubApi = false) ?: return null
        val sha = DeployStackSource.sha256FromSums(sums, asset.name) ?: return null
        return asset.copy(sha256 = sha)
    }

    private fun downloadVerified(
        asset: DeployStackSource.ReleaseAsset,
        label: String,
        gitRef: String,
        arch: String,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): DeployPayload {
        val expect = asset.sha256.lowercase(Locale.US).removePrefix("sha256:")
        if (expect.isEmpty()) {
            error("У актива ${asset.name} нет SHA-256 в метаданных релиза (digest / SHA256SUMS)")
        }
        val cache = cacheFile(arch, expect)
        if (cache.isFile && cache.length() > 0L) {
            val existing = sha256OfFile(cache, isCancelled)
            if (existing == expect) {
                onProgress(1f)
                return DeployPayload(cache, expect, cache.length(), arch, "$label (кеш)", gitRef)
            }
        }
        onProgress(0.05f)
        val tmp = File(cache.parentFile, cache.name + ".partial")
        tmp.parentFile?.mkdirs()
        if (tmp.exists()) tmp.delete()
        val got = downloadToFile(asset.url, tmp, asset.sizeBytes, githubDownload = true, onProgress, isCancelled)
        if (got != expect) {
            tmp.delete()
            error("SHA-256 пакета не совпал с релизом (ожидали $expect, получили $got)")
        }
        if (cache.exists()) cache.delete()
        if (!tmp.renameTo(cache)) {
            tmp.copyTo(cache, overwrite = true)
            tmp.delete()
        }
        onProgress(1f)
        return DeployPayload(cache, expect, cache.length(), arch, label, gitRef)
    }

    private fun cacheFile(arch: String, sha256: String): File {
        val dir = File(context.cacheDir, "ardtt-deploy")
        dir.mkdirs()
        return File(dir, "ardtt-server-${expectedVersion}-linux-$arch-${sha256.take(16)}.tar.gz")
    }

    private fun downloadToFile(
        url: String,
        dest: File,
        expectedSize: Long,
        githubDownload: Boolean,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): String {
        val request = requestBuilder(url, githubApi = false, githubDownload = githubDownload).build()
        var lastError: Throwable? = null
        for (candidate in httpClients()) {
            val digest = MessageDigest.getInstance("SHA-256")
            val sha = runCatching {
                candidate.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("пустой ответ")
                    val total = if (body.contentLength() > 0) body.contentLength() else expectedSize
                    dest.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var copied = 0L
                            while (true) {
                                if (isCancelled()) error("Отменено")
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                copied += read.toLong()
                                if (total > 0) onProgress((copied.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                    hex(digest.digest())
                }
            }.onFailure {
                lastError = it
                dest.delete()
            }.getOrNull()
            if (sha != null) return sha
        }
        throw (lastError ?: IllegalStateException("нет ответа"))
    }

    private fun sha256OfFile(file: File, isCancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                if (isCancelled()) error("Отменено")
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
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

    companion object {
        fun looksLikeInstaller(bytes: ByteArray): Boolean {
            val text = bytes.decodeToString().take(SNIFF_CHARS)
            return text.contains("ARDTT_PROGRESS|") && text.contains("ARDTT_DONE|")
        }

        const val SNIFF_CHARS = 64 * 1024

        fun looksLikeGzip(bytes: ByteArray): Boolean =
            bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

        fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { b -> "%02x".format(b) }
    }
}
