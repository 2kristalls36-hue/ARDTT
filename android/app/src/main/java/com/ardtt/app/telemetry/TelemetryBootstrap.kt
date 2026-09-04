package com.ardtt.app.telemetry

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

object TelemetryBootstrap {
    fun install(app: Application) {
        AppHttpClient.initialize(app)
        TelemetryExceptionHandler.install(app)
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver(app))
        app.registerActivityLifecycleCallbacks(ActivityScreenObserver(app))
    }
}

private class AppLifecycleObserver(app: Application) : DefaultLifecycleObserver {
    private val recorder by lazy { TelemetryRecorder.get(app) }

    override fun onStart(owner: LifecycleOwner) {
        if (recorder.isRecording.value) {
            recorder.logLifecycle("app", "foreground")
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (recorder.isRecording.value) {
            recorder.logLifecycle("app", "background")
        }
    }
}

private class ActivityScreenObserver(app: Application) : Application.ActivityLifecycleCallbacks {
    private val recorder by lazy { TelemetryRecorder.get(app) }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (recorder.isRecording.value) {
            recorder.logLifecycle(activity.javaClass.simpleName, "onCreate")
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (recorder.isRecording.value) {
            recorder.logLifecycle(activity.javaClass.simpleName, "onStart")
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (recorder.isRecording.value) {
            recorder.logLifecycle(activity.javaClass.simpleName, "onResume")
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (recorder.isRecording.value) {
            recorder.logLifecycle(activity.javaClass.simpleName, "onPause")
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (recorder.isRecording.value) {
            recorder.logLifecycle(activity.javaClass.simpleName, "onStop")
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (recorder.isRecording.value) {
            recorder.logLifecycle(activity.javaClass.simpleName, "onDestroy")
        }
    }
}
