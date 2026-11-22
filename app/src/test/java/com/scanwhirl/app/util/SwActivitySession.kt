package com.scanwhirl.app.util

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.Lifecycle
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * Test-only, deterministic replacement for [androidx.test.core.app.ActivityScenario].
 *
 * ActivityScenario orchestrates the activity lifecycle on another thread and its
 * close() has hung CI on this suite; Robolectric's [ActivityController] drives the
 * same lifecycle synchronously on the test (main) thread. Every session is
 * AutoCloseable: `use { }` therefore always destroys the activity in a finally,
 * which is the lead's binding rule for Robolectric lifecycle tests.
 *
 * Only the small API surface actually used by the suite is exposed. No production
 * code knows about this class.
 */
class SwActivitySession<T : Activity> private constructor(
    val controller: ActivityController<T>
) : AutoCloseable {

    /** The current activity; [ActivityController.recreate] replaces this instance. */
    val activity: T get() = controller.get()

    /** Runs on the Robolectric main thread, exactly where the test itself runs. */
    fun onActivity(block: (T) -> Unit) = block(activity)

    fun recreate(): SwActivitySession<T> {
        controller.recreate()
        return this
    }

    fun moveToState(state: Lifecycle.State): SwActivitySession<T> {
        when (state) {
            Lifecycle.State.INITIALIZED, Lifecycle.State.RESUMED -> controller.resume()
            Lifecycle.State.STARTED -> controller.pause()
            Lifecycle.State.CREATED -> controller.pause().stop()
            Lifecycle.State.DESTROYED -> controller.pause().stop().destroy()
        }
        return this
    }

    override fun close() {
        controller.close()
    }

    companion object {
        fun <T : Activity> launch(clazz: Class<T>): SwActivitySession<T> =
            SwActivitySession(Robolectric.buildActivity(clazz).setup())

        @Suppress("UNCHECKED_CAST")
        fun <T : Activity> launch(intent: Intent): SwActivitySession<T> {
            val name = intent.component?.className
                ?: throw IllegalArgumentException("SwActivitySession needs an explicit component: $intent")
            val clazz = Class.forName(name) as Class<T>
            return SwActivitySession(Robolectric.buildActivity(clazz, intent).setup())
        }
    }
}
