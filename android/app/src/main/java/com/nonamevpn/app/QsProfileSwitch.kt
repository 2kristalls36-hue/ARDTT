package com.nonamevpn.app

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.profile.ProfileCatalog
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.profile.StoredProfile
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.PROFILE_SWITCH_LOCKED_MESSAGE
import com.nonamevpn.app.ui.QsProfileClickAction
import com.nonamevpn.app.ui.nextProfileId
import com.nonamevpn.app.ui.qsProfileCanPick
import com.nonamevpn.app.ui.qsProfileClickAction
import com.nonamevpn.app.ui.qsProfilePickerCheckedIndex
import com.nonamevpn.app.ui.qsProfilePickerLabels
import com.nonamevpn.app.ui.vpnSessionBlocksProfileSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object QsProfileSwitch {
    suspend fun snapshot(context: Context): ProfileCatalog =
        withContext(Dispatchers.IO) {
            ProfileRepository(context.applicationContext).snapshot()
        }

    fun sessionLocked(): Boolean {
        val state = ConnectionManager.getOrNull()?.ui?.value?.state ?: ConnState.Idle
        return vpnSessionBlocksProfileSwitch(state)
    }

    suspend fun activate(context: Context, item: StoredProfile) {
        val app = context.applicationContext
        val profiles = ProfileRepository(app)
        val settings = AppSettingsRepository(app)
        val conn = ConnectionManager.get(app)
        profiles.setActive(item.id)
        settings.setProfileName(item.profile.name)
        conn.updateProfile(item.profile)
        AppLog.i("QsProfile", "active=${item.profile.name}")
        requestQuickSettingsTilesUpdate(app)
    }

    suspend fun cycleToNext(context: Context, catalog: ProfileCatalog): StoredProfile? {
        val nextId = nextProfileId(catalog.items.map { it.id }, catalog.activeId) ?: return null
        val item = catalog.items.firstOrNull { it.id == nextId } ?: return null
        activate(context, item)
        return item
    }

    fun clickAction(catalog: ProfileCatalog): QsProfileClickAction =
        qsProfileClickAction(catalog.items.size, sessionLocked())

    fun buildPickerDialog(
        context: Context,
        catalog: ProfileCatalog,
        onPicked: (StoredProfile) -> Unit,
    ): Dialog {
        val items = catalog.items
        val labels = qsProfilePickerLabels(items.map { it.profile.name })
        val checked = qsProfilePickerCheckedIndex(items.map { it.id }, catalog.activeId)
        return AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.tile_profile_label))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                items.getOrNull(which)?.let(onPicked)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .create()
    }

    fun toastLocked(context: Context) {
        Toast.makeText(context, PROFILE_SWITCH_LOCKED_MESSAGE, Toast.LENGTH_SHORT).show()
    }

    fun toastPicked(context: Context, name: String) {
        Toast.makeText(context, "Профиль: ${name.trim().ifBlank { "без имени" }}", Toast.LENGTH_SHORT).show()
    }

    fun openProfilesIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_PROFILES
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        }

    fun canShowPicker(catalog: ProfileCatalog): Boolean =
        qsProfileCanPick(catalog.items.size, sessionLocked())
}
