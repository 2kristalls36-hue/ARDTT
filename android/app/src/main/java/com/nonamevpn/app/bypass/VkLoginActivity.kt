package com.nonamevpn.app.bypass

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nonamevpn.app.ui.theme.NonameTheme
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
            Mode.LOGIN -> "Войдите в аккаунт VK"
            Mode.TOKEN -> "Получаем доступ VK API…"
        }
        val startUrl = when (mode) {
            Mode.LOGIN -> VkSession.loginStartUrl(0)
            Mode.TOKEN -> VkCallHashGenerator.oauthTokenStartUrl()
        }

        setContent {
            NonameTheme {
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
                                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 48.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(
                                onClick = {
                                    notifyCancelled()
                                    finish()
                                },
                                modifier = Modifier.align(Alignment.CenterEnd),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Закрыть")
                            }
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
                                applySettings(this)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        loading = true
                                        maybeComplete(url.orEmpty())
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loading = false
                                        maybeComplete(url.orEmpty())
                                        // Login flow fallbacks if stuck on broken VK ID page
                                        if (mode == Mode.LOGIN && !loginHandled) {
                                            val u = url.orEmpty()
                                            if (loginFlowAttempt < 2 &&
                                                (u.contains("error") || u.contains("blank"))
                                            ) {
                                                loginFlowAttempt++
                                                view?.loadUrl(VkSession.loginStartUrl(loginFlowAttempt))
                                            }
                                        }
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val u = request?.url?.toString().orEmpty()
                                        maybeComplete(u)
                                        return false
                                    }
                                }
                                loadUrl(startUrl)
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

    private fun maybeComplete(url: String) {
        when (mode) {
            Mode.LOGIN -> {
                if (loginHandled) return
                if (VkSession.hasSessionCookie() && !VkSession.looksLikeLoginUrl(url)) {
                    loginHandled = true
                    pendingLogin.getAndSet(null)?.complete(Result.success(Unit))
                    finish()
                }
            }
            Mode.TOKEN -> {
                val token = VkCallHashGenerator.extractAccessToken(url)
                if (!token.isNullOrBlank()) {
                    pendingToken.getAndSet(null)?.complete(Result.success(token))
                    finish()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettings(webView: WebView) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = WebSettings.getDefaultUserAgent(this@VkLoginActivity)
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        private const val TIMEOUT_MS = 5 * 60_000L

        private val mutex = Mutex()
        private val pendingLogin = AtomicReference<CompletableDeferred<Result<Unit>>?>(null)
        private val pendingToken = AtomicReference<CompletableDeferred<Result<String>>?>(null)
        @Volatile private var active: VkLoginActivity? = null

        suspend fun login(context: Context): Result<Unit> = mutex.withLock {
            if (VkSession.hasSessionCookie()) return@withLock Result.success(Unit)
            val deferred = CompletableDeferred<Result<Unit>>()
            pendingLogin.getAndSet(deferred)?.cancel()
            val intent = Intent(context, VkLoginActivity::class.java).apply {
                putExtra(EXTRA_MODE, Mode.LOGIN.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            try {
                withTimeout(TIMEOUT_MS) { deferred.await() }
            } catch (t: Throwable) {
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
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
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
