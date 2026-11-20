package com.scanwhirl.app.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.scanwhirl.app.store.vault.VaultSession

/** Counts all process activities, including SDK screens; navigation is not backgrounding. */
internal class VaultAppLifecycle private constructor() : Application.ActivityLifecycleCallbacks {
    private val started = mutableSetOf<Activity>()

    override fun onActivityStarted(activity: Activity) {
        if (started.isEmpty()) VaultSession.onForeground()
        started.add(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        started.remove(activity)
        if (started.isEmpty() && !activity.isChangingConfigurations) VaultSession.onBackground()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) { started.remove(activity) }

    companion object {
        private val installed = java.util.WeakHashMap<Application, VaultAppLifecycle>()

        fun install(app: Application) {
            if (!installed.containsKey(app)) {
                val tracker = VaultAppLifecycle()
                installed[app] = tracker
                app.registerActivityLifecycleCallbacks(tracker)
            }
        }
    }
}
