package com.scanwhirl.app.ui

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.tools.PhotoCropFlowActivity
import com.scanwhirl.app.util.ImageTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * fixinsertcrop: "Insert pages > Photos" used to drop the picked gallery photo on the page
 * exactly as it came out of the gallery, so the table around the paper ended up in the document
 * (audit A8). Every picked photo is now auto-cropped and its corners confirmed on the corner
 * crop screen before anything reaches the draft; several photos are confirmed one after another,
 * and Back at any of them inserts nothing from the batch and keeps no picked copy.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class InsertPhotosCropFixInsertCropTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private val tableColour = Color.rgb(27, 42, 107)
    private val whitePage = Color.rgb(235, 235, 235)
    private val greenPage = Color.rgb(180, 220, 180)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    /** A photo of a paper page lying on a dark blue table: the owner's "wooden table" photo. */
    private fun photoOfPageOnTable(pageColour: Int): File {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(tableColour)
            canvas.drawRect(RectF(280f, 220f, 920f, 660f), Paint().apply { color = pageColour })
            val ink = Paint().apply { color = Color.rgb(60, 60, 70) }
            for (i in 1..5) {
                val y = 220f + 440f * (i / 6f)
                canvas.drawRect(320f, y - 4f, 700f, y + 4f, ink)
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            return File(app.cacheDir, "fixinsertcrop_photo_${System.nanoTime()}.jpg").apply {
                writeBytes(out.toByteArray())
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun photosIntent(vararg photos: File): Intent {
        val intent = Intent()
        val clip = ClipData.newUri(app.contentResolver, "photos", Uri.fromFile(photos.first()))
        for (photo in photos.drop(1)) {
            clip.addItem(ClipData.Item(Uri.fromFile(photo)))
        }
        intent.clipData = clip
        return intent
    }

    private fun sheetTitle(): String? =
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
            ?.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()

    private fun clickSheetOption(index: Int) {
        idle()
        val rows = requireNotNull(
            ShadowDialog.getLatestDialog()?.findViewById<LinearLayout>(R.id.swSheetItems)
        ) { "no option sheet is open" }
        requireNotNull(rows.getChildAt(index)) { "the sheet has no option $index" }.performClick()
        idle()
    }

    /** Every TextView text on a screen (the "Photo 2 of 3" header is found by its text). */
    private fun texts(root: View): List<String> {
        val found = mutableListOf<String>()
        fun walk(view: View) {
            if (view is TextView) found += view.text.toString()
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return found
    }

    private fun documentViewer(
        target: String,
        block: (ActivityScenario<DocumentViewerActivity>, DocumentViewerActivity) -> Unit
    ) {
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, target))
            .use { scenario ->
                var viewer: DocumentViewerActivity? = null
                scenario.onActivity { viewer = it }
                assertTrue("the page grid loads", waitUntil {
                    var count = 0
                    scenario.onActivity {
                        count = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pageList)
                            .adapter?.itemCount ?: 0
                    }
                    count > 0
                })
                block(scenario, requireNotNull(viewer))
            }
    }

    /**
     * Insert pages > Photos, deliver [photos] from the picker and choose "At the end"; returns
     * the intent the viewer used to open the corner crop screen.
     */
    private fun openPhotoBatch(viewer: DocumentViewerActivity, vararg photos: File): Intent {
        viewer.startInsertPages()
        idle()
        clickSheetOption(1) // "From photos"
        idle()
        val pick = requireNotNull(shadowOf(viewer).nextStartedActivityForResult) {
            "Photos must open the photo picker"
        }
        shadowOf(viewer).receiveResult(pick.intent, Activity.RESULT_OK, photosIntent(*photos))
        assertTrue(
            "the insert position is asked first",
            waitUntil { sheetTitle() == app.getString(R.string.insert_position_title) }
        )
        clickSheetOption(2) // "At the end"
        var started: Intent? = null
        assertTrue("the corner crop screen opens for the batch", waitUntil {
            started = shadowOf(viewer).nextStartedActivityForResult?.intent
            started != null
        })
        val intent = requireNotNull(started)
        assertEquals(
            "the insert-photo flow is the corner crop screen",
            "com.scanwhirl.app.ui.tools.PhotoCropFlowActivity",
            intent.component?.className
        )
        return intent
    }

    /** Waits for photo [index] on the corner screen, checks the header, and confirms it. */
    private fun confirmPhoto(
        scenario: ActivityScenario<PhotoCropFlowActivity>,
        activity: PhotoCropFlowActivity,
        index: Int,
        total: Int
    ) {
        val header = app.getString(R.string.fixinsertcrop_crop_title, index + 1, total)
        assertTrue(
            "\"$header\" is shown before the confirm",
            waitUntil {
                activity.findViewById<View>(R.id.toolCropView) != null &&
                    texts(activity.window.decorView).contains(header)
            }
        )
        assertEquals("the current photo is the one confirmed", index, activity.currentPhotoIndexForTest())
        scenario.onActivity { it.findViewById<View>(R.id.toolCropApply).performClick() }
    }

    private fun isTableColour(pixel: Int): Boolean =
        Color.blue(pixel) > Color.red(pixel) + 40 && Color.blue(pixel) > Color.green(pixel) + 40

    private fun tablePixels(page: Bitmap): Int {
        var count = 0
        for (x in 0 until page.width step 4) {
            for (y in 0 until page.height step 4) {
                if (isTableColour(page.getPixel(x, y))) count++
            }
        }
        return count
    }

    /** Share of sampled page pixels that are the table colour (the uncropped photo is ~70%). */
    private fun tableFraction(page: Bitmap): Double {
        var total = 0
        var table = 0
        for (x in 0 until page.width step 4) {
            for (y in 0 until page.height step 4) {
                total++
                if (isTableColour(page.getPixel(x, y))) table++
            }
        }
        return if (total == 0) 1.0 else table.toDouble() / total
    }

    private fun averageColour(page: Bitmap): IntArray {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (x in 0 until page.width step 4) {
            for (y in 0 until page.height step 4) {
                val pixel = page.getPixel(x, y)
                r += Color.red(pixel)
                g += Color.green(pixel)
                b += Color.blue(pixel)
                n++
            }
        }
        if (n == 0) return intArrayOf(0, 0, 0)
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** A batch's cache folder; it must not outlive the flow. */
    private fun insertDirs(): List<File> =
        ApplicationProvider.getApplicationContext<Application>().cacheDir
            .listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("insert_") }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun normalisedRect(activity: PhotoCropFlowActivity): FloatArray {
        val view = requireNotNull(activity.findViewById<CropView>(R.id.toolCropView))
        val bytes = requireNotNull(activity.shownBytesForTest())
        val image = ImageTools.decodeSampled(bytes, 2400)
        try {
            val rect = view.getCropRect()
            return floatArrayOf(
                rect.left / image.width, rect.top / image.height,
                rect.right / image.width, rect.bottom / image.height
            )
        } finally {
            image.recycle()
        }
    }

    private fun dragTopLeftCorner(activity: PhotoCropFlowActivity) {
        val crop = requireNotNull(activity.findViewById<CropView>(R.id.toolCropView))
        val width = SwStyle.dp(activity, 300f)
        val height = SwStyle.dp(activity, 300f)
        crop.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        crop.layout(0, 0, width, height)
        val handle = crop.handlePoints()
        val x = handle[0]
        val y = handle[1]
        val dx = SwStyle.dp(activity, 45f).toFloat()
        val dy = SwStyle.dp(activity, 35f).toFloat()
        crop.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        crop.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, x + dx, y + dy, 0))
        crop.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, x + dx, y + dy, 0))
    }

    private fun launchCrop(
        intent: Intent,
        block: (ActivityScenario<PhotoCropFlowActivity>, PhotoCropFlowActivity) -> Unit
    ) {
        ActivityScenario.launch<PhotoCropFlowActivity>(intent).use { scenario ->
            var activity: PhotoCropFlowActivity? = null
            scenario.onActivity { activity = it }
            block(scenario, requireNotNull(activity))
        }
    }

    /** Delivers the crop screen's own result back to the document viewer. */
    private fun deliverCropResult(
        viewer: DocumentViewerActivity,
        cropIntent: Intent,
        cropActivity: PhotoCropFlowActivity
    ) {
        val shadow = shadowOf(cropActivity)
        val code = shadow.resultCode
        val data = shadow.resultIntent
        shadowOf(viewer).receiveResult(cropIntent, code, data)
        idle()
    }

    @Test
    fun `a picked photo opens the corner crop screen instead of landing on the page`() {
        val target = DocFixtures.saved(repo, app, pages = 1)
        val photo = photoOfPageOnTable(whitePage)
        documentViewer(target) { _, viewer ->
            openPhotoBatch(viewer, photo)
            assertNull(
                "nothing is inserted before the corners are confirmed",
                runBlocking { repo.draftOf(target) }
            )
        }
        assertTrue("the batch folder is cleaned up", insertDirs().isEmpty())
    }

    @Test
    fun `a photo of a page on a coloured table is inserted without the table colour`() {
        val target = DocFixtures.saved(repo, app, pages = 1)
        val photo = photoOfPageOnTable(whitePage)
        documentViewer(target) { _, viewer ->
            val cropIntent = openPhotoBatch(viewer, photo)
            var crop: PhotoCropFlowActivity? = null
            launchCrop(cropIntent) { scenario, activity ->
                crop = activity
                confirmPhoto(scenario, activity, 0, 1)
                assertTrue("the batch finishes after the last photo", waitUntil { activity.isFinishing })
                val result = shadowOf(activity).resultIntent
                assertEquals("the confirmed photo is returned", Activity.RESULT_OK, shadowOf(activity).resultCode)
                assertEquals(
                    "one confirmed page comes back",
                    1,
                    result?.getStringArrayListExtra(PhotoCropFlowActivity.EXTRA_CROPPED)?.size ?: 0
                )
            }
            deliverCropResult(viewer, cropIntent, requireNotNull(crop))

            assertTrue(
                "the confirmed photo lands in the draft",
                waitUntil { runBlocking { repo.draftPages(target) }.size == 2 }
            )
        }

        val added = runBlocking { repo.draftPages(target) }.last()
        val page = BitmapFactory.decodeFile(added.path)
        try {
            assertTrue(
                "the table is not printed on the page: ${tableFraction(page)} of the page",
                tableFraction(page) < 0.03
            )
            val average = averageColour(page)
            assertTrue(
                "the page itself is on the paper: ${average.toList()}",
                kotlin.math.abs(average[0] - Color.red(whitePage)) <= 40 &&
                    kotlin.math.abs(average[1] - Color.green(whitePage)) <= 40 &&
                    kotlin.math.abs(average[2] - Color.blue(whitePage)) <= 40
            )
        } finally {
            page.recycle()
        }
        assertTrue("the picked copies are gone after the insert", insertDirs().isEmpty())
    }

    @Test
    fun `Back on the corner screen inserts nothing and keeps the draft untouched`() {
        val target = DocFixtures.saved(repo, app, pages = 2)
        val photo = photoOfPageOnTable(whitePage)
        var crop: PhotoCropFlowActivity? = null
        documentViewer(target) { _, viewer ->
            val cropIntent = openPhotoBatch(viewer, photo)
            launchCrop(cropIntent) { scenario, activity ->
                crop = activity
                assertTrue(
                    "the corner screen shows the photo",
                    waitUntil { activity.findViewById<View>(R.id.toolCropView) != null }
                )
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                assertTrue("Back closes the flow", waitUntil { activity.isFinishing })
                assertEquals(
                    "Back cancels the batch",
                    Activity.RESULT_CANCELED,
                    shadowOf(activity).resultCode
                )
            }
            deliverCropResult(viewer, cropIntent, requireNotNull(crop))
            idle()

            assertTrue(
                "the document still has its two pages",
                waitUntil { repo.getDocument(target)?.pageFiles?.size == 2 }
            )
            assertNull("no draft was created", runBlocking { repo.draftOf(target) })
        }
        assertTrue("Back keeps no picked copy", insertDirs().isEmpty())
    }

    @Test
    fun `two photos are confirmed one after another and land in order`() {
        val target = DocFixtures.saved(repo, app, pages = 1)
        val first = photoOfPageOnTable(whitePage)
        val second = photoOfPageOnTable(greenPage)
        var crop: PhotoCropFlowActivity? = null
        documentViewer(target) { _, viewer ->
            val cropIntent = openPhotoBatch(viewer, first, second)
            launchCrop(cropIntent) { scenario, activity ->
                crop = activity
                confirmPhoto(scenario, activity, 0, 2)
                // The total is known, so the header counts the photos: photo 2 of 2.
                confirmPhoto(scenario, activity, 1, 2)
                assertTrue("the batch finishes", waitUntil { activity.isFinishing })
                val paths = shadowOf(activity).resultIntent
                    ?.getStringArrayListExtra(PhotoCropFlowActivity.EXTRA_CROPPED).orEmpty()
                assertEquals("both confirmed pages come back", 2, paths.size)
                assertTrue(paths[0].endsWith("cropped_000.jpg"))
                assertTrue(paths[1].endsWith("cropped_001.jpg"))
            }
            deliverCropResult(viewer, cropIntent, requireNotNull(crop))
            assertTrue(
                "both pages land in the draft at the end",
                waitUntil { runBlocking { repo.draftPages(target) }.size == 3 }
            )
        }

        val pages = runBlocking { repo.draftPages(target) }
        fun averageOf(file: File): IntArray {
            val bitmap = BitmapFactory.decodeFile(file.path)
            try {
                return averageColour(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
        val one = averageOf(pages[1])
        val two = averageOf(pages[2])
        assertTrue(
            "the first confirmed photo is the first inserted page: ${one.toList()} vs ${two.toList()}",
            one[0] > two[0] + 20
        )
        for (page in pages.drop(1)) {
            val bitmap = BitmapFactory.decodeFile(page.path)
            try {
                assertTrue(
                    "no table colour on ${page.name}: ${tableFraction(bitmap)}",
                    tableFraction(bitmap) < 0.03
                )
            } finally {
                bitmap.recycle()
            }
        }
        assertTrue("the picked copies are gone after the insert", insertDirs().isEmpty())
    }

    @Test
    fun `rotation keeps the current photo and the adjusted corners`() {
        val target = DocFixtures.saved(repo, app, pages = 1)
        val first = photoOfPageOnTable(whitePage)
        val second = photoOfPageOnTable(greenPage)
        documentViewer(target) { _, viewer ->
            val cropIntent = openPhotoBatch(viewer, first, second)
            launchCrop(cropIntent) { scenario, activity ->
                assertTrue(
                    "the corner screen shows the first photo",
                    waitUntil { activity.findViewById<View>(R.id.toolCropView) != null }
                )
                dragTopLeftCorner(activity)
                val beforeRect = normalisedRect(activity)
                val beforePhoto = sha256(requireNotNull(activity.shownBytesForTest()))
                assertTrue(
                    "the frame really moved",
                    beforeRect[0] > 0.05f || beforeRect[1] > 0.05f
                )

                scenario.recreate()
                var current = activity
                scenario.onActivity { current = it }
                assertTrue(
                    "the same photo comes back after rotation",
                    waitUntil {
                        current.findViewById<View>(R.id.toolCropView) != null &&
                            current.currentPhotoIndexForTest() == 0
                    }
                )
                assertEquals(
                    "rotation stays on the same photo",
                    0,
                    current.currentPhotoIndexForTest()
                )
                assertTrue(
                    "the header still counts photo 1 of 2",
                    texts(current.window.decorView)
                        .contains(app.getString(R.string.fixinsertcrop_crop_title, 1, 2))
                )
                assertEquals(
                    "the same prepared photo is shown",
                    beforePhoto,
                    sha256(requireNotNull(current.shownBytesForTest()))
                )
                val afterRect = normalisedRect(current)
                for (i in 0 until 4) {
                    assertEquals(
                        "adjusted corner $i survives rotation",
                        beforeRect[i],
                        afterRect[i],
                        0.01f
                    )
                }

                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                assertTrue("Back finishes the flow", waitUntil { current.isFinishing })
            }
        }
        assertTrue("a cancelled rotation test keeps no picked copy", insertDirs().isEmpty())
    }
}
