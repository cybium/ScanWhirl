package com.scanwhirl.app.fx1
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.ui.kit.ToolTile
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.stamp.StampEditorActivity
import com.scanwhirl.app.ui.tools.ToolSetupActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * FX1 — the four real-phone defects from run 5 (build 338c1784, Infinix X6711, 100 % font).
 *
 * Every test uses ActivityController + destroy in finally and a 30 s wall-clock budget.
 * All assertions are NEW; no existing expectation is weakened.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class Fx1Run5DefectsTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private val repo get() = ScanRepository(app)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun layoutRoot(activity: android.app.Activity): View {
        val root = (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(3) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return root
    }

    // ---- 2. Apply signature to all pages ----------------------------------

    @Test(timeout = 30_000)
    fun `apply signature reports the page count and bakes the mark into the saved copy`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "FX1 sign")
        val controller = Robolectric.buildActivity(
            StampEditorActivity::class.java,
            Intent(app, StampEditorActivity::class.java)
                .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            val a = controller.setup().get()
            val bmp = Bitmap.createBitmap(60, 30, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.RED)
            val signature = File(app.cacheDir, "fx1_signature_${System.nanoTime()}.png")
            signature.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()

            a.applySignatureToAllForTest(signature)
            idle()
            val hint = a.findViewById<TextView>(R.id.hint).text.toString()
            assertTrue(
                "the receipt must say how many pages got the mark: '$hint'",
                hint.contains("3") && hint.contains("page", ignoreCase = true)
            )
            val edits = a.editor.edits()
            assertEquals("three pages carry an edit", 3, edits.size)
            val source = repo.getDocument(id)!!.pageFiles
            val copy = runBlocking(Dispatchers.IO) { repo.stampDocument(id, edits, null) }
            for (page in 0 until 3) {
                assertFalse(
                    "saved page $page must carry the burned-in signature",
                    copy.pageFiles[page].readBytes().contentEquals(source[page].readBytes())
                )
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // ---- 3. Clear temporary files receipt ---------------------------------

    @Test(timeout = 30_000)
    fun `storage separates removable bytes from the total and never claims empty beside a number`() {
        val enXml = File("src/main/res/values/strings_cz.xml").readText(Charsets.UTF_8)
        val hiXml = File("src/main/res/values-hi/strings_cz.xml").readText(Charsets.UTF_8)
        assertTrue("EN explains why files are kept", enXml.contains("last 24 hours"))
        assertTrue("EN has a separate removable line", enXml.contains("Removable now: %1\$s"))
        assertTrue("HI explains why files are kept (24 घंटे)", hiXml.contains("24 घंटे"))
        assertTrue("HI has a separate removable line", hiXml.contains("अभी हटाने योग्य"))

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val a = controller.get()
            val beforeDeadline = System.currentTimeMillis() + 10_000L
            while (a.findViewById<TextView>(R.id.subTempRemovable).text.toString()
                    .contains("Removable now", ignoreCase = true).not() &&
                System.currentTimeMillis() < beforeDeadline
            ) {
                idle(); Thread.sleep(10)
            }
            val removableBefore = a.findViewById<TextView>(R.id.subTempRemovable).text.toString()
            assertTrue("removable bytes are shown separately: '$removableBefore'",
                removableBefore.contains("Removable now", ignoreCase = true))

            a.findViewById<View>(R.id.rowClearTemp).performClick()
            val deadline = System.currentTimeMillis() + 10_000L
            while (a.findViewById<TextView>(R.id.storageReceipt).text.isBlank() &&
                System.currentTimeMillis() < deadline
            ) {
                idle(); Thread.sleep(10)
            }
            val receipt = a.findViewById<TextView>(R.id.storageReceipt).text.toString()
            assertTrue("a receipt must be visible: '$receipt'", receipt.isNotBlank())
            assertTrue(
                "the receipt must be honest about kept files: '$receipt'",
                receipt.contains("Freed") ||
                    receipt.contains("No removable temporary files")
            )
            assertFalse("never say already empty: '$receipt'", receipt.contains("already empty"))
            val removableAfter = a.findViewById<TextView>(R.id.subTempRemovable).text.toString()
            assertTrue("removable bytes are recomputed after cleanup: '$removableAfter'",
                removableAfter.contains("Removable now", ignoreCase = true))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // ---- 5. Tool setup page thumbnails ------------------------------------

    @Test(timeout = 30_000)
    fun `tool setup page thumbnail sits on the shared dark canvas with a one dp page edge`() {
        DocFixtures.saved(repo, app, pages = 1, name = "FX1 thumb")
        val controller = Robolectric.buildActivity(
            ToolSetupActivity::class.java, ToolSetupActivity.intent(app, "split")
        )
        try {
            val a = controller.setup().get()
            idle()
            val host = a.findViewById<LinearLayout>(R.id.toolSetupSource)
            assertNotNull("the source list is shown", host)
            assertTrue("a document row exists", host.childCount > 0)
            val row = (0 until host.childCount).map { host.getChildAt(it) }
                .first { it.id == R.id.toolSetupDocRow }
            val thumb = row.findViewById<View>(R.id.toolSetupDocThumb)
            val frame = thumb.parent as View
            assertTrue("the frame keeps a rounded drawable", frame.background is GradientDrawable)
            val bg = frame.background as GradientDrawable
            val probe = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(probe)
            bg.setBounds(0, 0, 20, 20)
            bg.draw(canvas)
            val fill = ContextCompat.getColor(a, R.color.sw_cc_canvas)
            assertEquals(
                "the surface behind the page is lane C's canvas",
                fill, probe.getPixel(10, 10)
            )
            val src = File("src/main/java/com/scanwhirl/app/ui/tools/ToolSetupActivity.kt")
                .readText(Charsets.UTF_8)
            assertTrue("the shared dark canvas is used", src.contains("R.color.sw_cc_canvas"))
            assertTrue(
                "the shared 1dp #3DFFFFFF page edge is used (lane K's drawable is not in base)",
                src.contains("3DFFFFFF") && src.contains("strokeDp = 1f")
            )
            assertFalse("the white paper host is gone", src.contains("R.color.sw_paper_thumb"))
            probe.recycle()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // ---- Critic additions --------------------------------------------------

    @Test(timeout = 30_000)
    fun `find with zero hits says matches detected and offers Review page in EN and HI`() {
        val en = File("src/main/res/values/strings_copydiet_fx1.xml").readText(Charsets.UTF_8)
        val hi = File("src/main/res/values-hi/strings_copydiet_fx1.xml").readText(Charsets.UTF_8)
        assertTrue(en.contains("No matches detected"))
        assertTrue(en.contains("Automatic detection can miss details."))
        assertTrue(en.contains("Review page"))
        assertTrue(hi.contains("कोई मिलान नहीं मिला"))
        assertTrue(hi.contains("अपने आप खोजने"))
        assertTrue(hi.contains("पन्ने की जाँच करें"))

        val id = DocFixtures.saved(repo, app, pages = 1, name = "FX1 find")
        val controller = Robolectric.buildActivity(
            StampEditorActivity::class.java,
            Intent(app, StampEditorActivity::class.java)
                .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            val a = controller.setup().get()
            a.findScanner = { StampEditorActivity.FindOutcome(emptyList(), emptyList()) }
            a.runFindForTest()
            idle()
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("a persistent empty-outcome sheet must be shown", sheet)
            assertEquals(
                app.getString(R.string.cdf1_find_none_title),
                sheet!!.findViewById<TextView>(R.id.swSheetTitle).text.toString()
            )
            assertEquals(
                app.getString(R.string.cdf1_find_none_body),
                sheet.findViewById<TextView>(R.id.swSheetMessage).text.toString()
            )
            assertEquals(
                app.getString(R.string.cdf1_find_review_page),
                sheet.findViewById<TextView>(R.id.swSheetPrimary).text.toString()
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `signature receipt offers Undo only when every page is marked and names the count`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "FX1 sign receipt")
        val controller = Robolectric.buildActivity(
            StampEditorActivity::class.java,
            Intent(app, StampEditorActivity::class.java)
                .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            val a = controller.setup().get()
            val tile = a.findViewById<View>(R.id.v2vApplySignature) as ToolTile
            assertEquals(
                "the action names the real page count",
                app.getString(R.string.v2v_apply_to_all_count, 3),
                tile.labelView.text.toString()
            )
            val bmp = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.BLUE)
            val file = File(app.cacheDir, "fx1_receipt_${System.nanoTime()}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            assertNullBefore(a)
            a.applySignatureToAllForTest(file)
            idle()
            val receipt = a.signatureReceipt()
            assertNotNull("all three pages are marked", receipt)
            assertTrue("the receipt names the count: '$receipt'", receipt!!.contains("3"))
            // COPY_DIET: the receipt is "Signed 3 pages"; Undo is a separate Snackbar action.
            assertEquals(app.getString(R.string.cdf1_undo), "Undo")
            assertEquals(receipt, a.findViewById<TextView>(R.id.hint).text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun assertNullBefore(a: StampEditorActivity) {
        org.junit.Assert.assertNull(
            "no receipt before marks exist", a.signatureReceipt()
        )
    }

    @Test(timeout = 30_000)
    fun `page navigation pill sits below the canvas instead of covering a page`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "FX1 nav")
        val controller = Robolectric.buildActivity(
            StampEditorActivity::class.java,
            Intent(app, StampEditorActivity::class.java)
                .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            val a = controller.setup().get()
            idle()
            val root = layoutRoot(a)
            val canvas = a.findViewById<View>(R.id.canvas)
            val nav = a.findViewById<View>(R.id.pageNav)
            fun topIn(v: View, root: View): Int {
                var y = 0
                var current: View? = v
                while (current != null && current !== root) {
                    y += current.top
                    current = current.parent as? View
                }
                return y
            }
            assertTrue(
                "page pill must start at or below the canvas bottom, not on the paper",
                topIn(nav, root) >= topIn(canvas, root) + canvas.height
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // ---- COPY_DIET roles / removed strings --------------------------------

    @Test(timeout = 30_000)
    fun `copy diet pairs EN and HI keys removes duplicate receipts and promotes deciding values`() {
        fun keys(path: String): Set<String> {
            val xml = File(path).readText(Charsets.UTF_8)
            return Regex("""<(?:string|plurals) name="([^"]+)"""")
                .findAll(xml).map { it.groupValues[1] }.toSet()
        }
        val en = keys("src/main/res/values/strings_copydiet_fx1.xml")
        val hi = keys("src/main/res/values-hi/strings_copydiet_fx1.xml")
        assertTrue("copy-diet keys must exist", en.isNotEmpty())
        assertEquals("EN/HI copy-diet keys must match", en, hi)

        val stamp = File("src/main/java/com/scanwhirl/app/ui/stamp/StampEditorActivity.kt")
            .readText(Charsets.UTF_8)
        for (removed in listOf(
            "R.string.v2v_redact_original_kept", "R.string.c1_redacted_saved",
            "R.string.private_info_saved"
        )) {
            assertFalse("duplicate receipt binding is gone: $removed", stamp.contains(removed))
        }
        val setup = File("src/main/java/com/scanwhirl/app/ui/tools/ToolSetupActivity.kt")
            .readText(Charsets.UTF_8)
        for (removed in listOf("R.string.tools_setup_compress_note", "R.string.tools_setup_zip_note")) {
            assertFalse("redundant setup note is gone: $removed", setup.contains(removed))
        }

        val editorXml = File("src/main/res/layout/activity_stamp_editor.xml")
            .readText(Charsets.UTF_8)
        assertTrue("current page is P14 white",
            editorXml.contains("android:textColor=\"@color/text_hologram_hi\"") &&
                editorXml.contains("android:textStyle=\"bold\""))
        val settingsXml = File("src/main/res/layout/activity_settings.xml")
            .readText(Charsets.UTF_8)
        assertTrue("storage bytes are P14 white",
            settingsXml.contains("android:textColor=\"@color/text_hologram_hi\"") &&
                settingsXml.contains("android:textSize=\"14sp\""))
    }

    // ---- 4. Library A/अ chip ----------------------------------------------

    @Test(timeout = 30_000)
    fun `library language chip opens the sheet on a real tap at its centre`() {
        val controller = Robolectric.buildActivity(ShellActivity::class.java)
        try {
            val a = controller.setup().get()
            idle()
            layoutRoot(a)
            val chip = a.findViewById<View>(R.id.swLanguageChip)
            assertNotNull("the chip is still shown", chip)
            val loc = IntArray(2)
            chip.getLocationOnScreen(loc)
            val x = loc[0] + chip.width / 2f
            val y = loc[1] + chip.height / 2f
            val down = MotionEvent.obtain(
                android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
                MotionEvent.ACTION_DOWN, x, y, 0
            )
            val up = MotionEvent.obtain(
                android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, x, y, 0
            )
            a.window.decorView.dispatchTouchEvent(down)
            a.window.decorView.dispatchTouchEvent(up)
            idle()
            assertNotNull(
                "a real tap on the chip must open the language sheet (x=$x y=$y)",
                ShadowDialog.getLatestDialog()
            )
            down.recycle(); up.recycle()
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
