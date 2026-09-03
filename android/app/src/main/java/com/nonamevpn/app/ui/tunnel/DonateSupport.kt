package com.nonamevpn.app.ui.tunnel

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnState

/** Soft donate ask: Tunnel while connected, and a persistent card in Settings. */
object DonateSupport {
    const val URL = "https://spasibomir.ru/pay/34807"
    const val TITLE = "Поддержка автора"
    const val BODY =
        "Донат на развитие проекта. Для вас — цена чашки кофе, для меня — стимул."
    const val ACTION = "Угостить кофе"

    fun bannerVisible(dismissed: Boolean, state: ConnState): Boolean =
        !dismissed && state == ConnState.Connected

    fun openPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { AppLog.w("Donate", "не удалось открыть $URL: ${it.message}") }
    }
}
