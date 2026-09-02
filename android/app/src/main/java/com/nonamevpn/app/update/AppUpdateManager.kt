package com.nonamevpn.app.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nonamevpn.app.BuildConfig
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
    val manifestKind: AppUpdateManifestKind = AppUpdateManifestKind.LegacyVps,
) {
    val isNewer: Boolean get() = versionCode > BuildConfig.VERSION_CODE

    companion object {
        fun parse(raw: String): AppUpdateInfo {
            val json = JSONObject(raw)
            return AppUpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.optString("sha256").lowercase(Locale.US),
                sizeBytes = json.optLong("sizeBytes", 0L),
                notes = json.optString("notes"),
                manifestKind = AppUpdateManifestKind.LegacyVps,
            )
        }
    }
}

class AppUpdateManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun check(): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            checkGitHubRelease()?.takeIf { it.isNewer }
                ?: checkLegacyManifest().getOrThrow().takeIf { it.isNewer }
                ?: throw NoUpdateAvailableException()
        }
    }

    suspend fun download(
        info: AppUpdateInfo,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val resolved = when (info.manifestKind) {
            AppUpdateManifestKind.GitHubJsonAsset -> resolveManifestAsset(info)
            else -> Result.success(info)
        }.getOrElse { return@withContext Result.failure(it) }

        val requestBuilder = Request.Builder()
            .url(resolved.apkUrl)
            .get()
        if (resolved.apkUrl.contains("github.com", ignoreCase = true)) {
            applyGitHubAuthorization(requestBuilder, enabled = true)
        }
        val request = requestBuilder.build()
        val updatesDir = File(appContext.cacheDir, "updates").also { it.mkdirs() }
        val target = File(updatesDir, "ardtt-${resolved.versionName}.apk")
        val fallbackClient = vpnBoundClientOrNull()
        val activeCall = AtomicReference<okhttp3.Call?>(null)
        currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                activeCall.getAndSet(null)?.cancel()
                runCatching { if (target.exists()) target.delete() }
            }
        }
        try {
            val clients = buildList {
                add(client)
                if (fallbackClient != null) add(fallbackClient)
            }
            var lastError: Throwable? = null
            var downloaded = false

            for (candidate in clients) {
                ensureActive()
                runCatching {
                    val call = candidate.newCall(request)
                    activeCall.set(call)
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            error("APK недоступен: HTTP ${response.code}")
                        }
                        val body = response.body ?: error("Пустой ответ сервера")
                        val total = body.contentLength().takeIf { it > 0 } ?: resolved.sizeBytes
                        body.byteStream().use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var copied = 0L
                                while (true) {
                                    ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    copied += read
                                    if (total > 0) {
                                        onProgress((copied.toFloat() / total.toFloat()).coerceIn(0f, 0.99f))
                                    }
                                }
                            }
                        }
                    }
                    downloaded = true
                }.onFailure { error ->
                    lastError = error
                    runCatching { if (target.exists()) target.delete() }
                }
                if (downloaded) break
            }
            if (!downloaded) {
                throw (lastError ?: IllegalStateException("Не удалось загрузить APK"))
            }
            if (resolved.sha256.isNotBlank()) {
                val actual = target.sha256()
                if (!actual.equals(resolved.sha256, ignoreCase = true)) {
                    target.delete()
                    error("SHA-256 не совпал: $actual")
                }
            }
            onProgress(1f)
            Result.success(target)
        } catch (e: CancellationException) {
            runCatching { if (target.exists()) target.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { if (target.exists()) target.delete() }
            if (!currentCoroutineContext().isActive || (activeCall.get()?.isCanceled() == true)) {
                throw CancellationException("Загрузка отменена", e)
            }
            Result.failure(e)
        }
    }

    private suspend fun resolveManifestAsset(info: AppUpdateInfo): Result<AppUpdateInfo> = runCatching {
        val body = fetchText(info.apkUrl, githubApi = false, githubDownload = true)
            ?: error("Не удалось загрузить ardtt-update.json")
        GitHubReleaseUpdate.parseManifest(body, supportedAbis())
    }

    private suspend fun checkGitHubRelease(): AppUpdateInfo? {
        val latest = fetchText(GitHubReleaseUpdate.latestReleaseApiUrl(), githubApi = true)
            ?.let(GitHubReleaseUpdate::parseRelease)
        if (latest != null) {
            if (latest.manifestKind == AppUpdateManifestKind.GitHubJsonAsset) {
                return resolveManifestAsset(latest).getOrNull()
            }
            return latest
        }
        return fetchText(GitHubReleaseUpdate.releasesListApiUrl(), githubApi = true)
            ?.let { raw ->
                val releases = org.json.JSONArray(raw)
                var best: AppUpdateInfo? = null
                for (i in 0 until releases.length()) {
                    val releaseJson = releases.optJSONObject(i) ?: continue
                    val release = GitHubReleaseUpdate.parseRelease(releaseJson.toString())
                        ?: continue
                    val resolved = if (release.manifestKind == AppUpdateManifestKind.GitHubJsonAsset) {
                        resolveManifestAsset(release).getOrNull()
                    } else {
                        release
                    } ?: continue
                    if (best == null || resolved.versionCode > best.versionCode) {
                        best = resolved
                    }
                }
                best
            }
    }

    private suspend fun checkLegacyManifest(): Result<AppUpdateInfo> = runCatching {
        val request = Request.Builder()
            .url(BuildConfig.UPDATE_MANIFEST_URL)
            .get()
            .build()
        val fallbackClient = vpnBoundClientOrNull()
        val clients = buildList {
            add(client)
            if (fallbackClient != null) add(fallbackClient)
        }
        var lastError: Throwable? = null
        for (candidate in clients) {
            val parsed = runCatching {
                candidate.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Сервер обновлений вернул ${response.code}")
                    }
                    AppUpdateInfo.parse(response.body?.string().orEmpty())
                }
            }.onFailure { lastError = it }.getOrNull()
            if (parsed != null) {
                return@runCatching parsed
            }
        }
        throw (lastError ?: IllegalStateException("Проверка обновлений недоступна"))
    }

    private fun fetchText(url: String, githubApi: Boolean, githubDownload: Boolean = false): String? {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", GitHubReleaseUpdate.userAgent())
            .header("Cache-Control", "no-cache")
        if (githubApi) {
            requestBuilder.header("Accept", "application/vnd.github+json")
            requestBuilder.header("X-GitHub-Api-Version", GitHubReleaseUpdate.API_VERSION)
        }
        applyGitHubAuthorization(requestBuilder, githubApi || githubDownload)
        val request = requestBuilder.build()
        val fallbackClient = vpnBoundClientOrNull()
        val clients = buildList {
            add(client)
            if (fallbackClient != null) add(fallbackClient)
        }
        var lastError: Throwable? = null
        for (candidate in clients) {
            val body = runCatching {
                candidate.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code}")
                    }
                    response.body?.string().orEmpty()
                }
            }.onFailure { lastError = it }.getOrNull()
            if (!body.isNullOrBlank()) return body
        }
        lastError?.let { /* logged by caller */ }
        return null
    }

    private fun applyGitHubAuthorization(builder: Request.Builder, enabled: Boolean) {
        if (!enabled) return
        val token = BuildConfig.GITHUB_API_TOKEN.trim()
        if (token.isNotEmpty()) {
            builder.header("Authorization", "Bearer $token")
        }
    }

    private fun supportedAbis(): Array<String> =
        if (Build.SUPPORTED_ABIS.isNotEmpty()) {
            Build.SUPPORTED_ABIS
        } else {
            arrayOf(Build.CPU_ABI ?: "arm64-v8a")
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
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(settingsIntent)
            return
        }

        val uri = FileProvider.getUriForFile(
            appContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        appContext.startActivity(intent)
    }
}

class NoUpdateAvailableException : Exception("Обновлений нет")

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
