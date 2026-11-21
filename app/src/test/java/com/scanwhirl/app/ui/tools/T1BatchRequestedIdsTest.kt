package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T1: the requested id set must survive process death, otherwise a cancelled batch reports zero
 * "not processed" documents after it comes back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class T1BatchRequestedIdsTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Suppress("UNCHECKED_CAST")
    private fun requested(activity: BatchActivity): List<String> {
        val field = BatchActivity::class.java.getDeclaredField("requestedIds")
        field.isAccessible = true
        return field.get(activity) as List<String>
    }

    @Test
    fun `requested ids survive recreation`() {
        ActivityScenario.launch<BatchActivity>(BatchActivity.intent(app, emptyList())).use { scenario ->
            scenario.onActivity { activity ->
                val field = BatchActivity::class.java.getDeclaredField("requestedIds")
                field.isAccessible = true
                field.set(activity, listOf("doc-a", "doc-b", "doc-c"))
            }
            scenario.recreate()
            idle()
            scenario.onActivity { activity ->
                assertEquals(listOf("doc-a", "doc-b", "doc-c"), requested(activity))
            }
        }
    }
}
