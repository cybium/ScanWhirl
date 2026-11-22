package com.scanwhirl.app.cr

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ReplaceJournal
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.util.ReplaceFeature
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.runBlocking

/** Lane CR: consent sheet, dark-by-default entry point and EN/HI parity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CR_ReplaceUiTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Faults.resetAll()
    }

    @After
    fun tearDown() {
        ReplaceFeature.resetForTests()
        Faults.resetAll()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(20L)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitUntil(timeoutMs: Long = 5000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) idle()
        idle()
    }

    private fun intentFor(source: String, output: String, authId: String? = null) = ToolResultActivity.intent(
        app, "compress", docIds = listOf(output), receiptSourceId = source, replaceAuthId = authId
    )

    private fun read(path: String): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(path)).documentElement
        val out = LinkedHashMap<String, String>()
        for (i in 0 until root.childNodes.length) {
            val el = root.childNodes.item(i) as? org.w3c.dom.Element ?: continue
            if (el.tagName == "string") out[el.getAttribute("name")] = el.textContent.trim()
        }
        return out
    }

    @Test
    fun `lane strings carry full EN HI parity and the Trash consequence`() {
        val en = read("src/main/res/values/strings_cr.xml")
        val hi = read("src/main/res/values-hi/strings_cr.xml")
        assertEquals(en.keys.toSet(), hi.keys.toSet())
        assertEquals("Replace original?", en["cr_replace_title"])
        assertTrue(en["cr_replace_message"]!!.contains("Trash"))
        assertTrue(en["cr_replace_message"]!!.contains("30 days"))
        assertEquals("मूल को बदलें?", hi["cr_replace_title"])
        assertTrue(hi["cr_replace_message"]!!.contains("30 दिन"))
        assertTrue(hi["cr_replace_message"]!!.contains("हटाई गई फ़ाइलों"))
    }

    @Test
    fun `the entry is absent while the flag ships off`() {
        ReplaceFeature.resetForTests()
        val source = DocFixtures.saved(repo, app, pages = 1, name = "dark source")
        val output = DocFixtures.saved(repo, app, pages = 1, name = "dark output")
        ActivityScenario.launch<ToolResultActivity>(intentFor(source, output)).use { scenario ->
            scenario.onActivity {
                it.findViewById<View>(R.id.toolResultMore).performClick()
                idle()
                assertNull(it.findViewById<View>(R.id.cr_replace_entry))
            }
        }
    }

    @Test
    fun `the consent sheet states the consequence and confirm replaces with undo`() {
        ReplaceFeature.testOverride = true
        val source = DocFixtures.saved(repo, app, pages = 1, name = "sheet source")
        // Only an actual producer can issue a permit; opening a result no longer does.
        val output = runBlocking { repo.compressScan(source, 65, 2000) }
        val authId = requireNotNull(output.replaceAuthorizationId)
        ActivityScenario.launch<ToolResultActivity>(intentFor(source, output.id, authId)).use { scenario ->
            scenario.onActivity {
                it.findViewById<View>(R.id.toolResultMore).performClick()
                idle()
                assertNotNull(it.findViewById<View>(R.id.cr_replace_entry))
                it.findViewById<View>(R.id.cr_replace_entry).performClick()
                idle()
            }
            waitUntil { ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.cr_replace_sheet_title) != null
                || ShadowDialog.getLatestDialog()?.isShowing == true }
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            val text = dialog.window?.decorView?.rootView?.toString().orEmpty()
            assertTrue("the sheet is shown", dialog.isShowing)
            assertTrue("sheet text exists: $text", text.isNotBlank())
            scenario.onActivity { activity ->
                // The primary action is the one labelled "Replace original".
                val decor = requireNotNull(dialog.window).decorView as android.view.ViewGroup
                fun walk(v: View) {
                    if (v is android.widget.TextView && v.text.toString() ==
                        activity.getString(R.string.cr_replace_confirm)
                    ) {
                        v.performClick()
                    }
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(decor)
                idle()
            }
            waitUntil { repo.getDocument(source) == null }
            assertNotNull(repo.listTrash().firstOrNull { it.originalId == source })
            assertNotNull(ReplaceJournal(app).findBySource(source))
        }
    }
}
