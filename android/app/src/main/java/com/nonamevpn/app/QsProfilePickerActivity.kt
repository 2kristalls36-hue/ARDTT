package com.nonamevpn.app

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.nonamevpn.app.profile.StoredProfile
import com.nonamevpn.app.ui.PendingUiAction
import com.nonamevpn.app.ui.QsProfileClickAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Dialog picker launched from Quick Settings long-press (QS_TILE_PREFERENCES)
 * and as a fallback when the profile tile cannot show an in-shade dialog.
 */
class QsProfilePickerActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            runCatching { present() }
                .onFailure {
                    Toast.makeText(this@QsProfilePickerActivity, "Не удалось открыть профили", Toast.LENGTH_SHORT).show()
                    finish()
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private var finishingAfterPick = false

    private suspend fun present() {
        val catalog = QsProfileSwitch.snapshot(applicationContext)
        when {
            QsProfileSwitch.sessionLocked() -> {
                QsProfileSwitch.toastLocked(this)
                finish()
            }
            QsProfileSwitch.clickAction(catalog) == QsProfileClickAction.OpenProfiles -> {
                PendingUiAction.requestOpenProfiles()
                startActivity(QsProfileSwitch.openProfilesIntent(this))
                finish()
            }
            QsProfileSwitch.canShowPicker(catalog) -> {
                val dialog = QsProfileSwitch.buildPickerDialog(this, catalog, ::onPicked)
                dialog.setOnDismissListener {
                    if (!finishingAfterPick && !isFinishing) finish()
                }
                dialog.show()
            }
            else -> finish()
        }
    }

    private fun onPicked(item: StoredProfile) {
        finishingAfterPick = true
        scope.launch {
            QsProfileSwitch.activate(applicationContext, item)
            QsProfileSwitch.toastPicked(this@QsProfilePickerActivity, item.profile.name)
            finish()
        }
    }
}
