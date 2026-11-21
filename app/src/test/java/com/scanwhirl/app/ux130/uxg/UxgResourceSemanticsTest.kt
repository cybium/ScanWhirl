package com.scanwhirl.app.ux130.uxg

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * UX lane UXG — runtime resource and accessibility-role checks. Symbols are resolved by
 * name so these tests compile (and fail) on the base commit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UxgResourceSemanticsTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun dimenSp(resources: android.content.res.Resources, name: String): Float {
        val id = resources.getIdentifier(name, "dimen", app.packageName)
        assertTrue("lane dimen $name must exist", id != 0)
        return resources.getDimension(id) / resources.displayMetrics.scaledDensity
    }

    @Test
    fun `G6 metadata resource is 12sp in English and 14sp in Hindi`() {
        assertEquals("EN metadata floor regressed", 12f,
            dimenSp(app.resources, "ux_uxg_viewer_meta"), 0.05f)
        val hiConfig = Configuration(app.resources.configuration).apply {
            setLocale(Locale("hi", "IN"))
        }
        val hi = app.createConfigurationContext(hiConfig).resources
        assertEquals("HI Devanagari metadata must be 14sp", 14f,
            dimenSp(hi, "ux_uxg_viewer_meta"), 0.05f)
    }

    @Test
    fun `G5 lane action view reports Button role and focus`() {
        val clazz = Class.forName("com.scanwhirl.app.ui.kit.UxgActionTextView")
        val ctor = clazz.getConstructor(android.content.Context::class.java)
        val view = ctor.newInstance(app) as View
        assertTrue("lane action view must be clickable", view.isClickable)
        assertTrue("lane action view must be focusable", view.isFocusable)
        assertEquals("lane action view must announce the Button role",
            Button::class.java.name, view.accessibilityClassName.toString())
        val info = AccessibilityNodeInfo.obtain(view)
        view.onInitializeAccessibilityNodeInfo(info)
        assertEquals("node info must carry the Button className",
            Button::class.java.name, info.className.toString())
    }

    @Test
    fun `G4 lane warning string has English and Hindi resources`() {
        val enId = app.resources.getIdentifier("ux_uxg_ocr_discard_warning", "string", app.packageName)
        assertTrue("lane warning string must exist", enId != 0)
        val en = app.resources.getString(enId)
        assertTrue("EN warning must say the edits are lost", en.contains("lost") || en.contains("Unsaved"))

        val hiConfig = Configuration(app.resources.configuration).apply { setLocale(Locale("hi", "IN")) }
        val hiRes = app.createConfigurationContext(hiConfig).resources
        val hiId = hiRes.getIdentifier("ux_uxg_ocr_discard_warning", "string", app.packageName)
        assertTrue("Hindi warning string must exist", hiId != 0)
        val hi = hiRes.getString(hiId)
        assertTrue("HI warning must be Devanagari, not untranslated English",
            hi.any { it.code in 0x0900..0x097F })
    }
}
