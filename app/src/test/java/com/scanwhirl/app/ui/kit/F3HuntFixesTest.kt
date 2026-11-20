package com.scanwhirl.app.ui.kit

import android.app.Application
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** F3: small fixes from bug hunt H6 (strings, honesty, accessibility). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class F3HuntFixesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun node(view: View): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().also { view.onInitializeAccessibilityNodeInfo(it) }

    @Test
    fun `H6-3 multi-choice rows tell TalkBack whether each one is checked`() {
        val checked = booleanArrayOf(true, false)
        val sheet = SwSheet.builder(app)
            .setTitle("Merge")
            .setOptions(listOf("One", "Two"), multiChecked = checked) { which, isChecked ->
                checked[which] = isChecked
            }
            .setPrimary("Merge")
            .build()
        sheet.show()
        val list = sheet.view.findViewById<ViewGroup>(R.id.swSheetItems)

        val first = node(list.getChildAt(0))
        val second = node(list.getChildAt(1))
        assertTrue("a choice row is checkable", first.isCheckable)
        assertTrue("the picked row is announced as checked", first.isChecked)
        assertFalse("the other row is announced as not checked", second.isChecked)
        assertEquals(android.widget.CheckBox::class.java.name, first.className)

        list.getChildAt(1).performClick()
        val toggled = node(list.getChildAt(1))
        assertTrue("after a tap TalkBack hears the new state", toggled.isChecked)
        sheet.dismiss()
    }

    @Test
    fun `H6-3 single-choice rows announce the current choice as a radio button`() {
        val sheet = SwSheet.builder(app)
            .setTitle("Appearance")
            .setOptions(listOf("Light", "Dark"), checked = 1) { _, _ -> }
            .build()
        sheet.show()
        val list = sheet.view.findViewById<ViewGroup>(R.id.swSheetItems)
        val dark = node(list.getChildAt(1))
        assertEquals(android.widget.RadioButton::class.java.name, dark.className)
        assertTrue(dark.isChecked)
        assertFalse(node(list.getChildAt(0)).isChecked)
        sheet.dismiss()
    }

    @Test
    fun `H6-4 Hindi has its own selected-count plural`() {
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale("hi")) }
        val hi = app.createConfigurationContext(config).resources
        val three = hi.getQuantityString(R.plurals.selected_count, 3, 3)
        assertEquals("3 चुने", three)
        assertFalse("no English left in Hindi", three.contains("selected"))
    }

    @Test
    fun `H6-6 the import failure message never shows a raw placeholder`() {
        for (tag in listOf("en", "hi")) {
            val config = Configuration(app.resources.configuration).apply { setLocale(Locale(tag)) }
            val text = app.createConfigurationContext(config).getString(R.string.import_unlock_failed)
            assertFalse("$tag: $text", text.contains("%"))
        }
    }

    @Test
    fun `H6-2 and H6-7 copy matches what the app does`() {
        val vault = app.getString(R.string.vault_stats_body)
        assertFalse("no unverified hardware claim", vault.contains("chip"))
        assertTrue("the 2-minute relock is stated", vault.contains("2 minutes"))
        assertEquals("folders are free; only tags are Pro", "Tags", app.getString(R.string.pro_feat_folders))
    }
}
