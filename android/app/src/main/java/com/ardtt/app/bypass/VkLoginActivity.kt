package com.ardtt.app.bypass

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.core.AppLog
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.ArdttTheme
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Slim VK WebView: LOGIN (remixsid) or TOKEN (access_token fallback).
 * No JOIN_CALL / TURN intercept — Connect uses anonymous vkcalls.
 */
class VkLoginActivity : ComponentActivity() {
    enum class Mode { LOGIN, TOKEN }

    private var mode: Mode = Mode.LOGIN
    private var loginHandled = false
    private var loginFlowAttempt = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = this
        mode = when (intent.getStringExtra(EXTRA_MODE)) {
            Mode.TOKEN.name -> Mode.TOKEN
            else -> Mode.LOGIN
        }
        CookieManager.getInstance().setAcceptCookie(true)

        val title = when (mode) {
            Mode.LOGIN -> "Авторизация во ВКонтакте"
            Mode.TOKEN -> "Получение доступа к API ВКонтакте…"
        }
        val startUrl = when (mode) {
            Mode.LOGIN -> VkSession.loginStartUrl(0)
            Mode.TOKEN -> VkCallHashGenerator.oauthTokenStartUrl()
        }

        val settings = AppSettingsRepository(applicationContext)
        setContent {
            // Same theme choice as the main window: a forced light / dark app
            // must not open a system-themed VK sheet.
            val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
            ArdttTheme(themeMode = themeMode) {
                var loading by remember { mutableStateOf(true) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = title,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(
                                        start = ArdttSpacing.Large,
                                        top = ArdttSpacing.Medium,
                                        bottom = ArdttSpacing.Medium,
                                        end = ArdttSize.TouchTarget,
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            ArdttButton(
                                onClick = {
                                    notifyCancelled()
                                    finish()
                                },
                                modifier = Modifier.align(Alignment.CenterEnd),
                                variant = ArdttButtonVariant.Icon,
                                icon = Icons.Default.Close,
                                contentDescription = "Закрыть",
                            )
                        }
                    }
                    if (loading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    AndroidView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                applySettings(this, loginFlowAttempt)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        loading = true
                                        maybeCaptureDisplayName(view)
                                        maybeComplete(url.orEmpty())
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loading = false
                                        maybeCaptureDisplayName(view)
                                        maybeComplete(url.orEmpty())
                                        injectLoginErrorWatcher(view)
                                        // Login flow fallbacks if stuck on broken VK ID page
                                        if (mode == Mode.LOGIN && !loginHandled) {
                                            val u = url.orEmpty()
                                            if (loginFlowAttempt < 2 &&
                                                (u.contains("error") || u.contains("blank") ||
                                                    u.contains("blocked"))
                                            ) {
                                                retryLogin(view, "url=$u")
                                            }
                                        }
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val u = request?.url?.toString().orEmpty()
                                        maybeCaptureDisplayName(view)
                                        maybeComplete(u)
                                        return false
                                    }
                                }
                                addJavascriptInterface(
                                    object {
                                        @android.webkit.JavascriptInterface
                                        fun onLoginPageError(msg: String) {
                                            runOnUiThread {
                                                if (!loginHandled) retryLogin(this@apply, msg)
                                            }
                                        }
                                    },
                                    "ArdttVkAuth",
                                )
                                loadUrl(startUrl, AUTH_HEADERS)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    private fun retryLogin(view: WebView?, reason: String) {
        if (loginHandled || mode != Mode.LOGIN) return
        if (loginFlowAttempt >= 2) {
            AppLog.e(TAG_ACT, "All login variants exhausted ($reason)")
            pendingLogin.getAndSet(null)?.complete(
                Result.failure(IllegalStateException("Не удалось открыть авторизацию ВКонтакте ($reason)")),
            )
            finish()
            return
        }
        loginFlowAttempt++
        val next = VkSession.loginStartUrl(loginFlowAttempt)
        AppLog.w(TAG_ACT, "Retry login attempt=$loginFlowAttempt reason=$reason → $next")
        view?.let {
            applySettings(it, loginFlowAttempt)
            it.evaluateJavascript("window.__ardtt_login_err_watch=false;", null)
            it.loadUrl(next, AUTH_HEADERS)
        }
    }

    private fun injectLoginErrorWatcher(view: WebView?) {
        view?.evaluateJavascript(LOGIN_ERROR_WATCHER_JS, null)
    }

    private fun maybeComplete(url: String) {
        when (mode) {
            Mode.LOGIN -> {
                if (loginHandled) return
                if (VkSession.hasSessionCookie() && !VkSession.looksLikeLoginUrl(url)) {
                    loginHandled = true
                    AppLog.i(TAG_ACT, "Login OK remixsid present")
                    pendingLogin.getAndSet(null)?.complete(Result.success(Unit))
                    finish()
                }
            }
            Mode.TOKEN -> {
                val token = VkCallHashGenerator.extractAccessToken(url)
                if (!token.isNullOrBlank()) {
                    AppLog.i(TAG_ACT, "Token OK")
                    pendingToken.getAndSet(null)?.complete(Result.success(token))
                    finish()
                }
            }
        }
    }

    private fun maybeCaptureDisplayName(view: WebView?) {
        if (mode != Mode.LOGIN || !VkSession.hasSessionCookie()) return
        view ?: return
        view.evaluateJavascript(CAPTURE_PROFILE_NAME_JS) { raw ->
            val normalized = raw
                ?.removePrefix("\"")
                ?.removeSuffix("\"")
                ?.replace("\\n", " ")
                ?.replace("\\t", " ")
                ?.replace("\\\"", "\"")
                ?.replace("\\u003C", "<")
                ?.replace("\\u003E", ">")
                ?.replace("\\\\", "\\")
                ?.trim()
                .orEmpty()
            if (normalized.isNotBlank()) {
                VkSession.rememberDisplayName(normalized)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettings(webView: WebView, attempt: Int) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Desktop UA on last attempt — workaround for broken VK ID WebView.
            userAgentString = if (attempt >= 2) {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            } else {
                WebSettings.getDefaultUserAgent(this@VkLoginActivity)
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        private const val TIMEOUT_MS = 5 * 60_000L
        private const val TAG = "VkLogin"
        private const val TAG_ACT = "VkLogin"

        private val AUTH_HEADERS = mapOf(
            "Accept-Language" to "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
        )

        private const val LOGIN_ERROR_WATCHER_JS = """
            (function() {
                if (window.__ardtt_login_err_watch) return;
                window.__ardtt_login_err_watch = true;
                function check() {
                    try {
                        var t = (document.body && document.body.innerText) || '';
                        var low = t.toLowerCase();
                        if (low.indexOf('unknown method') !== -1) {
                            window.ArdttVkAuth.onLoginPageError('Unknown method passed');
                        }
                    } catch(e) {}
                }
                setInterval(check, 1200);
                check();
            })();
        """

        private const val CAPTURE_PROFILE_NAME_JS = """
            (function() {
                function text(v){ return (v || '').replace(/\s+/g, ' ').trim(); }
                var candidates = [
                    document.querySelector('[data-testid="top_profile_name"]'),
                    document.querySelector('.top_profile_name'),
                    document.querySelector('.TopNavBtn__profileName'),
                    document.querySelector('.owner_name'),
                    document.querySelector('.ProfileHeader__name'),
                    document.querySelector('meta[property="og:title"]')
                ];
                for (var i = 0; i < candidates.length; i++) {
                    var el = candidates[i];
                    if (!el) continue;
                    var val = el.content ? text(el.content) : text(el.textContent);
                    if (val && val.toLowerCase() !== 'вконтакте' && val.toLowerCase() !== 'vk') return val;
                }
                var title = text(document.title || '');
                title = title.replace(/\s+\|\s+(ВКонтакте|VK)$/i, '').trim();
                return title;
            })();
        """

        private val mutex = Mutex()
        private val pendingLogin = AtomicReference<CompletableDeferred<Result<Unit>>?>(null)
        private val pendingToken = AtomicReference<CompletableDeferred<Result<String>>?>(null)
        @Volatile private var active: VkLoginActivity? = null

        suspend fun login(context: Context): Result<Unit> = mutex.withLock {
            if (VkSession.hasSessionCookie()) {
                AppLog.i(TAG, "Already have remixsid — skip WebView")
                return@withLock Result.success(Unit)
            }
            val deferred = CompletableDeferred<Result<Unit>>()
            pendingLogin.getAndSet(deferred)?.cancel()
            val intent = Intent(context, VkLoginActivity::class.java).apply {
                putExtra(EXTRA_MODE, Mode.LOGIN.name)
                // WebView flags; Activity context still works with NEW_TASK on modern Android.
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            }
            AppLog.i(TAG, "Starting VK login WebView")
            try {
                context.startActivity(intent)
            } catch (t: Throwable) {
                AppLog.e(TAG, "startActivity failed: ${t.message}")
                return@withLock Result.failure(t)
            }
            try {
                withTimeout(TIMEOUT_MS) { deferred.await() }
            } catch (t: Throwable) {
                AppLog.w(TAG, "login await: ${t.message}")
                Result.failure(t)
            } finally {
                pendingLogin.set(null)
                runCatching { active?.finish() }
            }
        }

        suspend fun awaitAccessToken(context: Context): String = mutex.withLock {
            val deferred = CompletableDeferred<Result<String>>()
            pendingToken.getAndSet(deferred)?.cancel()
            val intent = Intent(context, VkLoginActivity::class.java).apply {
                putExtra(EXTRA_MODE, Mode.TOKEN.name)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            }
            AppLog.i(TAG, "Starting VK token WebView")
            context.startActivity(intent)
            try {
                withTimeout(TIMEOUT_MS) {
                    deferred.await().getOrThrow()
                }
            } finally {
                pendingToken.set(null)
                runCatching { active?.finish() }
            }
        }

        private fun notifyCancelled() {
            pendingLogin.getAndSet(null)?.complete(Result.failure(IllegalStateException("Вход отменён")))
            pendingToken.getAndSet(null)?.complete(Result.failure(IllegalStateException("Токен отменён")))
        }
    }
}
