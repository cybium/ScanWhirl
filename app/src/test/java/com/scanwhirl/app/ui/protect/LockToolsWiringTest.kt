package com.scanwhirl.app.ui.protect

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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
import java.io.File

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
            val chooser = latestDialog()
            assertNotNull("the document chooser opens", chooser)
            assertNotNull(
                "UI-W6: the chooser is the shared searchable picker",
                chooser!!.findViewById<android.view.View>(R.id.docPickerSearch)
            )
            assertTrue(
                "the chooser lists the free document",
                waitUntil { pickerRows(chooser).isNotEmpty() }
            )
            pickerRows(chooser).first().performClick()
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("the Protect sheet opens", sheet)
            assertNotNull(
                "the Protect sheet is the password form",
                sheet.findViewById<android.view.View>(R.id.protectPassword)
            )
            sheet.dismiss()
            idle()
            scenario.onActivity { activity ->
                val targets = ArrayList<View>()
                activity.window.decorView.findViewsWithText(
                    targets, app.getString(R.string.lock_choose_document), View.FIND_VIEWS_WITH_TEXT
                )
                val choose = targets.singleOrNull { it.isShown && it.isClickable && it.isFocusable }
                assertNotNull("dismissing Protect must leave a reachable Choose a document action", choose)
                choose!!.performClick()
                assertNotNull("the action reopens the picker", latestDialog()?.findViewById<View>(R.id.docPickerSearch))
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `dismissing a device PDF refusal finishes the tool activity`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Unprotected test PDF")
        val pdf = requireNotNull(repo.getDocument(id)).pdfFile
        val input = File(app.cacheDir, "r2lock-device-fixture.pdf")
        pdf.copyTo(input, overwrite = true)
        val before = input.readBytes()
        ActivityScenario.launch<LockToolsActivity>(
            LockToolsActivity.intent(app, LockToolsActivity.MODE_UNLOCK)
        ).use { scenario ->
            scenario.onActivity { activity ->
                val chooser = requireNotNull(latestDialog())
                chooser.findViewById<View>(R.id.docPickerExtra).performClick()
                val shadow = shadowOf(activity)
                val request = requireNotNull(shadow.nextStartedActivityForResult)
                shadow.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(input)))
                assertTrue("refusal dialog opened", waitUntil {
                    latestDialog()?.let { it !== chooser && it.isShowing &&
                        it.findViewById<View>(android.R.id.button1) != null } == true
                })
                val refusal = latestDialog()!!
                assertEquals(
                    app.getString(R.string.unlock_pdf_not_ours_msg),
                    refusal.findViewById<TextView>(android.R.id.message).text.toString()
                )
                assertTrue("the tool stays alive until the message is dismissed", !activity.isFinishing)
                refusal.findViewById<View>(android.R.id.button1).performClick()
                idle()
                assertTrue("the title-only tool should close and return to its caller", activity.isFinishing)
                assertTrue("the refused file remains untouched", before.contentEquals(input.readBytes()))
            }
        }
    }

    @Test
    fun `unlock tile lists protected documents plus From a PDF file and opens Unlock sheet`() {
        DocFixtures.locked(repo, app, password = "1234", pages = 1)
        val scenario = ActivityScenario.launch<LockToolsActivity>(
            LockToolsActivity.intent(app, LockToolsActivity.MODE_UNLOCK)
        )
        try {
            val chooser = latestDialog()
            assertNotNull("the document chooser opens", chooser)
            assertTrue(
                "the locked document is listed",
                waitUntil { pickerRows(chooser!!).isNotEmpty() }
            )
            assertEquals("one protected document", 1, pickerRows(chooser!!).size)
            val extra = requireNotNull(chooser.findViewById<TextView>(R.id.docPickerExtra)) {
                "From a PDF file is the picker's own last action"
            }
            assertEquals(app.getString(R.string.lock_from_pdf), extra.text.toString())
            pickerRows(chooser).first().performClick()
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

    private fun latestDialog(): Dialog? = ShadowDialog.getLatestDialog()

    private fun pickerRows(dialog: Dialog): List<LinearLayout> {
        val list = dialog.findViewById<LinearLayout>(R.id.docPickerList) ?: return emptyList()
        return (0 until list.childCount).mapNotNull { list.getChildAt(it) as? LinearLayout }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
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
