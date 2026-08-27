package com.nonamevpn.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import com.nonamevpn.app.core.ConnectionManager

/**
 * Lightweight activity for widget / shortcuts when system VPN consent is required.
 */
class VpnPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prep = runCatching { VpnService.prepare(this) }.getOrNull()
        if (prep != null) {
            startActivityForResult(prep, REQ)
        } else {
            ConnectionManager.get(applicationContext).connect()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            if (resultCode == RESULT_OK) {
                ConnectionManager.get(applicationContext).connect()
            } else {
                Toast.makeText(this, "VPN не разрешён", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    companion object {
        private const val REQ = 42
    }
}
