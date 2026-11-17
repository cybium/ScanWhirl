package com.scanwhirl.app.ui.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.IdCard
import com.scanwhirl.app.util.PaperSize
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * v2 UI: 03_tools_hub — the ID card flow as people use it: front capture, "Add the back side?",
 * Layout, then one document through the publish path.
 *
 * The ML Kit camera cannot run under Robolectric; the screen opens the front sheet first, and
 * [IdCardActivity.acceptCapture] is the seam for a captured side (the same method the scanner
 * result and the gallery picker call).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class IdCardFlowTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @Before
    fun setUp() {
        app.getSharedPreferences("scanwhirl_id_card", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        Faults.resetAll()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun jpeg(color: Int, width: Int = 900, height: Int = 560): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(color)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

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

    private fun clickSheet(id: Int) {
        idle()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "no sheet is open" }
        val button = requireNotNull(dialog.findViewById<TextView>(id)) { "sheet has no view $id" }
        button.performClick()
        idle()
    }

    /** Clicks a quiet action inside the sheet body (for example "Front only"). */
    private fun clickSheetText(textRes: Int) {
        idle()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "no sheet is open" }
        val wanted = app.getString(textRes)
        val found = mutableListOf<TextView>()
        fun walk(view: View) {
            if (view is TextView && view.text.toString() == wanted) found += view
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(dialog.window!!.decorView)
        requireNotNull(found.firstOrNull()) { "sheet has no action \"$wanted\"" }.performClick()
        idle()
    }

    private fun newestDoc() = repo.listDocuments().first()

    private fun pageColor(docId: String, pageIndex: Int, x: Int, y: Int): Int {
        val page = requireNotNull(repo.getDocument(docId)).pageFiles[pageIndex]
        val bitmap = BitmapFactory.decodeFile(page.path)
        try {
            return bitmap.getPixel(x, y)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 60) {
        assertTrue(
            "expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                abs(Color.blue(expected) - Color.blue(actual)) <= tolerance
        )
    }

    // ---- flow -------------------------------------------------------------

    // fixflow: owner bug W6 - a front-only card now has one layout only (the front on its own
    // page), because "both sides on one page" cannot be filled without a back side.
    @Test
    fun `front only creates one page with the front on its own page`() {
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCapture(IdCardActivity.Side.FRONT, jpeg(Color.RED))
                // "Add the back side?" → Front only (a quiet action in the sheet body).
                clickSheetText(R.string.tools_id_front_only)
                // Layout → the only layout left: the front on one page.
                clickSheet(R.id.swSheetPrimary)
            }
            assertTrue("the card document is created", waitUntil { repo.listDocuments().isNotEmpty() })
        }

        val doc = newestDoc()
        assertEquals("front only is one page", 1, doc.pageCount)
        val centre = pageColor(doc.id, 0, IdCard.WIDTH / 2, IdCard.singleRect().centerY().toInt())
        val top = pageColor(doc.id, 0, IdCard.WIDTH / 2, 80)
        assertColor(Color.RED, centre)
        assertColor(Color.WHITE, top)
        assertEquals(1, repo.listDocuments().size)
    }

    @Test
    fun `letter paper is chosen, remembered and used for the page`() {
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCapture(IdCardActivity.Side.FRONT, jpeg(Color.RED))
                activity.acceptCapture(IdCardActivity.Side.BACK, jpeg(Color.BLUE))
                clickSheetText(R.string.tools_id_paper_letter)
                clickSheet(R.id.swSheetPrimary)
            }
            assertTrue("the card document is created", waitUntil { repo.listDocuments().isNotEmpty() })
        }
        assertEquals(PaperSize.LETTER, IdCardPrefs.paper(app))
        val doc = newestDoc()
        val page = newestPageFile(doc.id, 0)
        val bitmap = BitmapFactory.decodeFile(page.path)
        try {
            assertEquals(PaperSize.LETTER.width, bitmap.width)
            assertEquals(PaperSize.LETTER.height, bitmap.height)
            val card = IdCard.frontRect(PaperSize.LETTER)
            assertColor(Color.RED, bitmap.getPixel(card.centerX().toInt(), card.centerY().toInt()))
            assertEquals(
                IdCard.ID1_RATIO,
                card.width() / card.height(),
                0.01f
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `front and back on one page contains both halves`() {
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCapture(IdCardActivity.Side.FRONT, jpeg(Color.RED))
                // The back-scan step is simulated with the same capture seam the scanner uses.
                activity.acceptCapture(IdCardActivity.Side.BACK, jpeg(Color.BLUE))
                // Layout → both sides on one page (remembered default primary).
                clickSheet(R.id.swSheetPrimary)
            }
            assertTrue(waitUntil { repo.listDocuments().isNotEmpty() })
        }

        val doc = newestDoc()
        assertEquals(1, doc.pageCount)
        val top = pageColor(doc.id, 0, IdCard.WIDTH / 2, IdCard.frontRect().centerY().toInt())
        val bottom = pageColor(doc.id, 0, IdCard.WIDTH / 2, IdCard.backRect().centerY().toInt())
        assertColor(Color.RED, top)
        assertColor(Color.BLUE, bottom)
    }

    @Test
    fun `each side on its own page creates two pages and is remembered`() {
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCapture(IdCardActivity.Side.FRONT, jpeg(Color.RED))
                activity.acceptCapture(IdCardActivity.Side.BACK, jpeg(Color.BLUE))
                // Layout → the other option (secondary) is "Each side on its own page".
                clickSheet(R.id.swSheetSecondary)
            }
            assertTrue(waitUntil { repo.listDocuments().isNotEmpty() })
        }

        val doc = newestDoc()
        assertEquals(2, doc.pageCount)
        assertColor(Color.RED, pageColor(doc.id, 0, IdCard.WIDTH / 2, IdCard.singleRect().centerY().toInt()))
        assertColor(Color.BLUE, pageColor(doc.id, 1, IdCard.WIDTH / 2, IdCard.singleRect().centerY().toInt()))
        assertEquals(IdCardPrefs.Layout.OWN_PAGES, IdCardPrefs.layout(app))
    }

    @Test
    fun `leaving the flow before the layout creates nothing`() {
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCapture(IdCardActivity.Side.FRONT, jpeg(Color.RED))
                idle()
            }
            // No layout choice: leave the screen.
            scenario.onActivity { it.finish() }
        }
        idle()
        assertTrue("cancelling must create no document", repo.listDocuments().isEmpty())
    }

    @Test
    fun `front sheet offers the camera and the gallery`() {
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity {
                idle()
                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                assertEquals(
                    it.getString(R.string.tools_id_front_title),
                    (dialog.findViewById<TextView>(R.id.swSheetTitle)).text.toString()
                )
                assertEquals(
                    it.getString(R.string.tools_id_scan_camera),
                    dialog.findViewById<TextView>(R.id.swSheetPrimary).text.toString()
                )
                assertEquals(
                    it.getString(R.string.tools_id_pick_gallery),
                    dialog.findViewById<TextView>(R.id.swSheetSecondary).text.toString()
                )
            }
        }
        assertTrue(repo.listDocuments().isEmpty())
    }

    // fixflow: owner bug W4 - "Pick from gallery" opens Android's photo picker directly now; the
    // Google camera scanner (launchGalleryCrop) is no longer part of this button.
    @Test
    fun `the id card gallery opens the android photo picker`() {
        var scannerStarted = false
        com.scanwhirl.app.tools.GalleryCrop.startOverride = { scannerStarted = true; true }
        try {
            ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
                scenario.onActivity {
                    idle()
                    val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                    dialog.findViewById<View>(R.id.swSheetSecondary).performClick()
                    idle()
                    val picker = shadowOf(it).nextStartedActivityForResult
                    assertNotNull("Pick from gallery opens the photo picker", picker)
                    assertEquals("image/*", picker.intent.type)
                }
            }
            assertTrue("the Google camera scanner must not be involved", !scannerStarted)
        } finally {
            com.scanwhirl.app.tools.GalleryCrop.startOverride = null
        }
    }

    @Test
    fun `back sheet asks for the back and still offers front only`() {
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCapture(IdCardActivity.Side.FRONT, jpeg(Color.RED))
                idle()
                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                assertEquals(
                    activity.getString(R.string.tools_id_back_title),
                    (dialog.findViewById<TextView>(R.id.swSheetTitle)).text.toString()
                )
                assertEquals(
                    activity.getString(R.string.tools_id_scan_camera),
                    dialog.findViewById<TextView>(R.id.swSheetPrimary).text.toString()
                )
                assertEquals(
                    activity.getString(R.string.tools_id_pick_gallery),
                    dialog.findViewById<TextView>(R.id.swSheetSecondary).text.toString()
                )
                val texts = mutableListOf<String>()
                fun walk(view: View) {
                    if (view is TextView) texts += view.text.toString()
                    if (view is android.view.ViewGroup) {
                        for (i in 0 until view.childCount) walk(view.getChildAt(i))
                    }
                }
                walk(dialog.window!!.decorView)
                assertTrue(
                    "Front only must stay reachable: $texts",
                    texts.contains(activity.getString(R.string.tools_id_front_only))
                )
            }
        }
        assertTrue("no layout choice yet, so nothing is created", repo.listDocuments().isEmpty())
    }

    private fun newestPageFile(docId: String, index: Int): File =
        requireNotNull(repo.getDocument(docId)).pageFiles[index]
}
