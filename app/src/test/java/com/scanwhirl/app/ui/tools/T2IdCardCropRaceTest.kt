package com.scanwhirl.app.ui.tools

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T2: IdCardCropFixIdCropTest failed "no photo is cached" only when the whole suite ran. The
 * cause is a stale preparation coroutine: T1 moved the crop cache write into the IO block, but the
 * write still ran after a recreation had superseded that generation, orphaning crop.jpg. These
 * tests reproduce that deterministically and check it stays fixed across repeated runs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class T2IdCardCropRaceTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.rgb(232, 232, 232))
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    class DeniedProvider : android.content.ContentProvider() {
        @Volatile
        var deny = false
        var photo: File? = null

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "image/jpeg"
        override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
            if (deny) throw SecurityException("access revoked")
            return android.os.ParcelFileDescriptor.open(
                requireNotNull(photo), android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
        }
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?
        ): android.database.Cursor? = null
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri = error("read-only")
        override fun update(
            uri: Uri, values: android.content.ContentValues?, selection: String?,
            selectionArgs: Array<out String>?
        ): Int = error("read-only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            error("read-only")
    }

    private fun provider(photo: File, authority: String): DeniedProvider {
        val p = DeniedProvider().apply { this.photo = photo }
        p.attachInfo(app, android.content.pm.ProviderInfo().apply { this.authority = authority })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, p)
        return p
    }

    /** One full recreation-during-preparation race, including the denied restore read. */
    private fun runRaceOnce(label: String, index: Int = 0) {
        org.robolectric.shadows.ShadowDialog.reset()
        val authority = "t2.photos.$index"
        val photo = File(app.cacheDir, "t2_card_${System.nanoTime()}.jpg").apply { writeBytes(jpeg()) }
        val denied = provider(photo, authority)
        val uri = Uri.parse("content://$authority/card.jpg")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { current ->
                current.cropPrepareGate = {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
            scenario.onActivity { current ->
                idle()
                val sheet = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
                sheet.findViewById<View>(com.scanwhirl.app.R.id.swSheetSecondary).performClick()
                idle()
                val request = requireNotNull(shadowOf(current).nextStartedActivityForResult)
                shadowOf(current).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
                idle()
            }
            // Pump the main looper while waiting: blocking it here would starve the
            // Dispatchers.Main continuation that calls beginCrop (and then the gate).
            assertTrue("$label: preparation started", waitUntil(10_000) { started.count == 0L })
            denied.deny = true
            scenario.recreate()
            idle()
            release.countDown()
            // The stale coroutine resumes after release; give it time to (wrongly) write the file.
            Thread.sleep(200)
            idle()
            val state = File(app.cacheDir, "id_card_state")
            assertFalse(
                "$label: a superseded preparation orphaned crop.jpg",
                state.resolve("crop.jpg").exists()
            )
        }
    }

    @After
    fun tearDown() {
        org.robolectric.shadows.ShadowDialog.reset()
        File(app.cacheDir, "id_card_state").deleteRecursively()
    }

    @Test
    fun `a superseded preparation never leaves a cached crop photo`() = runRaceOnce("single")

    @Test
    fun `five consecutive races never leave a cached crop photo`() {
        repeat(5) { index -> runRaceOnce("run $index", index) }
    }
}
