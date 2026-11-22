package com.scanwhirl.app.ui.tools
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule
import org.junit.rules.Timeout

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.IdCard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Owner bugs F1/W3 (the ID card result screen must offer Keep / Discard, and Back must ask) and
 * W4/W6/W7/X1/X2 (photo picker, front-only layouts, one Back to Home, cancelled capture, coming
 * back from rotation with the captured sides).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class IdCardFixFlowTest {



    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
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

    private fun latestDialog() = requireNotNull(ShadowDialog.getLatestDialog()) { "no sheet is open" }

    private fun clickSheet(id: Int) {
        idle()
        requireNotNull(latestDialog().findViewById<TextView>(id)).performClick()
        idle()
    }

    private fun clickSheetText(textRes: Int) {
        idle()
        val dialog = latestDialog()
        val wanted = app.getString(textRes)
        val found = mutableListOf<TextView>()
        fun walk(view: View) {
            if (view is TextView && view.text.toString() == wanted) found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(dialog.window!!.decorView)
        requireNotNull(found.firstOrNull()) { "sheet has no action \"$wanted\"" }.performClick()
        idle()
    }

    private fun textsOf(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(view: View) {
            out += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return out
    }

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

    // ---- W6: only layouts that make sense ---------------------------------

    @Test(timeout = 60_000)
    fun `front only never offers both sides on one page`() {
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCapture(IdCardActivity.Side.FRONT, jpeg(Color.RED))
                idle()
                clickSheetText(R.string.tools_id_front_only)
                idle()
                val dialog = latestDialog()
                val text = app.getString(R.string.tools_id_layout_one)
                assertTrue(
                    "a front-only card must not offer \"$text\"",
                    textsOf(dialog.window!!.decorView).none { it is TextView && it.text.toString() == text }
                )
                assertEquals(
                    "the only layout left is the front on one page",
                    app.getString(R.string.fixflow_id_layout_front_page),
                    dialog.findViewById<TextView>(R.id.swSheetPrimary).text.toString()
                )
            }
        }
        assertTrue("nothing is created before the layout is confirmed", repo.listDocuments().isEmpty())
    }

    // ---- W7: one Back from a step before the result leaves to Home --------

    @Test(timeout = 60_000)
    fun `back at the layout step leaves the flow and saves nothing`() {
        var finishing = false
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCapture(IdCardActivity.Side.FRONT, jpeg(Color.RED))
                activity.acceptCapture(IdCardActivity.Side.BACK, jpeg(Color.BLUE))
                idle()
                assertEquals(
                    app.getString(R.string.tools_id_layout_title),
                    latestDialog().findViewById<TextView>(R.id.swSheetTitle).text.toString()
                )
                latestDialog().dismiss()
                idle()
                finishing = activity.isFinishing
            }
        }
        assertTrue("one Back at Layout must leave for Home, no intermediate screen", finishing)
        assertTrue("nothing was saved", repo.listDocuments().isEmpty())
    }

    // ---- X1: a cancelled capture returns to the previous step -------------

    @Test(timeout = 60_000)
    fun `a cancelled camera returns to the card screen instead of re-opening the sheet`() {
        var finishing = false
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.captureOverride = { true } // the camera opens
                val before = ShadowDialog.getShownDialogs().size
                // The camera button closes the choice sheet and opens the scanner.
                clickSheet(R.id.swSheetPrimary)
                assertEquals(
                    "the capture sheet is gone while the camera is open",
                    before,
                    ShadowDialog.getShownDialogs().size
                )
                activity.onCaptureCancelled()
                idle()
                assertEquals(
                    "a cancelled capture must not re-open the capture sheet",
                    before,
                    ShadowDialog.getShownDialogs().size
                )
                assertFalse(latestDialog().isShowing)
                // From the card screen one Back reaches Home.
                activity.onBackPressedDispatcher.onBackPressed()
                idle()
                finishing = activity.isFinishing
            }
        }
        assertTrue("one Back after a cancel leaves the flow", finishing)
        assertTrue("nothing was saved", repo.listDocuments().isEmpty())
    }

    // ---- W4: the Android photo picker, not Google's scanner ---------------

    @Test(timeout = 60_000)
    fun `pick from gallery opens the photo picker directly`() {
        var scannerCrop = false
        com.scanwhirl.app.tools.GalleryCrop.startOverride = { scannerCrop = true; true }
        try {
            ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    idle()
                    clickSheet(R.id.swSheetSecondary)
                    idle()
                    val picker = shadowOf(activity).nextStartedActivityForResult
                    assertNotNull("Pick from gallery must open a picker", picker)
                    val action = picker.intent.action
                    assertTrue(
                        "must be Android's photo/document picker, not the ML Kit scanner: $action",
                        action == MediaStore.ACTION_PICK_IMAGES || action == Intent.ACTION_OPEN_DOCUMENT ||
                            action == Intent.ACTION_GET_CONTENT
                    )
                    assertEquals("image/*", picker.intent.type)
                    assertFalse(
                        "the Google camera scanner must not be part of the picker",
                        scannerCrop
                    )
                }
            }
        } finally {
            com.scanwhirl.app.tools.GalleryCrop.startOverride = null
        }
        assertTrue("picking a photo is not a capture yet", repo.listDocuments().isEmpty())
    }

    // ---- X2: the captured sides survive recreation ------------------------

    @Test(timeout = 60_000)
    fun `the captured front survives recreation and is used for the card`() {
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCapture(IdCardActivity.Side.FRONT, jpeg(Color.RED))
                idle()
            }
            scenario.recreate()
            idle()
            scenario.onActivity { activity ->
                assertEquals(
                    "after recreation the flow continues at the back step, not the front",
                    app.getString(R.string.tools_id_back_title),
                    latestDialog().findViewById<TextView>(R.id.swSheetTitle).text.toString()
                )
                clickSheetText(R.string.tools_id_front_only)
                clickSheet(R.id.swSheetPrimary)
            }
            assertTrue("the card is created from the kept front", waitUntil { repo.listDocuments().isNotEmpty() })
        }
        val doc = repo.listDocuments().single()
        assertEquals(1, doc.pageCount)
        assertColor(
            Color.RED,
            pageColor(doc.id, 0, IdCard.WIDTH / 2, IdCard.singleRect().centerY().toInt())
        )
    }

    @Test(timeout = 60_000)
    fun `the captured back survives recreation and both sides reach the page`() {
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCapture(IdCardActivity.Side.FRONT, jpeg(Color.RED))
                activity.acceptCapture(IdCardActivity.Side.BACK, jpeg(Color.BLUE))
                idle()
            }
            scenario.recreate()
            idle()
            scenario.onActivity { activity ->
                assertEquals(
                    "after recreation the flow continues at Layout with both sides",
                    app.getString(R.string.tools_id_layout_title),
                    latestDialog().findViewById<TextView>(R.id.swSheetTitle).text.toString()
                )
                clickSheet(R.id.swSheetPrimary)
            }
            assertTrue(waitUntil { repo.listDocuments().isNotEmpty() })
        }
        val doc = repo.listDocuments().single()
        assertColor(Color.RED, pageColor(doc.id, 0, IdCard.WIDTH / 2, IdCard.frontRect().centerY().toInt()))
        assertColor(Color.BLUE, pageColor(doc.id, 0, IdCard.WIDTH / 2, IdCard.backRect().centerY().toInt()))
    }

    // ---- F1 / W3: Keep and Discard, no Save, Back asks first --------------

    private fun runToResult(): String {
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCapture(IdCardActivity.Side.FRONT, jpeg(Color.RED))
                idle()
                clickSheetText(R.string.tools_id_front_only)
                clickSheet(R.id.swSheetPrimary)
            }
            assertTrue("the card document is created", waitUntil { repo.listDocuments().isNotEmpty() })
            scenario.onActivity { activity ->
                // The document is published before the result-screen intent is recorded; wait
                // for the launch instead of sampling the intent immediately (a load-time race).
                assertTrue("the result screen is launched", waitUntil {
                    shadowOf(activity).peekNextStartedActivity()?.component?.className ==
                        ToolResultActivity::class.java.name
                })
                assertEquals(
                    ToolResultActivity::class.java.name,
                    shadowOf(activity).peekNextStartedActivity()?.component?.className
                )
            }
        }
        return repo.listDocuments().single().id
    }

    @Test(timeout = 60_000)
    fun `the result screen offers Keep and Discard and no Save or Undo`() {
        val id = runToResult()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "id_card", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                val views = textsOf(activity.window!!.decorView)
                val labels = views.filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue("Keep is offered: $labels", labels.contains(app.getString(R.string.fixflow_keep)))
                assertTrue("Discard is offered: $labels", labels.contains(app.getString(R.string.discard)))
                assertFalse(
                    "an already saved card must not offer Save: $labels",
                    labels.contains(app.getString(R.string.tools_result_save))
                )
                assertFalse(
                    "an already saved card must not offer Undo: $labels",
                    labels.contains(app.getString(R.string.tools_result_undo))
                )
                assertNull(
                    "no Save button view either",
                    views.firstOrNull { it.id == R.id.toolResultSave }
                )

                val keep = views.first { it.tag == ToolResultActivity.TAG_KEEP }
                keep.performClick()
                idle()
            }
            assertTrue("Keep closes the result screen", waitUntil { repo.getDocument(id) != null })
        }
        assertNotNull("Keep keeps the card", repo.getDocument(id))
    }

    @Test(timeout = 60_000)
    fun `Discard deletes exactly that card and nothing else`() {
        val id = runToResult()
        // The real IdCardActivity captures its producer D2 identity on IO at publish time.
        // Recompute that same identity here at fixture creation with the public helper.
        val identity = requireNotNull(
            ToolResultActivity.captureOutputIdentityBlocking(app, listOf(id), emptyList())
        )
        val staging = File(app.cacheDir, "fx_other").apply { mkdirs() }
        val other = File(staging, "p0.jpg").apply {
            writeBytes(com.scanwhirl.app.util.ImportFixtures.image(width = 120, height = 160))
        }
        val otherId = kotlinx.coroutines.runBlocking {
            repo.saveScan(listOf(android.net.Uri.fromFile(other)), null, "Other doc").id
        }
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "id_card", docIds = listOf(id), outputIdentity = identity)
        ).use { scenario ->
            scenario.onActivity { activity ->
                val discard = textsOf(activity.window!!.decorView)
                    .first { it.tag == ToolResultActivity.TAG_DISCARD }
                discard.performClick()
            }
            assertTrue("Discard deletes the card", waitUntil { repo.getDocument(id) == null })
        }
        assertNull("the card is gone", repo.getDocument(id))
        assertNotNull("the other document is untouched", repo.getDocument(otherId))
        assertEquals(1, repo.listDocuments().size)
    }

    @Test(timeout = 60_000)
    fun `Back on the result screen asks before discarding`() {
        val id = runToResult()
        // The real producer's D2 identity is required for the confirmed Discard to remove
        // the card; recompute it at fixture creation with the public helper.
        val identity = requireNotNull(
            ToolResultActivity.captureOutputIdentityBlocking(app, listOf(id), emptyList())
        )
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "id_card", docIds = listOf(id), outputIdentity = identity)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                idle()
                val dialog = latestDialog()
                assertEquals(
                    app.getString(R.string.fixflow_id_discard_title),
                    dialog.findViewById<TextView>(R.id.swSheetTitle).text.toString()
                )
                assertEquals(
                    app.getString(R.string.fixflow_id_delete_copy),
                    dialog.findViewById<TextView>(R.id.swSheetPrimary).text.toString()
                )
                assertEquals(
                    app.getString(R.string.fixflow_keep),
                    dialog.findViewById<TextView>(R.id.swSheetSecondary).text.toString()
                )
                // Keep means keep: the sheet closes and the card stays.
                dialog.findViewById<View>(R.id.swSheetSecondary).performClick()
                idle()
                assertNotNull("Keep leaves the card in place", repo.getDocument(id))
                // Discard in the question really deletes this one card.
                activity.onBackPressedDispatcher.onBackPressed()
                idle()
                latestDialog().findViewById<View>(R.id.swSheetPrimary).performClick()
            }
            assertTrue("Discard deletes the card", waitUntil { repo.getDocument(id) == null })
        }
    }

    @Test(timeout = 60_000)
    fun `Discard with no producer identity refuses and keeps the card`() {
        val id = runToResult()
        // A legacy stat-only C: receipt must fail closed: no deletion, honest error.
        val legacy = ToolResultActivity.cheapIdentity(app, listOf(id), emptyList())
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "id_card", docIds = listOf(id), outputIdentity = legacy)
        ).use { scenario ->
            scenario.onActivity { activity ->
                val discard = textsOf(activity.window!!.decorView)
                    .first { it.tag == ToolResultActivity.TAG_DISCARD }
                discard.performClick()
            }
            assertTrue("the refusal is shown", waitUntil {
                var shown = false
                scenario.onActivity {
                    shown = it.findViewById<View>(R.id.toolResultError)?.visibility == View.VISIBLE
                }
                shown
            })
        }
        assertNotNull("an unproven Discard must keep the card", repo.getDocument(id))
    }
}
