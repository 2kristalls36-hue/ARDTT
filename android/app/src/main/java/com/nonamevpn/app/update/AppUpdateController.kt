package com.nonamevpn.app.update

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Process-wide update check/download so leaving Settings does not abort an APK fetch. */
class AppUpdateController private constructor(context: Context) {
    private val manager = AppUpdateManager(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    data class Ui(
        val available: AppUpdateInfo? = null,
        val checking: Boolean = false,
        val downloading: Boolean = false,
        val progress: Float = 0f,
        val downloadedFile: File? = null,
        val message: String? = null,
        val donateUrl: String? = null,
    ) {
        val visible: Boolean
            get() = shouldShowUpdateCard(
                availableNewer = available?.isNewer == true,
                downloading = downloading,
                hasApk = downloadedFile != null,
            )
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    fun checkInBackground() {
        if (downloadJob?.isActive == true) return
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            _ui.update { it.copy(checking = true) }
            val result = manager.check()
            result.onSuccess { info ->
                val donateUrl = info.donateUrl?.takeIf { it.isNotBlank() }
                _ui.update { cur ->
                    val withDonate = if (donateUrl != null) cur.copy(donateUrl = donateUrl) else cur
                    if (info.isNewer) {
                        withDonate.copy(checking = false, available = info)
                    } else {
                        withDonate.copy(
                            checking = false,
                            available = null,
                            downloadedFile = null,
                            progress = 0f,
                            message = null,
                        )
                    }
                }
            }.onFailure {
                _ui.update { it.copy(checking = false) }
            }
        }
    }

    /** Wait until an in-flight or newly started catalog check finishes. */
    suspend fun checkAndWait() {
        if (downloadJob?.isActive == true) return
        val running = checkJob
        if (running?.isActive == true) {
            running.join()
            return
        }
        checkInBackground()
        checkJob?.join()
    }

    fun download() {
        val info = _ui.value.available?.takeIf { it.isNewer } ?: return
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _ui.update {
                it.copy(
                    downloading = true,
                    progress = 0f,
                    message = null,
                )
            }
            try {
                val result = manager.download(info) { progress ->
                    _ui.update { it.copy(progress = progress) }
                }
                result.onSuccess { file ->
                    _ui.update {
                        it.copy(
                            downloading = false,
                            progress = 1f,
                            downloadedFile = file,
                            message = null,
                        )
                    }
                }.onFailure { error ->
                    _ui.update {
                        it.copy(
                            downloading = false,
                            progress = 0f,
                            message = error.message ?: "Не удалось загрузить APK",
                        )
                    }
                }
            } catch (e: CancellationException) {
                _ui.update {
                    it.copy(
                        downloading = false,
                        progress = 0f,
                        downloadedFile = null,
                        message = null,
                    )
                }
                throw e
            }
        }
    }

    fun cancel() {
        if (downloadJob?.isActive == true) {
            downloadJob?.cancel()
        }
    }

    fun install() {
        val file = _ui.value.downloadedFile ?: return
        runCatching { manager.install(file) }
            .onFailure { error ->
                _ui.update {
                    it.copy(message = error.message ?: "Не удалось открыть установщик")
                }
            }
    }

    companion object {
        @Volatile
        private var instance: AppUpdateController? = null

        fun get(context: Context): AppUpdateController {
            return instance ?: synchronized(this) {
                instance ?: AppUpdateController(context.applicationContext).also { instance = it }
            }
        }
    }
}

fun shouldShowUpdateCard(
    availableNewer: Boolean,
    downloading: Boolean,
    hasApk: Boolean,
): Boolean = availableNewer || downloading || hasApk

fun updatePrimaryActionLabel(downloading: Boolean, hasApk: Boolean): String = when {
    downloading -> "Отмена"
    hasApk -> "Установить"
    else -> "Загрузить"
}

/** Copy shown between the «Обновление» title and the fill button. */
data class UpdateCardCopy(
    val headline: String,
    val installedLabel: String?,
    val sizeLabel: String?,
    val notes: String?,
)

fun updateCardCopy(
    installedVersionName: String,
    versionName: String,
    sizeBytes: Long,
    notes: String,
): UpdateCardCopy = UpdateCardCopy(
    headline = "Новая версия $versionName",
    installedLabel = installedVersionName.trim()
        .takeIf { it.isNotEmpty() && !it.equals(versionName, ignoreCase = true) }
        ?.let { "Установлена $it" },
    sizeLabel = if (sizeBytes > 0L) "Размер: ${formatUpdateSize(sizeBytes)}" else null,
    notes = notes.trim().takeIf { it.isNotEmpty() },
)

fun formatUpdateSize(bytes: Long): String {
    val locale = java.util.Locale("ru")
    return when {
        bytes >= 1024L * 1024L -> String.format(locale, "%.1f МБ", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(locale, "%.1f КБ", bytes / 1024.0)
        else -> "$bytes Б"
    }
}
