package com.nonamevpn.app

import android.app.Application
import com.nonamevpn.app.core.ConnectionManager

class NonameApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConnectionManager.get(this)
    }
}
