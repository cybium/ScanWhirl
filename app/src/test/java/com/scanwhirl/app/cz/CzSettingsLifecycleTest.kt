package com.scanwhirl.app.cz

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.TextSizePrefs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * Lane Z lifecycle tests: every ActivityController is destroyed in a finally block
 * (ActivityScenario.close() hung CI on this activity before), and every test has a
 * 30 s wall-clock budget.
 *
 * RED on base: none of the new rows, seams or the attachBaseContext hook exist.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class CzSettingsLifecycleTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RuntimeEnvironment.setFontScale(1f)
        TextSizePrefs.setMode(app, TextSizePrefs.MODE_DEFAULT)
        SettingsActivity.supportEmailOverride = null
        SettingsActivity.storageThreadProbe = null
        SettingsActivity.reviewLauncher = null
    }

    private fun open(): ActivityController<SettingsActivity> =
        Robolectric.buildActivity(SettingsActivity::class.java).setup()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun findIcon(v: View): ImageView? {
        if (v is ImageView) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                val found = findIcon(v.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    @Test(timeout = 30_000)
    fun `text size is applied to the activity and persists across a restart`() {
        TextSizePrefs.setMode(app, TextSizePrefs.MODE_LARGEST)
        val first = open()
        try {
            assertEquals(1.3f, first.get().resources.configuration.fontScale, 0.001f)
            assertEquals(
                app.getString(R.string.cz_text_size_largest),
                first.get().findViewById<TextView>(R.id.subTextSize).text.toString()
            )
        } finally {
            first.pause().stop().destroy()
        }

        val second = open()
        try {
            assertEquals(1.3f, second.get().resources.configuration.fontScale, 0.001f)
        } finally {
            second.pause().stop().destroy()
        }
        TextSizePrefs.setMode(app, TextSizePrefs.MODE_DEFAULT)
    }

    @Test(timeout = 30_000)
    fun `default leaves the phone scale untouched even at 200 percent`() {
        RuntimeEnvironment.setFontScale(2f)
        val a = open()
        try {
            assertEquals(2f, a.get().resources.configuration.fontScale, 0.001f)
        } finally {
            a.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `small and large apply on the next activity`() {
        for ((mode, expected) in listOf(
            TextSizePrefs.MODE_SMALL to 0.9f,
            TextSizePrefs.MODE_LARGE to 1.15f
        )) {
            TextSizePrefs.setMode(app, mode)
            val a = open()
            try {
                assertEquals(expected, a.get().resources.configuration.fontScale, 0.001f)
            } finally {
                a.pause().stop().destroy()
            }
        }
        TextSizePrefs.setMode(app, TextSizePrefs.MODE_DEFAULT)
    }

    @Test(timeout = 30_000)
    fun `the preview line states the honest multiplier`() {
        TextSizePrefs.setMode(app, TextSizePrefs.MODE_LARGE)
        val a = open()
        try {
            val text = a.get().findViewById<TextView>(R.id.textSizePercent).text.toString()
            assertTrue("preview was '$text'", text.contains("115"))
        } finally {
            a.pause().stop().destroy()
        }
        TextSizePrefs.setMode(app, TextSizePrefs.MODE_DEFAULT)
    }

    @Test(timeout = 30_000)
    fun `new rows have icons full targets and talkback names`() {
        val a = open()
        try {
            val act = a.get()
            val target = SwStyle.dp(act, 48f)
            for (id in intArrayOf(
                R.id.rowTextSize, R.id.rowLibrarySize, R.id.rowTempSize,
                R.id.rowClearTemp, R.id.rowRate
            )) {
                val row = act.findViewById<View>(id)
                assertNotNull("row $id exists", row)
                assertTrue("row $id minHeight", row!!.minimumHeight >= target)
                assertFalse("row $id has a TalkBack name", row.contentDescription.isNullOrBlank())
                val icon = findIcon(row)
                assertNotNull("row $id has a leading icon", icon)
                assertNotNull("row $id icon draws", icon!!.drawable)
            }
        } finally {
            a.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `send feedback is hidden while the address is the placeholder`() {
        val hidden = open()
        try {
            assertEquals(
                View.GONE,
                hidden.get().findViewById<View>(R.id.rowFeedback).visibility
            )
        } finally {
            hidden.pause().stop().destroy()
        }

        SettingsActivity.supportEmailOverride = "qa@example.com"
        val shown = open()
        try {
            assertEquals(
                View.VISIBLE,
                shown.get().findViewById<View>(R.id.rowFeedback).visibility
            )
            val intent = shown.get().buildFeedbackIntent()
            assertNotNull("feedback intent exists", intent)
            assertEquals(Intent.ACTION_SENDTO, intent!!.action)
            assertEquals("mailto:qa@example.com", intent.data.toString())
            assertArrayEquals(
                arrayOf("qa@example.com"),
                intent.getStringArrayExtra(Intent.EXTRA_EMAIL)
            )
        } finally {
            shown.pause().stop().destroy()
        }
        SettingsActivity.supportEmailOverride = null
    }

    @Test(timeout = 30_000)
    fun `rate falls back to a permission free Play listing`() {
        val a = open()
        try {
            a.get().openPlayListing()
            idle()
            val intent = shadowOf(a.get()).nextStartedActivity
            assertNotNull("a listing intent was started", intent)
            val uri = intent.data.toString()
            assertTrue("uri was $uri", uri.contains("com.scanwhirl.app"))
            assertTrue(
                "uri was $uri",
                uri.startsWith("market://") || uri.startsWith("https://")
            )
        } finally {
            a.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `rate row delegates to the injected review launcher`() {
        var launched = 0
        SettingsActivity.reviewLauncher = { launched++ }
        val a = open()
        try {
            a.get().findViewById<View>(R.id.rowRate).performClick()
            idle()
            assertEquals(1, launched)
        } finally {
            a.pause().stop().destroy()
        }
        SettingsActivity.reviewLauncher = null
    }

    @Test(timeout = 30_000)
    fun `clear temporary files only sweeps cache and reports a receipt`() {
        val libDir = File(app.filesDir, "documents/fixture-cz")
        libDir.mkdirs()
        val libFile = File(libDir, "keep.txt")
        libFile.writeText("keep me")
        val temp = File(app.cacheDir, "ocr_cz_fixture.txt")
        temp.writeText("throw away")

        val threads = mutableListOf<String>()
        SettingsActivity.storageThreadProbe = { threads.add(it) }

        val a = open()
        try {
            val act = a.get()
            act.findViewById<View>(R.id.rowClearTemp).performClick()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline &&
                act.findViewById<TextView>(R.id.storageReceipt).text.isNullOrBlank()
            ) {
                idle()
                Thread.sleep(10)
            }
            idle()
            assertFalse("temporary file was removed", temp.exists())
            assertTrue("library file was untouched", libFile.exists())
            assertTrue(
                "measurement ran off the main thread: $threads",
                threads.isNotEmpty() && threads.none { it == "main" }
            )
            val receipt = act.findViewById<TextView>(R.id.storageReceipt).text.toString()
            assertTrue("receipt was '$receipt'", receipt.contains("Freed") || receipt.contains("No removable temporary files"))
        } finally {
            a.pause().stop().destroy()
        }
        libFile.delete()
        libDir.delete()
        temp.delete()
        SettingsActivity.storageThreadProbe = null
    }

    @Test(timeout = 30_000)
    fun `English and Hindi copy follows the glossary`() {
        fun strings(tag: String): Context {
            val config = android.content.res.Configuration(app.resources.configuration).apply {
                setLocale(Locale(tag, if (tag == "hi") "IN" else "US"))
            }
            return app.createConfigurationContext(config)
        }
        val en = strings("en")
        val hi = strings("hi")
        assertEquals("Text size", en.getString(R.string.cz_text_size_title))
        assertEquals("लिखाई का आकार", hi.getString(R.string.cz_text_size_title))
        assertEquals("Small", en.getString(R.string.cz_text_size_small))
        assertEquals("छोटा", hi.getString(R.string.cz_text_size_small))
        assertEquals("Default (follows phone)", en.getString(R.string.cz_text_size_default))
        assertEquals("सामान्य (फ़ोन के अनुसार)", hi.getString(R.string.cz_text_size_default))
        assertEquals("Large", en.getString(R.string.cz_text_size_large))
        assertEquals("बड़ा", hi.getString(R.string.cz_text_size_large))
        assertEquals("Largest", en.getString(R.string.cz_text_size_largest))
        assertEquals("सबसे बड़ा", hi.getString(R.string.cz_text_size_largest))

        val uninstallEn = en.getString(R.string.cz_uninstall_note)
        assertEquals(
            "Uninstalling removes your documents and Vault from this phone. Make a backup first.",
            uninstallEn
        )
        val uninstallHi = hi.getString(R.string.cz_uninstall_note)
        assertTrue(
            uninstallHi,
            uninstallHi.contains("कागज़ात") && uninstallHi.contains("तिजोरी") &&
                uninstallHi.contains("सहेजी प्रति")
        )
        assertFalse(
            uninstallHi,
            uninstallHi.contains("लाइब्रेरी") || uninstallHi.contains("वॉल्ट") ||
                uninstallHi.contains("बैकअप")
        )
    }
}
