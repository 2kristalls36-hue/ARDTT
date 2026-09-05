package com.ardtt.app.deploy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One-shot deep link captured by [com.ardtt.app.MainActivity]. */
object PendingServerImport {
    private val _link = MutableStateFlow<String?>(null)
    val link: StateFlow<String?> = _link.asStateFlow()

    fun offer(uri: String) {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return
        _link.value = trimmed
    }

    fun take(): String? {
        var taken: String? = null
        _link.update { current ->
            taken = current
            null
        }
        return taken
    }
}
