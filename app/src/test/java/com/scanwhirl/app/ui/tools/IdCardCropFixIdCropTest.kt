package com.scanwhirl.app.ui.tools
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.CropView
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.SwActivitySession
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
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private val cardColour = Color.rgb(232, 232, 232)
    private val tableColour = Color.rgb(27, 42, 107)

    private var activity: IdCardActivity? = null

    @Before
    fun setUp() {
        app.getSharedPreferences("scanwhirl_id_card", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Gates and dialogs are process-wide. A test that fails mid-flight must not leak them
        // into the next test in the same fork (the full suite shares one JVM across classes).
        IdCardActivity.cropReopenGate = null
        Faults.resetAll()
        ShadowDialog.reset()
        File(app.cacheDir, "id_card_state").deleteRecursively()
    }

    @After
    fun tearDown() {
        Faults.resetAll()
        IdCardActivity.cropReopenGate = null
        ShadowDialog.reset()
        File(app.cacheDir, "id_card_state").deleteRecursively()
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

    private fun launch(): SwActivitySession<IdCardActivity> =
        SwActivitySession.launch(IdCardActivity::class.java).also { scenario ->
            scenario.onActivity { activity = it }
            idle()
        }

    private fun sheetTitle(): String? =
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
            ?.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()

    /** Clicks "Pick from gallery" on the open sheet and delivers a photo from the picker. */
    private fun pickPhoto(scenario: SwActivitySession<IdCardActivity>, photo: File) =
        pickPhotoUri(scenario, Uri.fromFile(photo))

    private fun pickPhotoUri(scenario: SwActivitySession<IdCardActivity>, uri: Uri) {
        scenario.onActivity { current ->
            idle()
            val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "no sheet is open" }
            dialog.findViewById<View>(R.id.swSheetSecondary).performClick()
            idle()
            val request = requireNotNull(shadowOf(current).nextStartedActivityForResult) {
                "Pick from gallery must open the photo picker"
            }
            shadowOf(current).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
            idle()
        }
    }

    /** A provider whose photo access is revoked after the first read (R2-2). */
    class DeniedPhotoProvider : android.content.ContentProvider() {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
        @Volatile
        var deny = false
        var photo: File? = null

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "image/jpeg"
        override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
            if (deny) throw SecurityException("photo access was revoked")
            return android.os.ParcelFileDescriptor.open(
                requireNotNull(photo), android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
        }
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?
        ): android.database.Cursor? = null
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = error("read-only")
        override fun update(
            uri: Uri, values: android.content.ContentValues?, selection: String?,
            selectionArgs: Array<out String>?
        ): Int = error("read-only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            error("read-only")
    }

    private fun registerDeniedProvider(photo: File): DeniedPhotoProvider {
        val provider = DeniedPhotoProvider().apply { this.photo = photo }
        provider.attachInfo(
            app,
            android.content.pm.ProviderInfo().apply { authority = "fixidcrop.photos" }
        )
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("fixidcrop.photos", provider)
        return provider
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

    private fun stateDir(): File = File(app.cacheDir, "id_card_state")

    private fun stateFiles(): List<String> =
        stateDir().listFiles()?.map { it.name }?.sorted() ?: emptyList()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The shown frame as fractions of the shown image, so any preview size compares equal. */
    private fun normalisedCropRect(current: IdCardActivity): FloatArray {
        val view = requireNotNull(current.findViewById<CropView>(R.id.toolCropView))
        val bytes = requireNotNull(current.cropScreenBytesForTest())
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

    /** Drags the top-left handle in: the frame is no longer the full image. */
    private fun dragTopLeftCorner(current: IdCardActivity) {
        val crop = requireNotNull(current.findViewById<CropView>(R.id.toolCropView))
        val width = SwStyle.dp(current, 300f)
        val height = SwStyle.dp(current, 300f)
        crop.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        crop.layout(0, 0, width, height)
        val handle = crop.handlePoints()
        val x = handle[0]
        val y = handle[1]
        val dx = SwStyle.dp(current, 45f).toFloat()
        val dy = SwStyle.dp(current, 35f).toFloat()
        crop.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        crop.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, x + dx, y + dy, 0))
        crop.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, x + dx, y + dy, 0))
    }

    @Test(timeout = 30_000)
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

    @Test(timeout = 30_000)
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

    @Test(timeout = 30_000)
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

    @Test(timeout = 30_000)
    fun `the crop screen's own Back button returns to the front sheet`() {
        val photo = photoOfCardOnTable()
        launch().use { scenario ->
            pickPhoto(scenario, photo)
            assertTrue(waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null })
            // A recreation first: the cached photo must go when Cancel closes the restored screen.
            scenario.recreate()
            scenario.onActivity { activity = it }
            idle()
            assertTrue(waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null })
            assertTrue("the restored photo is cached", stateFiles().contains("crop.jpg"))

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
            assertTrue("cancel removes the cached photo", stateFiles().isEmpty())
        }
        assertTrue("a cancelled crop saves nothing", repo.listDocuments().isEmpty())
    }

    // ---- rework: a confirm that is abandoned must not save (critic D2) ----

    @Test(timeout = 30_000)
    fun `Back while the crop is saving leaves the side empty and shows no error`() {
        val photo = photoOfCardOnTable()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val encoded = CountDownLatch(1)
        var encodedBytes: ByteArray? = null
        var settled: Boolean? = null
        try {
            launch().use { scenario ->
                pickPhoto(scenario, photo)
                assertTrue(waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null })

                scenario.onActivity { current ->
                    // The encode is held in flight by a test-only gate, so Back is guaranteed to
                    // arrive while the save is still running instead of racing the main-thread
                    // accept/abandon decision.
                    current.cropEncodeGate = {
                        held.countDown()
                        release.await(15, TimeUnit.SECONDS)
                    }
                    // Tests: the worker finished AND the main-thread accept/abandon decision ran.
                    current.cropEncodeFinishedForTest = { bytes ->
                        encodedBytes = bytes
                        encoded.countDown()
                    }
                    current.cropSaveSettledForTest = { accepted -> settled = accepted }
                    // Crop and save starts the JPEG encode, then Back arrives before it finishes.
                    current.findViewById<View>(R.id.toolCropApply).performClick()
                }
                assertTrue("the save really is encoding", held.await(15, TimeUnit.SECONDS))
                assertNull("nothing is decided while the save is in flight", settled)

                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                idle()
                assertNull("Back does not decide the in-flight save", settled)
                assertTrue(
                    "the crop screen closes",
                    waitUntil { activity?.findViewById<View>(R.id.toolCropView) == null }
                )

                // Only now does the abandoned save finish encoding.
                release.countDown()
                assertTrue(
                    "the abandoned encode really finished",
                    encoded.await(15, TimeUnit.SECONDS)
                )
                assertNotNull("the encode still produced bytes (no recycled bitmap)", encodedBytes)
                // The abandoned continuation really reached its token decision on the main thread.
                assertTrue("the main-thread decision ran", waitUntil { settled != null })
                assertEquals("the abandoned save is not accepted", false, settled)
                idle()
                scenario.onActivity { current ->
                    assertEquals(
                        "Back returns to the front sheet, not to the next step",
                        app.getString(R.string.tools_id_front_title),
                        sheetTitle()
                    )
                    val error = requireNotNull(current.findViewById<TextView>(R.id.toolIdCardError))
                    assertFalse("no error after Back", error.isVisible)
                    assertFalse("the flow is still open", current.isFinishing)
                    // The next attempt is not blocked by the abandoned save.
                    assertNotNull(
                        "the side sheet is usable again",
                        current.findViewById<View>(R.id.toolIdCardFront)
                    )
                }
            }
        } finally {
            // Never leave the worker thread blocked if an assertion failed before the release.
            release.countDown()
        }
        assertTrue("nothing was accepted or saved", repo.listDocuments().isEmpty())
        assertTrue("no cached photo is left", stateFiles().isEmpty())
    }

    // ---- rework: rotation mid-crop keeps the photo AND the adjusted frame (D5) ----

    @Test(timeout = 30_000)
    fun `rotation keeps the front photo and the adjusted crop frame`() {
        val photo = photoOfCardOnTable()
        launch().use { scenario ->
            pickPhoto(scenario, photo)
            assertTrue(waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null })

            var hashBefore = ""
            var rectBefore = FloatArray(4)
            scenario.onActivity { current ->
                dragTopLeftCorner(current)
                rectBefore = normalisedCropRect(current)
                hashBefore = sha256(requireNotNull(current.cropScreenBytesForTest()))
            }
            assertTrue(
                "the person moved the frame off the full image",
                rectBefore[0] > 0.01f && rectBefore[1] > 0.01f
            )

            scenario.recreate()
            scenario.onActivity { activity = it }
            idle()

            assertTrue(
                "the crop screen is still open after recreation",
                waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null }
            )
            scenario.onActivity { current ->
                assertEquals(
                    "the same prepared photo is back",
                    hashBefore,
                    sha256(requireNotNull(current.cropScreenBytesForTest()))
                )
                val rectAfter = normalisedCropRect(current)
                for (i in 0..3) {
                    assertEquals("adjusted crop rect part $i", rectBefore[i], rectAfter[i], 0.003f)
                }
                assertNotNull(
                    "the card hint is back",
                    findTextIn(
                        current.window!!.decorView,
                        app.getString(R.string.fixidcrop_id_crop_hint)
                    )
                )
                assertTrue("the pending photo survives for a further recreation", stateFiles().contains("crop.jpg"))
            }

            scenario.onActivity { it.findViewById<View>(R.id.toolCropApply).performClick() }
            assertTrue(
                "the kept crop is accepted as the front side",
                waitUntil { sheetTitle() == app.getString(R.string.tools_id_back_title) }
            )
            assertTrue("accepting removes the cached photo", stateFiles().none { it == "crop.jpg" })
        }
        assertTrue("nothing was saved before the layout", repo.listDocuments().isEmpty())
    }

    @Test(timeout = 30_000)
    fun `rotation keeps the back photo and the adjusted crop frame`() {
        val photo = photoOfCardOnTable()
        launch().use { scenario ->
            scenario.onActivity { it.acceptCapture(IdCardActivity.Side.FRONT, jpeg(Color.RED)) }
            assertTrue(
                "the back step is asking for the back side",
                waitUntil { sheetTitle() == app.getString(R.string.tools_id_back_title) }
            )
            pickPhoto(scenario, photo)
            assertTrue(waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null })

            var rectBefore = FloatArray(4)
            var hashBefore = ""
            scenario.onActivity { current ->
                dragTopLeftCorner(current)
                rectBefore = normalisedCropRect(current)
                hashBefore = sha256(requireNotNull(current.cropScreenBytesForTest()))
            }
            assertTrue("the back frame was adjusted", rectBefore[0] > 0.01f && rectBefore[1] > 0.01f)

            scenario.recreate()
            scenario.onActivity { activity = it }
            idle()
            assertTrue(
                "the back crop screen is still open after recreation",
                waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null }
            )
            scenario.onActivity { current ->
                assertEquals(
                    "the same prepared back photo is back",
                    hashBefore,
                    sha256(requireNotNull(current.cropScreenBytesForTest()))
                )
                val rectAfter = normalisedCropRect(current)
                for (i in 0..3) {
                    assertEquals("back crop rect part $i", rectBefore[i], rectAfter[i], 0.003f)
                }
            }

            scenario.onActivity { it.findViewById<View>(R.id.toolCropApply).performClick() }
            assertTrue(
                "accepting the back side reaches Layout",
                waitUntil { sheetTitle() == app.getString(R.string.tools_id_layout_title) }
            )
        }
        assertTrue("nothing was saved before the layout", repo.listDocuments().isEmpty())
    }

    @Test(timeout = 30_000)
    fun `recreation while the photo is being prepared still opens the crop screen`() {
        val photo = photoOfCardOnTable()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        launch().use { scenario ->
            scenario.onActivity { current ->
                current.cropPrepareGate = {
                    started.countDown()
                    release.await(15, TimeUnit.SECONDS)
                }
            }
            pickPhoto(scenario, photo)
            assertTrue("preparation started", waitUntil { started.count == 0L })

            scenario.recreate()
            scenario.onActivity { activity = it }
            release.countDown()
            idle()

            assertTrue(
                "the restored screen prepares the same photo again",
                waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null }
            )
            scenario.onActivity { it.findViewById<View>(R.id.toolCropApply).performClick() }
            assertTrue(
                "the prepared photo is accepted as the front side",
                waitUntil { sheetTitle() == app.getString(R.string.tools_id_back_title) }
            )
        }
        assertTrue("nothing was saved before the layout", repo.listDocuments().isEmpty())
    }

    // ---- rework 2: no picked photo stays behind (judge N1) ----

    @Test(timeout = 30_000)
    fun `Back before the reopened crop screen appears returns to the sheet and removes the photo`() {
        val photo = photoOfCardOnTable()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        IdCardActivity.cropReopenGate = {
            started.countDown()
            release.await(15, TimeUnit.SECONDS)
        }
        try {
            launch().use { scenario ->
                pickPhoto(scenario, photo)
                assertTrue(waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null })

                scenario.recreate()
                scenario.onActivity { activity = it }
                assertTrue("the reopen decode is held", waitUntil { started.count == 0L })
                assertNull(
                    "the crop screen is definitely not installed yet",
                    activity!!.findViewById<View>(R.id.toolCropView)
                )
                assertTrue("the cached photo waits for the reopen", stateFiles().contains("crop.jpg"))

                // Back while only the pending photo exists: this must be handled by the crop
                // screen's own Back path, not by finishing the activity.
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                idle()

                scenario.onActivity { current ->
                    assertNull(
                        "the crop screen never appears",
                        current.findViewById<View>(R.id.toolCropView)
                    )
                    assertEquals(
                        "Back returns to the front sheet",
                        app.getString(R.string.tools_id_front_title),
                        sheetTitle()
                    )
                    assertFalse("the flow did not leave the ID card screen", current.isFinishing)
                    assertFalse(
                        "no progress is stuck",
                        current.findViewById<View>(R.id.toolSetupProgress).isVisible
                    )
                }
                assertTrue("no cached photo is left", stateFiles().isEmpty())
            }
        } finally {
            release.countDown()
            IdCardActivity.cropReopenGate = null
        }
        assertTrue("nothing was saved", repo.listDocuments().isEmpty())
    }

    // ---- rework 3: a second recreation during the reopen keeps the frame (judge R2-1) ----

    @Test(timeout = 30_000)
    fun `a second recreation during the reopen keeps the adjusted frame`() {
        val photo = photoOfCardOnTable()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reopenCalls = java.util.concurrent.atomic.AtomicInteger(0)
        IdCardActivity.cropReopenGate = {
            // Only the first reopen decode is held; the second instance must finish normally.
            if (reopenCalls.incrementAndGet() == 1) {
                started.countDown()
                release.await(15, TimeUnit.SECONDS)
            }
        }
        try {
            launch().use { scenario ->
                pickPhoto(scenario, photo)
                assertTrue(waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null })
                var rectBefore = FloatArray(4)
                scenario.onActivity { current ->
                    dragTopLeftCorner(current)
                    rectBefore = normalisedCropRect(current)
                }
                assertTrue("the person adjusted the frame", rectBefore[0] > 0.01f && rectBefore[1] > 0.01f)

                scenario.recreate()
                scenario.onActivity { activity = it }
                assertTrue("the first reopen decode is held", waitUntil { started.count == 0L })
                assertNull(
                    "the first reopened screen has not been published",
                    activity!!.findViewById<View>(R.id.toolCropView)
                )

                // R2-1: save/recreate again while the reopen decode is still incomplete.
                scenario.recreate()
                scenario.onActivity { activity = it }
                assertTrue(
                    "the reopened crop screen appears",
                    waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null }
                )
                scenario.onActivity { current ->
                    val rectAfter = normalisedCropRect(current)
                    for (i in 0..3) {
                        assertEquals(
                            "adjusted frame part $i after two recreations",
                            rectBefore[i], rectAfter[i], 0.003f
                        )
                    }
                }
            }
        } finally {
            release.countDown()
            IdCardActivity.cropReopenGate = null
        }
    }

    // ---- rework 3: a restored pick that cannot be read says so (judge R2-2) ----

    @Test(timeout = 30_000)
    fun `a restored pick that can no longer be read says so in the side sheet`() {
        val photo = photoOfCardOnTable()
        val provider = registerDeniedProvider(photo)
        val uri = Uri.parse("content://fixidcrop.photos/card.jpg")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        launch().use { scenario ->
            scenario.onActivity { current ->
                current.cropPrepareGate = {
                    started.countDown()
                    release.await(15, TimeUnit.SECONDS)
                }
            }
            pickPhotoUri(scenario, uri)
            assertTrue("the first read finished and is held", waitUntil { started.count == 0L })
            provider.deny = true

            scenario.recreate()
            scenario.onActivity { activity = it }
            release.countDown()
            assertTrue(
                "the side sheet comes back",
                waitUntil { sheetTitle() == app.getString(R.string.tools_id_front_title) }
            )
            idle()
            scenario.onActivity { current ->
                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                assertEquals(
                    "the recovery message is part of the active sheet",
                    app.getString(R.string.fixidcrop_id_photo_gone),
                    dialog.findViewById<TextView>(R.id.swSheetMessage).text.toString()
                )
                assertEquals(
                    "the normal scan retry is offered",
                    app.getString(R.string.tools_id_scan_camera),
                    dialog.findViewById<TextView>(R.id.swSheetPrimary).text.toString()
                )
                assertEquals(
                    "the normal pick retry is offered",
                    app.getString(R.string.tools_id_pick_gallery),
                    dialog.findViewById<TextView>(R.id.swSheetSecondary).text.toString()
                )
                assertNull("no crop screen", current.findViewById<View>(R.id.toolCropView))
                assertFalse(
                    "no progress is stuck",
                    current.findViewById<View>(R.id.toolSetupProgress).isVisible
                )
            }
            // The denied read's cleanup runs after the sheet is shown; wait for it instead of
            // sampling the state directory immediately (a load-time race in the full suite).
            assertTrue("no photo is cached", waitUntil { stateFiles().none { it == "crop.jpg" } })
        }
        assertTrue("no side was accepted", repo.listDocuments().isEmpty())
    }

    @Test(timeout = 30_000)
    fun `finishing while the crop is open removes the cached photo`() {
        val photo = photoOfCardOnTable()
        launch().use { scenario ->
            pickPhoto(scenario, photo)
            assertTrue(waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null })
            scenario.recreate()
            scenario.onActivity { activity = it }
            idle()
            assertTrue(
                "the crop screen is open again",
                waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null }
            )
            assertTrue("the pending photo is cached", stateFiles().contains("crop.jpg"))

            scenario.onActivity { it.finish() }
            idle()
        }
        assertTrue("finishing removes every cached file", stateFiles().isEmpty())
    }

    @Test(timeout = 30_000)
    fun `finishing an abandoned flow removes the cached front side too`() {
        launch().use { scenario ->
            scenario.onActivity { it.acceptCapture(IdCardActivity.Side.FRONT, jpeg(Color.RED)) }
            assertTrue(
                "the back step is asking for the back side",
                waitUntil { sheetTitle() == app.getString(R.string.tools_id_back_title) }
            )
            scenario.recreate()
            scenario.onActivity { activity = it }
            idle()
            assertTrue(
                "the captured front is cached for the restored screen",
                stateFiles().contains("front.jpg")
            )

            scenario.onActivity { it.finish() }
            idle()
        }
        assertTrue("finishing removes the front and back copies", stateFiles().isEmpty())
    }

    // ---- rework: a photo with no card edge says so (critic D10) ----

    @Test(timeout = 30_000)
    fun `a photo without a card edge shows the drag the corners hint`() {
        val plain = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        val photo = try {
            Canvas(plain).drawColor(Color.rgb(120, 120, 120))
            val out = ByteArrayOutputStream()
            plain.compress(Bitmap.CompressFormat.JPEG, 92, out)
            File(app.cacheDir, "fixidcrop_plain_${System.nanoTime()}.jpg").apply {
                writeBytes(out.toByteArray())
            }
        } finally {
            plain.recycle()
        }

        launch().use { scenario ->
            pickPhoto(scenario, photo)
            assertTrue(waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null })
            scenario.onActivity { current ->
                assertNotNull(
                    "the fallback hint is shown",
                    findTextIn(
                        current.window!!.decorView,
                        app.getString(R.string.fixidcrop_id_crop_hint_none)
                    )
                )
                assertNull(
                    "the confident hint is not shown",
                    findTextIn(
                        current.window!!.decorView,
                        app.getString(R.string.fixidcrop_id_crop_hint)
                    )
                )
            }
        }
        assertTrue("nothing is saved yet", repo.listDocuments().isEmpty())
    }

    // ---- rework: touch target and the screen behind (critic D9) ----

    @Test(timeout = 30_000)
    fun `the crop screen's Back target is 48 dp and the screen behind is hidden`() {
        val photo = photoOfCardOnTable()
        val density = app.resources.displayMetrics.density
        launch().use { scenario ->
            pickPhoto(scenario, photo)
            assertTrue(waitUntil { activity?.findViewById<View>(R.id.toolCropView) != null })
            scenario.onActivity { current ->
                val crop = requireNotNull(current.findViewById<View>(R.id.toolCropView))
                val overlay = crop.parent.parent as ViewGroup
                val back = requireNotNull(findBackButton(overlay))
                assertTrue(
                    "the crop Back is at least 48 dp: ${back.layoutParams.width}x${back.layoutParams.height}",
                    back.layoutParams.width >= (48 * density).toInt() &&
                        back.layoutParams.height >= (48 * density).toInt()
                )
                assertEquals(
                    "the card screen behind the crop is out of the accessibility tree",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    current.contentAccessibilityForTest()
                )
            }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            idle()
            scenario.onActivity { current ->
                assertEquals(
                    "the card screen is reachable again once the crop closes",
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
                    current.contentAccessibilityForTest()
                )
            }
        }
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
