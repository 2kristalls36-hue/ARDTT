package com.ardtt.app.deploy

/** One-shot deep link captured by [com.ardtt.app.MainActivity]. */
object PendingServerImport {
    @Volatile
    var link: String? = null

    fun take(): String? {
        val value = link
        link = null
        return value
    }
}
