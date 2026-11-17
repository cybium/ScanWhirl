package com.scanwhirl.app.ui.shell

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner F6/F7/F20: a saved Home quick-tool list from an older install still names tiles that no
 * longer exist. Reading the list must skip them (never crash, never render an empty tile), and
 * the shipped defaults must hold only real, READY tools.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuickToolsFixUiTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.resetQuickTools(app)
    }

    @After
    fun tearDown() {
        Prefs.resetQuickTools(app)
    }

    private fun storeRawQuickTools(value: String) {
        app.getSharedPreferences("scanwhirl_prefs", Context.MODE_PRIVATE)
            .edit().putString("quick_tools", value).apply()
    }

    @Test
    fun `a saved list skips tiles that no longer exist`() {
        storeRawQuickTools("book_pages,photos_pdf,business_card,duplicate,enhance,bw,merge,compress")
        assertEquals(listOf("merge", "compress"), Prefs.quickTools(app))
    }

    @Test
    fun `a saved list of only removed tiles falls back to the shipped six`() {
        storeRawQuickTools("book_pages,enhance,bw")
        assertEquals(Prefs.DEFAULT_QUICK_TOOLS, Prefs.quickTools(app))
    }

    @Test
    fun `saving a list drops unknown ids`() {
        Prefs.setQuickTools(app, listOf("book_pages", "merge", "enhance"))
        assertEquals(listOf("merge"), Prefs.quickTools(app))
    }

    @Test
    fun `the defaults are real READY tools`() {
        for (id in Prefs.DEFAULT_QUICK_TOOLS) {
            assertNotNull("default quick tool $id is missing", ToolRegistry.byId(id))
            assertEquals("$id must be READY", ToolStatus.READY, ToolRegistry.byId(id)!!.status)
        }
    }
}
