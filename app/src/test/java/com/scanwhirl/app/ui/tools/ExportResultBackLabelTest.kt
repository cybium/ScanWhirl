package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.ExportResultActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B2-L3 (real-phone sweep 2026-11-19, build 2 = 1485874c): the Compress result's back arrow was a
 * 56x56dp ImageButton with no text and no content description, so a screen reader announced an
 * unnamed button.
 *
 * The result screen is exported from Compress (ExportResultActivity), which shows the Up arrow from
 * the toolbar. It now carries the app's own "Back" wording in both languages, like Trash does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class ExportResultBackLabelTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun navigationButtons(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            // The toolbar's navigation button is an ImageButton child.
            if (v is android.widget.ImageButton) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test
    fun `the compress result back arrow is named Back`() {
        val id = "compress-l3"
        val intent = ExportResultActivity.intent(
            app,
            outputPath = app.cacheDir.resolve("result/compress-l3.pdf").absolutePath,
            previewPath = null,
            mime = "application/pdf",
            displayName = "compress-l3.pdf",
            beforeBytes = 167_000L,
            afterBytes = 19_000L,
            width = 0,
            height = 0,
            title = "Compress",
            warning = null,
            docId = id
        )
        ActivityScenario.launch<ExportResultActivity>(intent).use { s ->
            s.onActivity { a ->
                idle()
                val toolbar = a.findViewById<MaterialToolbar>(R.id.toolbar)
                assertNotNull("the result screen has a toolbar", toolbar)
                val description = toolbar.navigationContentDescription?.toString().orEmpty()
                assertTrue(
                    "B2-L3: the back arrow has no content description",
                    description.isNotBlank()
                )
                assertEquals(
                    "the back arrow says what the rest of the app says",
                    a.getString(R.string.trash_navigate_up),
                    description
                )
                // …and the node a screen reader focuses carries it too.
                val nav = navigationButtons(toolbar!!).firstOrNull()
                assertNotNull("the toolbar has a navigation button", nav)
                assertTrue(
                    "B2-L3: the navigation button itself is unnamed",
                    nav!!.contentDescription?.toString()?.isNotBlank() == true
                )
            }
        }
    }
}
