package com.scanwhirl.app.ui.protect
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.app.Dialog
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.HomeHost
import com.scanwhirl.app.tools.LockFlow
import com.scanwhirl.app.ui.kit.SwProgress
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfDecryptor
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/** Minimal themed Home host: LockFlow only needs these four calls from its host. */
class K1LockHostActivity : AppCompatActivity(), HomeHost {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    override fun refreshList() = Unit
    override fun showProgress(titleRes: Int): SwProgress =
        SwProgress.show(this, getString(titleRes))
    override fun toast(res: Int, duration: Int) = Unit
    override fun toast(message: CharSequence, duration: Int) = Unit
}

/**
 * K1 defects 5 and 6: an import whose completion outlives its Activity must still publish the
 * document (so the Library shows it) but must never start a viewer from a finished host. And a
 * broken PDF must be described as damaged, not as an unsupported password format.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImportLifecycleTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private var controller: ActivityController<K1LockHostActivity>? = null
    private val looper get() = android.os.Looper.getMainLooper()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanWhirlApp.from(app).repository
        ProGate.setTestOverride(app, true)
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        ScanRepository.resetTestSeams()
        PdfDecryptor.resetForTests()
        ProGate.setTestOverride(app, null)
        ShadowDialog.reset()
    }

    private fun idle() = shadowOf(looper).idle()

    private fun pollUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun host(): K1LockHostActivity {
        val c = Robolectric.buildActivity(K1LockHostActivity::class.java).setup()
        controller = c
        return c.get()
    }

    private fun sheet(): Dialog? =
        ShadowDialog.getLatestDialog()?.takeIf { it.findViewById<View>(R.id.unlockPassword) != null }

    private fun submit(dialog: Dialog, text: String) {
        dialog.findViewById<EditText>(R.id.unlockPassword).setText(text)
        dialog.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()
    }

    private fun register(uri: Uri) = shadowOf(app.contentResolver).registerInputStreamSupplier(uri) {
        ByteArrayInputStream("%PDF-1.7 protected".toByteArray())
    }

    @Test
    fun `an import finishing after the host is finishing publishes the file but opens nothing`() {
        val page = ImportFixtures.image(width = 160, height = 220)
        val uri = Uri.parse("content://scanwhirl-import/late.pdf")
        register(uri)
        val began = java.util.concurrent.atomic.AtomicBoolean(false)
        val releaseTimedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val release = java.util.concurrent.CountDownLatch(1)
        val activity = host()
        val before = repo.listDocuments().map { it.id }.toSet()

        LockFlow(
            activity, repo, activity, { emptyList() }, { _ -> },
            importProtected = { _, name, _ ->
                began.set(true)
                // Hold the decrypt until the host has been asked to finish, but never forever:
                // a missed signal now fails this test instead of hanging the whole CI JVM.
                if (!release.await(8, java.util.concurrent.TimeUnit.SECONDS)) {
                    releaseTimedOut.set(true)
                }
                repo.createDocument(name ?: "Late", listOf(page))
            }
        ).importProtectedPdf(uri, "Late import")

        assertTrue("the Unlock sheet opens", pollUntil { sheet() != null })
        val dialog = sheet()!!
        submit(dialog, "1234")
        assertTrue("the import started", pollUntil { began.get() })

        // finish() (without destroy) is the rotation path: the old host is no longer usable, but
        // its coroutine scope is still alive, so the base code would start a viewer from it.
        activity.finish()
        assertTrue("the host is finishing", activity.isFinishing)
        release.countDown()
        assertFalse("the import seam waited too long to be released after finish()", releaseTimedOut.get())

        assertTrue(
            "the document is still created",
            pollUntil { repo.listDocuments().map { it.id }.toSet() != before }
        )
        idle()
        assertNull(
            "no viewer may start from a finished host",
            shadowOf(activity).peekNextStartedActivity()
        )
        assertTrue(
            "the new document is visible in the Library",
            repo.listDocuments().any { it.name == "Late import" }
        )
    }

    @Test
    fun `an import with a live host still opens the imported document`() {
        val page = ImportFixtures.image(width = 160, height = 220)
        val uri = Uri.parse("content://scanwhirl-import/live.pdf")
        register(uri)
        val activity = host()

        LockFlow(
            activity, repo, activity, { emptyList() }, { _ -> },
            importProtected = { _, name, _ -> repo.createDocument(name ?: "Live", listOf(page)) }
        ).importProtectedPdf(uri, "Live import")

        assertTrue("the Unlock sheet opens", pollUntil { sheet() != null })
        val dialog = sheet()!!
        submit(dialog, "1234")
        assertTrue(
            "the live host opens the viewer",
            pollUntil { shadowOf(activity).peekNextStartedActivity() != null }
        )
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertTrue(
            "the viewer is DocumentViewerActivity",
            started!!.component?.className.orEmpty().contains("DocumentViewerActivity")
        )
    }

    @Test
    fun `a damaged protected PDF reports a damaged file, not an unsupported password format`() {
        val uri = Uri.parse("content://scanwhirl-import/damaged.pdf")
        register(uri)
        val activity = host()

        LockFlow(
            activity, repo, activity, { emptyList() }, { _ -> },
            importProtected = { _, _, _ -> throw PdfDecryptor.Unsupported("This PDF's xref is broken") }
        ).importProtectedPdf(uri, "Damaged import")

        assertTrue("the Unlock sheet opens", pollUntil { sheet() != null })
        val dialog = sheet()!!
        submit(dialog, "1234")
        val error = dialog.findViewById<TextView>(R.id.swPasswordError)
        assertNotNull(error)
        assertTrue(
            "a broken PDF is described as damaged",
            pollUntil { error!!.text.contains("looks damaged") }
        )
        assertFalse(
            "a broken PDF is not an unsupported password format",
            error!!.text.contains("password format")
        )
    }

    @Test
    fun `an unsupported password scheme still gets the scheme explanation`() {
        val uri = Uri.parse("content://scanwhirl-import/scheme.pdf")
        register(uri)
        val activity = host()
        // Reflection keeps this test compiling against the base revision, where the split does not
        // exist yet: on base the className lookup fails inside the seam and the sheet shows the
        // generic import failure, which is a RED result for this assertion.
        val scheme: IOException = Class.forName("com.scanwhirl.app.util.PdfDecryptor\$UnsupportedScheme")
            .getConstructor(String::class.java)
            .newInstance("scheme") as IOException

        LockFlow(
            activity, repo, activity, { emptyList() }, { _ -> },
            importProtected = { _, _, _ -> throw scheme }
        ).importProtectedPdf(uri, "Scheme import")

        assertTrue("the Unlock sheet opens", pollUntil { sheet() != null })
        val dialog = sheet()!!
        submit(dialog, "1234")
        val error = dialog.findViewById<TextView>(R.id.swPasswordError)
        assertNotNull(error)
        assertTrue(
            "an unknown password format is explained as such",
            pollUntil { error!!.text.contains("password format") }
        )
    }

    @Test
    fun `a well formed shape we cannot rewrite gets an honest message`() {
        val uri = Uri.parse("content://scanwhirl-import/shape.pdf")
        register(uri)
        val activity = host()
        // Reflection keeps this test compiling against the base revision, where the split does not
        // exist yet: on base the lookup fails inside the seam and the sheet shows a generic failure.
        val shape: IOException = Class.forName("com.scanwhirl.app.util.PdfDecryptor\$CannotRewrite")
            .getConstructor(String::class.java)
            .newInstance("incremental update") as IOException

        LockFlow(
            activity, repo, activity, { emptyList() }, { _ -> },
            importProtected = { _, _, _ -> throw shape }
        ).importProtectedPdf(uri, "Shape import")

        assertTrue("the Unlock sheet opens", pollUntil { sheet() != null })
        val dialog = sheet()!!
        submit(dialog, "1234")
        val error = dialog.findViewById<TextView>(R.id.swPasswordError)
        assertNotNull(error)
        assertTrue(
            "a valid but unrewritable shape is explained honestly",
            pollUntil { error!!.text.contains("cannot open this kind") }
        )
        assertFalse("it is not called damaged", error!!.text.contains("looks damaged"))
    }

    @Test
    fun `the damaged and scheme messages exist in both languages`() {
        val en = app.resources.getString(R.string.import_unlock_failed) // sanity: resources resolve
        assertEquals("Could not import this PDF. Try again.", en)
        for (id in listOf("import_unlock_damaged", "lock_op_failed")) {
            val name = app.resources.getIdentifier(id, "string", app.packageName)
            assertTrue("$id must be a string resource", name != 0)
            assertTrue("$id must be English", app.resources.getString(name).isNotBlank())
        }
    }

    @Test
    fun `the cannot rewrite message exists in English and Hindi`() {
        val en = Regex("<string\\s+name=\"([^\"]+)\"")
            .findAll(File("src/main/res/values/strings_protect.xml").readText(Charsets.UTF_8))
            .map { it.groupValues[1] }.toSet()
        val hi = Regex("<string\\s+name=\"([^\"]+)\"")
            .findAll(File("src/main/res/values-hi/strings_protect.xml").readText(Charsets.UTF_8))
            .map { it.groupValues[1] }.toSet()
        assertTrue("import_unlock_cannot_rewrite missing from English", "import_unlock_cannot_rewrite" in en)
        assertTrue("import_unlock_cannot_rewrite missing from Hindi", "import_unlock_cannot_rewrite" in hi)
        val hiText = File("src/main/res/values-hi/strings_protect.xml").readText(Charsets.UTF_8)
        for (banned in listOf("वॉल्ट", "पेज", "सेव", "शेयर", "साइज़", "टूल")) {
            assertFalse("glossary term [$banned] is not allowed", hiText.contains(banned))
        }
    }
}
