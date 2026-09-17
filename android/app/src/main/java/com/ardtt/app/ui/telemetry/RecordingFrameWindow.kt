package com.ardtt.app.ui.telemetry

import android.app.Activity
import android.graphics.PixelFormat
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
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Hosts the recording frame in a WindowManager overlay so bottom sheets and
 * dialogs cannot cover it. Touches pass through to the window below.
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
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus) raise()
        }
        focusListener = listener
        activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
    }

    fun hide() {
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
        addOverlayWindow(view)
    }

    private fun addOverlayWindow(view: View): Boolean {
        val token = activity.window.decorView.windowToken ?: return false
        val types = intArrayOf(
            WindowManager.LayoutParams.LAST_SUB_WINDOW,
            WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
        )
        for (type in types) {
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
