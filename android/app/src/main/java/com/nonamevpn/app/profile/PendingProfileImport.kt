package com.nonamevpn.app.profile

/** One-shot deep link captured by [com.nonamevpn.app.MainActivity]. */
object PendingProfileImport {
    @Volatile
    var link: String? = null

    fun take(): String? {
        val value = link
        link = null
        return value
    }
}
