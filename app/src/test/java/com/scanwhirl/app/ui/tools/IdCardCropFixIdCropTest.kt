package com.scanwhirl.app.ui.tools

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.CropView
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

/**
 * fixidcrop: "Pick from gallery" used to put the whole photo (table and all) on the A4 page.
 * A picked card photo now runs the app's existing automatic crop and opens the Passport-style
 * corner crop screen, preset to the detected card; only the confirmed crop is composed onto the
 * page, and Back on the crop screen returns to the side sheet with nothing saved.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class IdCardCropFixIdCropTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private val cardColour = Color.rgb(232, 232, 232)
    private val tableColour = Color.rgb(27, 42, 107)

    private var activity: IdCardActivity? = null

    @Before
    fun setUp() {
        app.getSharedPreferences("scanwhirl_id_card", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        Faults.resetAll()
        activity = null
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

    /** A card on a dark blue table: the photo the owner picked from the gallery. */
    private fun photoOfCardOnTable(): File {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(tableColour)
            canvas.drawRect(RectF(300f, 250f, 900f, 630f), Paint().apply { color = cardColour })
            val ink = Paint().apply { color = Color.rgb(60, 60, 70) }
            for (i in 1..4) {
                val y = 250f + 380f * (i / 5f)
                canvas.drawRect(336f, y - 4f, 660f, y + 4f, ink)
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            return File(app.cacheDir, "fixidcrop_card_${System.nanoTime()}.jpg").apply {
                writeBytes(out.toByteArray())
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun launch(): ActivityScenario<IdCardActivity> =
        ActivityScenario.launch(IdCardActivity::class.java).also { scenario ->
            scenario.onActivity { activity = it }
            idle()
        }

    private fun sheetTitle(): String? =
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
            ?.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()

    /** Clicks "Pick from gallery" on the open sheet and delivers a photo from the picker. */
    private fun pickPhoto(scenario: ActivityScenario<IdCardActivity>, photo: File) {
        scenario.onActivity { current ->
            idle()
            val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "no sheet is open" }
            dialog.findViewById<View>(R.id.swSheetSecondary).performClick()
            idle()
            val request = requireNotNull(shadowOf(current).nextStartedActivityForResult) {
                "Pick from gallery must open the photo picker"
            }
            shadowOf(current).receiveResult(
                request.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(photo))
            )
            idle()
        }
    }

    private fun clickSheetText(textRes: Int) {
        idle()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "no sheet is open" }
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

    private fun clickSheet(id: Int) {
        idle()
        requireNotNull(ShadowDialog.getLatestDialog()?.findViewById<View>(id)).performClick()
        idle()
    }

    private fun isTableColour(pixel: Int): Boolean =
        Color.blue(pixel) > Color.red(pixel) + 40 && Color.blue(pixel) > Color.green(pixel) + 40

    @Test
    fun `a picked photo opens the corner crop screen preset to the auto cropped card`() {
        val photo = photoOfCardOnTable()
        launch().use { scenario ->
            pickPhoto(scenario, photo)

            assertTrue(
                "the crop screen opens after the pick",
                waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null }
            )
            idle()
            scenario.onActivity { current ->
                val crop = requireNotNull(current.findViewById<CropView>(R.id.toolCropView))
                val bytes = requireNotNull(current.cropScreenBytesForTest()) {
                    "the crop screen shows the auto-cropped photo"
                }
                val shown = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                try {
                    assertTrue(
                        "the table around the card was cut off (${shown.width}x${shown.height})",
                        shown.width < 900 && shown.height < 700
                    )
                    for (fx in floatArrayOf(0.1f, 0.5f, 0.9f)) {
                        for (fy in floatArrayOf(0.1f, 0.5f, 0.9f)) {
                            val pixel = shown.getPixel(
                                (shown.width * fx).toInt().coerceIn(0, shown.width - 1),
                                (shown.height * fy).toInt().coerceIn(0, shown.height - 1)
                            )
                            if (fx != 0.5f || fy != 0.5f) {
                                assertFalse(
                                    "the crop screen shows the card at $fx,$fy, not the table",
                                    isTableColour(pixel)
                                )
                            }
                        }
                    }
                    // Preset to the detected card: the frame covers the whole auto-cropped image.
                    val rect = crop.getCropRect()
                    assertEquals(0f, rect.left, 2f)
                    assertEquals(0f, rect.top, 2f)
                    assertEquals(shown.width.toFloat(), rect.right, 2f)
                    assertEquals(shown.height.toFloat(), rect.bottom, 2f)
                } finally {
                    shown.recycle()
                }
                assertEquals(
                    "the hint names the card corners",
                    app.getString(R.string.fixidcrop_id_crop_hint),
                    findTextIn(current.window!!.decorView, app.getString(R.string.fixidcrop_id_crop_hint))
                )
                assertNotNull(
                    "the confirm action is the Passport crop screen's Crop and save",
                    current.findViewById<View>(R.id.toolCropApply)
                )
            }
            assertFalse(
                "the next step waits until the crop is confirmed",
                sheetTitle() == app.getString(R.string.tools_id_back_title)
            )
        }
        assertTrue("nothing is saved before confirming", repo.listDocuments().isEmpty())
    }

    @Test
    fun `only the confirmed crop is printed on the page`() {
        val photo = photoOfCardOnTable()
        launch().use { scenario ->
            pickPhoto(scenario, photo)
            assertTrue(waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null })

            scenario.onActivity { current ->
                current.findViewById<View>(R.id.toolCropApply).performClick()
            }
            assertTrue(
                "confirming the crop opens the back step",
                waitUntil { sheetTitle() == app.getString(R.string.tools_id_back_title) }
            )
            scenario.onActivity {
                clickSheetText(R.string.tools_id_front_only)
                clickSheet(R.id.swSheetPrimary)
            }
            assertTrue("the card is created", waitUntil { repo.listDocuments().isNotEmpty() })
        }

        val doc = repo.listDocuments().single()
        val page = BitmapFactory.decodeFile(doc.pageFiles[0].path)
        try {
            var blue = 0
            // The table must not be on the paper: scan inside the card area in a grid.
            for (x in 0 until page.width step 4) {
                for (y in 0 until page.height step 4) {
                    if (isTableColour(page.getPixel(x, y))) blue++
                }
            }
            assertEquals("the table/background is not printed on the page", 0, blue)
            val centre = page.getPixel(page.width / 2, page.height / 2)
            assertTrue(
                "the card itself is on the page: ${Integer.toHexString(centre)}",
                kotlin.math.abs(Color.red(centre) - Color.red(cardColour)) <= 40 &&
                    kotlin.math.abs(Color.green(centre) - Color.green(cardColour)) <= 40 &&
                    kotlin.math.abs(Color.blue(centre) - Color.blue(cardColour)) <= 40
            )
        } finally {
            page.recycle()
        }
    }

    @Test
    fun `Back on the crop screen returns to the side sheet and saves nothing`() {
        val photo = photoOfCardOnTable()
        launch().use { scenario ->
            scenario.onActivity { it.acceptCapture(IdCardActivity.Side.FRONT, jpeg(Color.RED)) }
            assertTrue(
                "the back step is asking for the back side",
                waitUntil { sheetTitle() == app.getString(R.string.tools_id_back_title) }
            )

            pickPhoto(scenario, photo)
            assertTrue(
                "the back photo opens the crop screen",
                waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null }
            )

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            idle()

            assertTrue(
                "the crop screen closes",
                waitUntil { activity?.findViewById<View>(R.id.toolCropView) == null }
            )
            idle()
            scenario.onActivity { current ->
                assertFalse("the flow did not leave the ID card screen", current.isFinishing)
                assertEquals(
                    "Back returns to the side sheet it came from",
                    app.getString(R.string.tools_id_back_title),
                    sheetTitle()
                )
                assertNull(
                    "the back slot keeps waiting for a photo",
                    current.findViewById<View>(R.id.toolCropView)
                )
            }
        }
        assertTrue("a cancelled crop saves nothing", repo.listDocuments().isEmpty())
    }

    @Test
    fun `the crop screen's own Back button returns to the front sheet`() {
        val photo = photoOfCardOnTable()
        launch().use { scenario ->
            pickPhoto(scenario, photo)
            assertTrue(waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null })

            scenario.onActivity { current ->
                val crop = requireNotNull(current.findViewById<View>(R.id.toolCropView))
                val overlay = crop.parent.parent as ViewGroup
                val back = findBackButton(overlay)
                requireNotNull(back) { "the crop screen has its own Back button" }
                back.performClick()
            }
            idle()

            assertTrue(
                "the crop screen closes",
                waitUntil { activity?.findViewById<View>(R.id.toolCropView) == null }
            )
            assertEquals(
                "cancel returns to the front side sheet",
                app.getString(R.string.tools_id_front_title),
                sheetTitle()
            )
        }
        assertTrue("a cancelled crop saves nothing", repo.listDocuments().isEmpty())
    }

    private fun findTextIn(root: View, wanted: String): String? {
        if (root is TextView && root.text.toString() == wanted) return root.text.toString()
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findTextIn(root.getChildAt(i), wanted)?.let { return it }
            }
        }
        return null
    }

    private fun findBackButton(root: View): View? {
        if (root is android.widget.ImageButton) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findBackButton(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
