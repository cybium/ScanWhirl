package com.scanwhirl.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.HomeHost
import com.scanwhirl.app.tools.MergeFlow
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.kit.SwProgress
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.tools.BatchActivity
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.ResetDialogShadowsRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * G1 privacy round: every export route must apply the ProtectionDialogs policy. A document in
 * NEEDS_REPAIR / UNKNOWN_PASSWORD has a plaintext PDF behind a stale lock label (or the reverse)
 * and must never be shared, batch-processed or merged into an unprotected copy. LOCKED is a
 * positive control: its PDF is encrypted and is deliberately shareable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class G1PrivacyFixTest {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setTestOverride(app, true)
        // FileProvider caches path roots per authority in a static map (see MultiShareSitesFixShareTest).
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    /** R09 F3: the chooser is posted after the IO copies; a single idle() races it. */
    private fun chooserAfterCopy(activity: Activity): Intent? {
        var chooser: Intent? = null
        waitUntil(5_000) {
            chooser = shadowOf(activity).nextStartedActivity
            chooser != null
        }
        return chooser
    }

    private fun dirOf(id: String) = File(repo.docsDir, id)

    /** Plain scan.pdf + stale locked.meta — the documented crash window. */
    private fun repairable(name: String): String {
        val id = DocFixtures.saved(repo, app, pages = 1, name = name)
        Durable.atomicWriteText(
            File(dirOf(id), PdfProtection.LOCK_FILE), Prefs.sha256("pdf_lock:1234")
        )
        assertEquals(PdfProtection.State.NEEDS_REPAIR, repo.protectionState(id))
        return id
    }

    /** Encrypted scan.pdf with its locked.meta gone. */
    private fun unknownPassword(name: String): String {
        val id = DocFixtures.saved(repo, app, pages = 1, name = name)
        runBlocking { repo.protectPdf(id, "1234") }
        assertTrue(File(dirOf(id), PdfProtection.LOCK_FILE).delete())
        assertEquals(PdfProtection.State.UNKNOWN_PASSWORD, repo.protectionState(id))
        return id
    }

    private fun latestTitle(): String? = ShadowDialog.getLatestDialog()?.let { dialog ->
        (dialog.findViewById<TextView>(R.id.swSheetTitle)
            ?: dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle))?.text?.toString()
    }

    /** Long-press the first named row and tick the rest, then tap the Library's selection Share. */
    private fun shareSelection(names: List<String>, check: (Intent?) -> Unit) {
        ActivityScenario.launch<ShellActivity>(ShellActivity::class.java).use { scenario ->
            var ready = false
            assertTrue("library rows load", waitUntil {
                scenario.onActivity { activity ->
                    idle()
                    val list = activity.findViewById<ViewGroup>(R.id.swDocList)
                    ready = list != null && list.childCount == names.size &&
                        names.all { name ->
                            (0 until list.childCount).any {
                                (list.getChildAt(it) as DocCard).doc?.name == name
                            }
                        }
                }
                ready
            })
            scenario.onActivity { activity ->
                idle()
                val list = activity.findViewById<ViewGroup>(R.id.swDocList)
                val cards = (0 until list.childCount).map { list.getChildAt(it) as DocCard }
                val picked = names.map { name -> cards.first { it.doc?.name == name } }
                picked.first().performLongClick()
                idle()
                picked.drop(1).forEach { it.performClick(); idle() }
                // Drop anything the shell started while loading.
                while (shadowOf(activity).nextStartedActivity != null) Unit
                activity.findViewById<View>(R.id.swSelShare).performClick()
                check(chooserAfterCopy(activity))
            }
        }
    }

    // ---- R06 F1: Library multi-select Share -------------------------------

    @Test
    fun `two selected documents with one needing repair share nothing and show repair`() {
        repairable("Broken source")
        DocFixtures.saved(repo, app, pages = 1, name = "Healthy source")
        shareSelection(listOf("Broken source", "Healthy source")) { started ->
            assertEquals(app.getString(R.string.protection_repair_title), latestTitle())
            assertNull("nothing may be shared", started)
        }
    }

    /** The docs.size == 1 branch of shareDocuments was unguarded too. */
    @Test
    fun `a single selected document with unknown password shares nothing and shows repair`() {
        unknownPassword("Forgotten source")
        shareSelection(listOf("Forgotten source")) { started ->
            assertEquals(app.getString(R.string.protection_repair_title), latestTitle())
            assertNull("nothing may be shared", started)
        }
    }

    // ---- positive controls for the share guard ----------------------------

    @Test
    fun `multi share of ordinary documents still shares both PDFs`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Ordinary one")
        DocFixtures.saved(repo, app, pages = 1, name = "Ordinary two")
        shareSelection(listOf("Ordinary one", "Ordinary two")) { started ->
            assertNotNull("ordinary multi share must still open the sharesheet", started)
            assertEquals(Intent.ACTION_CHOOSER, started!!.action)
            val send = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull(send)
            val uris = send!!.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            assertEquals(2, uris?.size ?: -1)
        }
    }

    @Test
    fun `a locked encrypted document is still allowed by the share guard`() {
        val locked = DocFixtures.saved(repo, app, pages = 1, name = "Encrypted source")
        runBlocking { repo.protectPdf(locked, "1234") }
        assertEquals(PdfProtection.State.LOCKED, repo.protectionState(locked))
        DocFixtures.saved(repo, app, pages = 1, name = "Ordinary source")
        shareSelection(listOf("Encrypted source", "Ordinary source")) { started ->
            assertNotNull("LOCKED shares the encrypted PDF by design", started)
            val send = started!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertEquals(2, send!!.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.size ?: -1)
        }
    }

    // ---- R05 NEW-1: Batch refuses unsafe sources --------------------------

    @Test
    fun `batch refuses a repairable selection before producing any output`() {
        val bad = repairable("Batch broken")
        val good = DocFixtures.saved(repo, app, pages = 1, name = "Batch good")
        val before = repo.listDocuments(includeVaulted = true).size
        ActivityScenario.launch<BatchActivity>(BatchActivity.intent(app, listOf(bad, good))).use { scenario ->
            assertTrue("action sheet opens", waitUntil {
                ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchActionExport) != null
            })
            scenario.onActivity { _ ->
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.batchActionExport).performClick()
                idle()
                assertEquals(app.getString(R.string.protection_repair_title), latestTitle())
            }
        }
        assertEquals("no batch copy may be published", before, repo.listDocuments(includeVaulted = true).size)
        assertTrue("nothing written to Documents", repo.exportsOf(bad).isEmpty() && repo.exportsOf(good).isEmpty())
    }

    @Test
    fun `batch still copies ordinary documents`() {
        val a = DocFixtures.saved(repo, app, pages = 1, name = "Batch one")
        val b = DocFixtures.saved(repo, app, pages = 1, name = "Batch two")
        val before = repo.listDocuments().size
        ActivityScenario.launch<BatchActivity>(BatchActivity.intent(app, listOf(a, b))).use { scenario ->
            assertTrue("action sheet opens", waitUntil {
                ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchActionCompress) != null
            })
            scenario.onActivity { _ ->
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.batchActionCompress).performClick()
                idle()
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.swSheetPrimary).performClick()
            }
            assertTrue("two copies published", waitUntil {
                repo.listDocuments().count { it.name.endsWith(" (2)") } == 2
            })
            assertTrue("summary opens", waitUntil {
                ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchSummary) != null
            })
        }
        assertEquals(before + 2, repo.listDocuments().size)
    }

    // ---- R07 NEW-2: Merge refuses Vault sources ---------------------------

    @Test
    fun `merge refuses a vaulted source and publishes nothing`() = runBlocking {
        val plain = DocFixtures.saved(repo, app, pages = 1, name = "Merge plain")
        val vaulted = DocFixtures.saved(repo, app, pages = 1, name = "Merge vaulted")
        repo.setVaulted(vaulted, true)
        val before = repo.listDocuments(includeVaulted = true).map { it.id }.toSet()
        val failure = runCatching { repo.mergeDocuments(listOf(plain, vaulted), "Sneaky merge") }
        assertTrue("merge of a Vault source must fail: ${failure.exceptionOrNull()}", failure.isFailure)
        assertTrue("same IOException as extractPages", failure.exceptionOrNull() is java.io.IOException)
        assertEquals(app.getString(R.string.doc_actions_vault_reason), failure.exceptionOrNull()?.message)
        assertEquals(before, repo.listDocuments(includeVaulted = true).map { it.id }.toSet())
    }

    @Test
    fun `merge of two ordinary documents still works`() = runBlocking {
        val a = DocFixtures.saved(repo, app, pages = 1, name = "Merge A")
        val b = DocFixtures.saved(repo, app, pages = 1, name = "Merge B")
        val merged = repo.mergeDocuments(listOf(a, b), "Merged normally")
        assertEquals("Merged normally", merged.name)
        assertEquals(2, merged.pageFiles.size)
    }

    @Test
    fun `merge picker does not offer vaulted or locked documents`() {
        val a = DocFixtures.saved(repo, app, pages = 1, name = "Picker normal A")
        val b = DocFixtures.saved(repo, app, pages = 1, name = "Picker normal B")
        val vaulted = DocFixtures.saved(repo, app, pages = 1, name = "Picker vault")
        repo.setVaulted(vaulted, true)
        val locked = DocFixtures.saved(repo, app, pages = 1, name = "Picker locked")
        runBlocking { repo.protectPdf(locked, "1234") }
        val all = repo.listDocuments(includeVaulted = true)
        assertEquals(4, all.size)

        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        try {
            val activity = controller.get()
            val host = object : HomeHost {
                override fun refreshList() = Unit
                override fun showProgress(titleRes: Int) = SwProgress.show(activity, "test")
                override fun toast(res: Int, duration: Int) = Unit
                override fun toast(message: CharSequence, duration: Int) = Unit
            }
            MergeFlow(activity, repo, host, { all }) { }.showMergeChooser()
            idle()
            assertEquals(app.getString(R.string.merge_chooser_title), latestTitle())
            val chooserRows = ShadowDialog.getLatestDialog()!!.findViewById<ViewGroup>(R.id.swSheetItems)
            assertEquals(2, chooserRows.childCount)
            chooserRows.getChildAt(0).performClick() // "Saved scans"
            idle()
            val pickerRows = ShadowDialog.getLatestDialog()!!.findViewById<ViewGroup>(R.id.swSheetItems)
            assertEquals(2, pickerRows.childCount)
            val labels = (0 until pickerRows.childCount)
                .map { pickerRows.getChildAt(it).contentDescription.toString() }.toSet()
            assertEquals(setOf("Picker normal A", "Picker normal B"), labels)
            assertFalse(labels.any { it.contains("vault") || it.contains("locked") })
        } finally {
            controller.destroy()
        }
    }
}
