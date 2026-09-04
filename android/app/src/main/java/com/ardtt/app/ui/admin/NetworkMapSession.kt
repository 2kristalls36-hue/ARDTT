package com.ardtt.app.ui.admin

import android.content.Context
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide snapshot of the Network tab map. NavHost disposes the screen on
 * tab switch, so [remember] cannot keep filled cards. Cleared only when the VPN
 * session actually ends ([shouldClearNetworkMapCards]).
 */
internal class NetworkMapSession private constructor(appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _snapshot = MutableStateFlow(NetworkMapSnapshot())
    val snapshot: StateFlow<NetworkMapSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            var prev = ConnState.Idle
            ConnectionManager.get(appContext).ui.collect { ui ->
                if (shouldClearNetworkMapCards(prev, ui.state)) {
                    _snapshot.value = NetworkMapSnapshot()
                }
                prev = ui.state
            }
        }
    }

    fun publish(next: NetworkMapSnapshot) {
        _snapshot.value = next
    }

    fun replaceHop(view: NetworkMapHopView) {
        val cur = _snapshot.value
        _snapshot.value = cur.copy(hops = replaceNetworkMapHopView(cur.hops, view))
    }

    companion object {
        @Volatile
        private var instance: NetworkMapSession? = null

        fun get(context: Context): NetworkMapSession {
            return instance ?: synchronized(this) {
                instance ?: NetworkMapSession(context.applicationContext).also { instance = it }
            }
        }
    }
}
