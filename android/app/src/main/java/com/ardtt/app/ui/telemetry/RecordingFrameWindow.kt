package com.ardtt.app.ui.telemetry

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Hosts the recording frame in a WindowManager overlay so bottom sheets and
 * dialogs cannot cover it. Touches pass through to the window below.
 *
 * Dialogs (`ModalBottomSheet`) are separate TYPE_APPLICATION windows. A
 * sub-window of the activity stays under them, so the overlay prefers
 * TYPE_APPLICATION and re-adds itself on every focus change to stay last.
 */
@Composable
fun RecordingFrameWindowHost(isRecording: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, isRecording) {
        if (!isRecording || recordingFrameHost() != RecordingFrameHost.WindowOverlay) {
            return@DisposableEffect onDispose { }
        }
        val activity = view.context as? Activity ?: return@DisposableEffect onDispose { }
        val overlay = RecordingFrameOverlay(activity)
        val attach = Runnable { overlay.show() }
        if (view.isAttachedToWindow) {
            attach.run()
        } else {
            view.post(attach)
        }
        onDispose {
            view.removeCallbacks(attach)
            overlay.hide()
        }
    }
}

internal class RecordingFrameOverlay(private val activity: Activity) {
    private var host: ComposeView? = null
    private var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private val raiseOnFocus = Runnable {
        if (host != null) raise()
    }

    fun show() {
        if (host != null) {
            raise()
            return
        }
        val owner = activity as? ComponentActivity ?: return
        val composeView = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            fitsSystemWindows = false
            consumeRecordingFrameWindowInsets(this)
            setContent {
                RecordingBorderOverlay(isRecording = true)
            }
        }
        if (!addOverlayWindow(composeView)) {
            val decor = activity.window.decorView as? ViewGroup ?: return
            decor.addView(
                composeView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        host = composeView
        val listener = ViewTreeObserver.OnWindowFocusChangeListener {
            if (recordingFrameRaisesOnAnyFocusChange()) {
                activity.window.decorView.removeCallbacks(raiseOnFocus)
                activity.window.decorView.post(raiseOnFocus)
            } else if (!it) {
                raise()
            }
        }
        focusListener = listener
        activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
    }

    fun hide() {
        activity.window.decorView.removeCallbacks(raiseOnFocus)
        focusListener?.let { listener ->
            val observer = activity.window.decorView.viewTreeObserver
            if (observer.isAlive) {
                observer.removeOnWindowFocusChangeListener(listener)
            }
        }
        focusListener = null
        val view = host ?: return
        host = null
        runCatching { activity.windowManager.removeViewImmediate(view) }
        (view.parent as? ViewGroup)?.removeView(view)
    }

    fun raise() {
        val view = host ?: return
        if (view.parent === activity.window.decorView) {
            (activity.window.decorView as ViewGroup).bringChildToFront(view)
            return
        }
        runCatching { activity.windowManager.removeViewImmediate(view) }
        (view.parent as? ViewGroup)?.removeView(view)
        addOverlayWindow(view)
    }

    private fun overlayTypes(): IntArray = when (recordingFrameWindowKind()) {
        RecordingFrameWindowKind.ApplicationWindow -> intArrayOf(
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
            WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
        )
        RecordingFrameWindowKind.ActivitySubWindow -> intArrayOf(
            WindowManager.LayoutParams.LAST_SUB_WINDOW,
            WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
        )
    }

    private fun overlayToken(type: Int): IBinder? {
        val activityToken = activity.window.decorView.windowToken
        val focusedToken = activity.currentFocus?.windowToken ?: activityToken
        return if (type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW) {
            focusedToken
        } else {
            activityToken
        }
    }

    private fun addOverlayWindow(view: View): Boolean {
        for (type in overlayTypes()) {
            val token = overlayToken(type) ?: continue
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                this.token = token
                gravity = Gravity.FILL
                title = OVERLAY_TITLE
                applyRecordingFrameWindowLayout(this)
            }
            val added = runCatching {
                activity.windowManager.addView(view, params)
            }.isSuccess
            if (added) return true
        }
        return false
    }

    companion object {
        internal const val OVERLAY_TITLE = "ARDTT recording frame"
    }
}

internal fun applyRecordingFrameWindowLayout(params: WindowManager.LayoutParams) {
    params.flags = params.flags or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    @Suppress("DEPRECATION")
    if (Build.VERSION.SDK_INT < 30) {
        params.systemUiVisibility = params.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    if (recordingFrameDrawsInDisplayCutout()) {
        params.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    if (Build.VERSION.SDK_INT >= 30 && recordingFrameIgnoresSystemInsets()) {
        params.fitInsetsTypes = 0
        params.fitInsetsSides = 0
    }
}

internal fun consumeRecordingFrameWindowInsets(view: View) {
    if (!recordingFrameIgnoresSystemInsets()) return
    ViewCompat.setOnApplyWindowInsetsListener(view) { _, _ ->
        WindowInsetsCompat.CONSUMED
    }
}
