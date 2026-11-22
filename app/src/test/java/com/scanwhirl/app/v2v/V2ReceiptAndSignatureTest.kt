package com.scanwhirl.app.v2v
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.stamp.StampEditorActivity
import com.scanwhirl.app.ui.stamp.StampSaveRunner
import com.scanwhirl.app.util.PageStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** V2V V2: persistent redaction receipt with a named Trash route, and a
 *  one-step undoable "apply signature to all pages" action. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class V2ReceiptAndSignatureTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)

    private fun controller(id: String): ActivityController<StampEditorActivity> =
        Robolectric.buildActivity(
            StampEditorActivity::class.java,
            Intent(app, StampEditorActivity::class.java)
                .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

    @Test(timeout = 30_000)
    fun `redaction save shows a persistent receipt naming the copy and the retained original`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "V2 receipt")
        val controller = controller(id)
        try {
            val screen = controller.setup().get()
            val copy = repo.getDocument(id)!!
            screen.deliverSaveForTest(Result.success(copy), R.string.c1_redacted_label)
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("a persistent receipt sheet must be shown", sheet)
            assertTrue("the receipt must stay up, not vanish", sheet.isShowing)
            val message = sheet.findViewById<TextView>(R.id.swSheetMessage).text.toString()
            assertTrue("receipt must name the new copy: $message", message.contains(copy.name))
            assertTrue("receipt must state the original still has the information: $message",
                message.contains(app.getString(R.string.cdf1_redact_receipt_body, copy.name)))
            assertFalse("the screen must stay open behind the receipt", screen.isFinishing)
            val labels = (sheet.window?.decorView
                ?: sheet.findViewById<View>(android.R.id.content)).texts()
            assertTrue("a named Trash route must be offered: $labels",
                labels.any { it.contains("Move original to Trash") })
            StampSaveRunner.consume(id)
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `redaction receipt keeps primary safe and offers an undoable confirmed Trash route`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "V2 trash receipt")
        val controller = controller(id)
        try {
            val screen = controller.setup().get()
            screen.deliverSaveForTest(Result.success(repo.getDocument(id)!!), R.string.c1_redacted_label)
            val receipt = ShadowDialog.getLatestDialog()
            val primary = receipt.findViewById<TextView>(R.id.swSheetPrimary).text.toString()
            val secondary = receipt.findViewById<TextView>(R.id.swSheetSecondary).text.toString()
            assertEquals("Keep original must be the primary", app.getString(R.string.v2v_keep_original), primary)
            assertEquals("Trash must be the secondary", app.getString(R.string.v2v_move_original_to_trash), secondary)

            receipt.findViewById<View>(R.id.swSheetSecondary).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val confirm = ShadowDialog.getLatestDialog()
            assertNotNull("a confirm sheet must appear before any deletion", confirm)
            assertTrue("original still exists before confirmation", repo.getDocument(id) != null)
            assertEquals("confirm also keeps Keep primary", app.getString(R.string.v2v_keep_original),
                confirm.findViewById<TextView>(R.id.swSheetPrimary).text.toString())

            confirm.findViewById<View>(R.id.swSheetSecondary).performClick()
            val deadline = System.currentTimeMillis() + 8000L
            while (repo.getDocument(id) != null && System.currentTimeMillis() < deadline) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertTrue("the original must be in Trash after confirmation", repo.getDocument(id) == null)
            val barTexts = mutableListOf<String>()
            fun walk(v: View) {
                if (v is TextView) v.text?.toString()?.let { barTexts += it }
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            screen.window?.decorView?.let { walk(it) }
            assertTrue("the Trash route must offer Undo: $barTexts",
                barTexts.any { it == app.getString(R.string.undo) })
            StampSaveRunner.consume(id)
        } finally {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `signature wording says all pages everywhere`() {
        val en = java.io.File("src/main/res/values/strings_copydiet_fx1.xml").readText(Charsets.UTF_8)
        assertTrue("EN must say all pages",
            en.contains("<string name=\"cdf1_apply_signature\">Sign all pages</string>"))
        assertFalse("EN must not claim selected pages", en.contains("selected pages"))
        val id = DocFixtures.saved(repo, app, pages = 2)
        val controller = controller(id)
        try {
            val screen = controller.setup().get()
            val tile = screen.findViewById<View>(
                screen.resources.getIdentifier("v2vApplySignature", "id", screen.packageName))
            assertNotNull("the all-pages signature tile must exist", tile)
            assertEquals("the tile's spoken name must say all pages",
                app.getString(R.string.cdf1_apply_signature), tile!!.contentDescription?.toString())
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `apply signature to all pages places marks with one-step undo`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "V2 signature")
        val controller = controller(id)
        try {
            val screen = controller.setup().get()
            val bmp = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.RED)
            val file = File(app.cacheDir, "v2v_signature_${System.nanoTime()}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(screen.editor.marks().isEmpty())
            screen.applySignatureToAllForTest(file)
            for (page in 0 until 3) {
                val pics = screen.editor.marks(page).filterIsInstance<PageStamp.Mark.Picture>()
                assertEquals("one signature on page $page", 1, pics.size)
            }
            assertTrue("undo must be available in one step", screen.editor.canUndo)
            screen.editor.undo()
            assertTrue("undo restores the empty document", screen.editor.marks().isEmpty())
        } finally {
            controller.destroy()
        }
    }

    private fun View.texts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) v.text?.toString()?.let { out += it }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        return out
    }
}
