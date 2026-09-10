package com.ardtt.app.deploy

import android.content.Context
import com.ardtt.app.core.AppLog
import com.ardtt.app.update.GitHubReleaseUpdate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Latest stack (deploy-part) version for UI / install targeting.
 * Polls GitHub Releases on launch, then takes the max with the APK-bundled
 * git [DeployBundle.FALLBACK_VERSION] so an unpublished git bump still shows
 * as “needs update” against an older installed stack.
 */
object DeployVersionCatalog {
    private const val TAG = "DeployVersionCatalog"
    private const val PREFS = "ardtt_deploy_version"
    private const val KEY_LATEST = "latest_deploy_version"
    private const val KEY_CHECKED_AT = "latest_checked_at_ms"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val cached = AtomicReference<String?>(null)
    private val _latest = MutableStateFlow<String?>(null)
    val latest: StateFlow<String?> = _latest.asStateFlow()

    fun expectedVersion(context: Context): String {
        val bundled = DeployBundle.offlineFallback(context)
        cached.get()?.takeIf { it.isNotBlank() }?.let {
            return DeployBundle.maxVersion(it, bundled)
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_LATEST, null)?.trim().orEmpty()
        if (stored.isNotEmpty()) {
            val merged = DeployBundle.maxVersion(stored, bundled)
            cached.set(merged)
            _latest.value = merged
            return merged
        }
        return bundled
    }

    suspend fun refresh(context: Context): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val bundled = DeployBundle.offlineFallback(app)
        val fromGithub = runCatching { fetchLatestFromGitHub() }.getOrNull()?.trim().orEmpty()
        val resolved = DeployBundle.maxVersion(fromGithub, bundled)
        cached.set(resolved)
        _latest.value = resolved
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LATEST, resolved)
            .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
            .apply()
        if (fromGithub.isNotEmpty()) {
            AppLog.i(TAG, "deploy version: github=$fromGithub bundled=$bundled → $resolved")
        } else {
            AppLog.w(TAG, "deploy version poll failed; using bundled/git $resolved")
        }
        resolved
    }

    fun fetchLatestFromGitHub(): String? {
        val url = GitHubReleaseUpdate.releasesListApiUrl()
        val body = fetchText(url) ?: return null
        return DeployStackSource.latestServerVersion(body)
    }

    private fun fetchText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", GitHubReleaseUpdate.userAgent())
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GitHubReleaseUpdate.API_VERSION)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }
}
