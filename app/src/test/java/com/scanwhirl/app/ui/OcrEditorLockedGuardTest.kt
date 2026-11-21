package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * C2 item 2: a direct EXTRA_DOC_ID launch for a locked or Vaulted document must refuse before
 * any page text or image is loaded. The fixture stores a recognisable secret in OCR so a
 * regression fails loudly instead of leaking quietly.
 *
 * RED on base 282630d1: base loads and shows the secret and stays open.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrEditorLockedGuardTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
    }

    /** A saved document with a secret stored in OCR; returns its id and the secret text. */
    private fun secretDoc(): Pair<String, String> {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Private")
        DocFixtures.withOcr(repo, id)
        return id to "text page_000.jpg"
    }

    private fun launchAndCheck(id: String, secret: String, shouldRefuse: Boolean) {
        val intent = Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
        // Robolectric.buildActivity keeps the instance observable even when onCreate finishes it.
        val controller = Robolectric.buildActivity(OcrEditorActivity::class.java, intent).setup()
        val activity = controller.get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val shown = activity.findViewById<EditText>(R.id.editText)?.text?.toString().orEmpty()
        if (shouldRefuse) {
            assertTrue("a private document is refused and finishes", activity.isFinishing)
            assertFalse("no page text is shown", shown.contains(secret))
            assertTrue("an honest message is shown", ShadowToast.getLatestToast() != null)
        } else {
            assertFalse("an ordinary document still opens", activity.isFinishing)
            assertTrue("its text is shown", shown.contains(secret))
        }
        controller.destroy()
    }

    @Test
    fun `a locked document is refused without showing its text`() {
        val (id, secret) = secretDoc()
        File(File(repo.docsDir, id), "locked.meta").writeText("1\n1234\n")
        launchAndCheck(id, secret, shouldRefuse = true)
    }

    @Test
    fun `a vaulted document is refused without showing its text`() {
        val (id, secret) = secretDoc()
        File(File(repo.docsDir, id), "vault.flag").writeText("1")
        launchAndCheck(id, secret, shouldRefuse = true)
    }

    @Test
    fun `a document that is neither locked nor vaulted still opens`() {
        val (id, secret) = secretDoc()
        launchAndCheck(id, secret, shouldRefuse = false)
    }
}
