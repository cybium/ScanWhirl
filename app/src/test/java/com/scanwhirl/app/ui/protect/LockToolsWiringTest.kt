package com.scanwhirl.app.ui.protect

import android.app.Application
import android.app.Dialog
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The Tools tiles "Protect PDF" and "Unlock PDF" (and the Home quick tool "Protect") must open a
 * real chooser and then their sheet — never the "Coming soon" dead end. The catalogue itself must
 * not keep a BUILD entry that falls through `host.open` to Coming soon.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LockToolsWiringTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanWhirlApp.from(app).repository
    }

    @Test
    fun `protect and unlock are ready free tools and no BUILD fall-through remains`() {
        for (id in listOf("protect", "unlock")) {
            val tool = requireNotNull(ToolRegistry.byId(id)) { "missing tool $id" }
            assertEquals(ToolStatus.READY, tool.status)
            assertFalse("$id must stay free", tool.pro)
        }
        val build = ToolRegistry.all.filter { it.status == ToolStatus.BUILD }
        assertTrue(
            "tools still silently fall through to Coming soon: " + build.joinToString { it.id },
            build.isEmpty()
        )
        val soon = ToolRegistry.all.filter { it.status == ToolStatus.SOON }
        assertTrue("a SOON tool must carry its own note", soon.all { it.soonNote != null })
        // A SOON entry is an explicit comingSoon, not the registry's open() fall-through.
        val host = RecordingHost()
        for (tool in soon) {
            tool.launch(host, tool)
            assertEquals("${tool.id} must ask for its own note", tool.id, host.soon)
        }
        assertEquals("no SOON launch may use open()", 0, host.opened)
    }

    @Test
    fun `the protect tile starts the lock chooser instead of Coming soon`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Free doc")
        ActivityScenario.launch(com.scanwhirl.app.ui.shell.ShellActivity::class.java).use { scenario ->
            scenario.onActivity { shell ->
                val tool = ToolRegistry.byId("protect")!!
                tool.launch(shell, tool)
                val started = shadowOf(shell).nextStartedActivity
                assertEquals(
                    "Protect must start its chooser activity",
                    LockToolsActivity::class.java.name,
                    started?.component?.className
                )
            }
        }
    }

    @Test
    fun `protect tile opens the chooser then the Protect sheet`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Free doc")
        val scenario = ActivityScenario.launch<LockToolsActivity>(
            LockToolsActivity.intent(app, LockToolsActivity.MODE_PROTECT)
        )
        try {
            val chooser = latestAlert()
            assertNotNull("the document chooser opens", chooser)
            assertTrue("the chooser lists the free document",
                (chooser!!.listView.adapter?.count ?: 0) >= 1)
            chooser.listView.performItemClick(null, 0, 0)
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("the Protect sheet opens", sheet)
            assertNotNull(
                "the Protect sheet is the password form",
                sheet.findViewById<android.view.View>(R.id.protectPassword)
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `unlock tile lists protected documents plus From a PDF file and opens Unlock sheet`() {
        DocFixtures.locked(repo, app, password = "1234", pages = 1)
        val scenario = ActivityScenario.launch<LockToolsActivity>(
            LockToolsActivity.intent(app, LockToolsActivity.MODE_UNLOCK)
        )
        try {
            val chooser = latestAlert()
            assertNotNull("the document chooser opens", chooser)
            val adapter = chooser!!.listView.adapter
            assertNotNull("the chooser has items", adapter)
            assertEquals(
                "From a PDF file is the last row",
                app.getString(R.string.lock_from_pdf),
                adapter?.getItem((adapter.count - 1))
            )
            assertTrue("the locked document is listed", adapter!!.count >= 2)
            chooser.listView.performItemClick(null, 0, 0)
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("the Unlock sheet opens", sheet)
            assertNotNull(
                "the Unlock sheet is the password form",
                sheet.findViewById<android.view.View>(R.id.unlockPassword)
            )
        } finally {
            scenario.close()
        }
    }

    private fun latestAlert(): AlertDialog? {
        val dialog: Dialog? = ShadowDialog.getLatestDialog()
        return dialog as? AlertDialog
    }

    private class RecordingHost : ToolHost {
        var soon: String? = null
        var opened = 0

        override fun open(tool: SwTool) {
            opened++
        }

        override fun comingSoon(tool: SwTool) {
            soon = tool.id
        }
    }
}
