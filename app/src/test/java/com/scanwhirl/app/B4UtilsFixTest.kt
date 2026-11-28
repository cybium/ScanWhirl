package com.scanwhirl.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ImportDiscardStore
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.BatchRename
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.OcrBox
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrRawLine
import com.scanwhirl.app.util.OcrRawPage
import com.scanwhirl.app.util.OcrScript
import com.scanwhirl.app.util.ShareUtils
import com.scanwhirl.app.util.ZipExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * B4 (safety round 2): the five utility/store fixes. Every defect test fails on the old code;
 * each fix also has a positive control on the ordinary path.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class B4UtilsFixTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun resetSeams() {
        // FileProvider caches its roots per authority; Robolectric gives each test a fresh cache.
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
        OcrEngine.rawBitmapReader = null
        ImageTools.galleryOrientationOverride = null
        ShareUtils.shareCopyDispatcher = Dispatchers.IO
        ShareUtils.namedShareCopy = { src, out -> src.copyTo(out, overwrite = false) }
        ImportDiscardStore.fault = {}
        Durable.resetForTests()
    }

    @After fun restoreSeams() {
        OcrEngine.rawBitmapReader = null
        ImageTools.galleryOrientationOverride = null
        ShareUtils.shareCopyDispatcher = Dispatchers.IO
        ShareUtils.namedShareCopy = { src, out -> src.copyTo(out, overwrite = false) }
        ImportDiscardStore.fault = {}
        Durable.resetForTests()
    }

    private fun orientedBytes(orientation: Int, width: Int = 120, height: Int = 80): ByteArray {
        val file = File(app.cacheDir, "b4_exif_${orientation}_${System.nanoTime()}.jpg")
        val bytes = ImportFixtures.oriented(file, orientation, width = width, height = height)
        file.delete()
        return bytes
    }

    /** Captures the bitmap OCR actually sees, without loading an ML Kit model. */
    private fun captureOcrBitmap(): Pair<() -> Pair<Int, Int>, () -> Unit> {
        var seen: Pair<Int, Int> = -1 to -1
        OcrEngine.rawBitmapReader = { bitmap, _ ->
            seen = bitmap.width to bitmap.height
            OcrRawPage(
                bitmap.width, bitmap.height,
                listOf(OcrRawLine("recognized", OcrBox(0, 0, 10, 10), OcrScript.LATIN))
            )
        }
        return ({ seen }) to ({ OcrEngine.rawBitmapReader = null })
    }

    // ---- R08 F2: OCR from raw bytes must honour EXIF ------------------------

    @Test fun `recognizeBytes decodes a sideways EXIF photo upright`() = runBlocking {
        val bytes = orientedBytes(ExifInterface.ORIENTATION_ROTATE_90)
        val before = bytes.copyOf()
        val (size, _) = captureOcrBitmap()
        assertEquals("recognized", OcrEngine.recognizeBytes(bytes))
        assertEquals("EXIF 90° must swap the sides before recognition", 80 to 120, size())
        assertArrayEquals("the caller's bytes are never rewritten", before, bytes)
    }

    @Test fun `recognizeBytes keeps an upright photo and its reading order`() = runBlocking {
        val bytes = orientedBytes(ExifInterface.ORIENTATION_NORMAL)
        val (size, reset) = captureOcrBitmap()
        try {
            assertEquals("recognized", OcrEngine.recognizeBytes(bytes))
            assertEquals(120 to 80, size())
        } finally {
            reset()
        }
    }

    // ---- R09 F2: the {date} token is invariant -----------------------------

    private fun stampUtc(): Long {
        val utc = TimeZone.getTimeZone("UTC")
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(utc)
        try {
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-11-27")!!.time
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test fun `date token uses Gregorian ASCII digits under a Thai phone locale`() {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH"))
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals(
                listOf("Bill 2026-11-27"),
                BatchRename.apply(
                    "{name} {date}",
                    listOf(BatchRename.Item("Bill", stampUtc()))
                )
            )
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }

    @Test fun `rename tokens and the live preview still work normally`() {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val items = listOf(BatchRename.Item("Bill", stampUtc()), BatchRename.Item("Rent", stampUtc()))
            assertEquals(
                listOf("Bill 1 2026-11-27", "Bill 2 2026-11-27"),
                BatchRename.preview("{name} {n} {date}", items)
            )
            assertEquals(listOf("Bill 1", "Rent 2"), BatchRename.apply("{name} {n}", items))
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }

    // ---- R09 F3: multi-file share copies off main, chooser on main ----------

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        val chooserOnMain = mutableListOf<Boolean>()
        override fun startActivity(intent: Intent) {
            started += intent
            chooserOnMain += (Looper.myLooper() == Looper.getMainLooper())
        }
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    private fun fakeDoc(id: String, name: String, bytes: ByteArray?): ScanDocument {
        val dir = File(app.cacheDir, "b4_doc_$id").apply { mkdirs() }
        val pdf = File(dir, "scan.pdf")
        if (bytes != null) pdf.writeBytes(bytes) else pdf.delete()
        return ScanDocument(id, name, 1L, 1, pdf, emptyList())
    }

    @Test fun `multi file share copies off main without blocking it and starts one chooser`() {
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "sw-b4-io") }
        val threads = Collections.synchronizedList(mutableListOf<String>())
        val copyStarted = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        ShareUtils.shareCopyDispatcher = pool.asCoroutineDispatcher()
        ShareUtils.namedShareCopy = { src, out ->
            threads += Thread.currentThread().name
            copyStarted.countDown()
            releaseCopy.await(10, TimeUnit.SECONDS)
            src.copyTo(out, overwrite = false)
        }
        val context = RecordingContext(app)
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5)
        val docs = listOf(fakeDoc("d1", "Same", first), fakeDoc("d2", "Same", second))
        try {
            // The click callback must return while the copies are still running.
            ShareUtils.shareDocuments(context, docs)
            assertTrue("a copy must actually start", copyStarted.await(5, TimeUnit.SECONDS))
            assertTrue("chooser must wait for every copy", context.started.isEmpty())
            val mainTaskRan = AtomicBoolean(false)
            Handler(Looper.getMainLooper()).post { mainTaskRan.set(true) }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("main looper stayed responsive during the IO copy", mainTaskRan.get())
        } finally {
            releaseCopy.countDown()
        }
        assertTrue("exactly one chooser is posted after the copies", waitUntil { context.started.size == 1 })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, context.started.size)
        assertEquals("the chooser runs on main", true, context.chooserOnMain.single())
        assertEquals("copies ran on the IO dispatcher", listOf("sw-b4-io", "sw-b4-io"), threads.toList())
        @Suppress("DEPRECATION")
        val send = context.started[0].getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION")
        val uris = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!.toList()
        assertEquals("names and order are unchanged", listOf("Same.pdf", "Same (2).pdf"), uris.map { it.lastPathSegment })
        assertArrayEquals(first, app.contentResolver.openInputStream(uris[0])!!.use { it.readBytes() })
        assertArrayEquals(second, app.contentResolver.openInputStream(uris[1])!!.use { it.readBytes() })
        pool.shutdownNow()
    }

    @Test fun `the shell protection guard still runs before any multi share copy`() {
        val shell = File("src/main/java/com/scanwhirl/app/ui/shell/ShellActivity.kt").readText()
        val start = shell.indexOf("private fun shareDocuments")
        assertTrue("the selection share entry point is still there", start >= 0)
        val guard = shell.indexOf("protectionState", start)
        val copy = shell.indexOf("ShareUtils.shareDocuments", start)
        assertTrue("a protection guard is present", guard > start)
        assertTrue("the guard must run BEFORE the copy", copy > guard)
    }

    private class FinishingActivity : android.app.Activity() {
        val started = mutableListOf<Intent>()
        override fun isFinishing(): Boolean = true
        override fun startActivity(intent: Intent) { started += intent }
    }

    @Test fun `a finishing activity gets no chooser after the copies complete`() {
        val controller = Robolectric.buildActivity(FinishingActivity::class.java).setup()
        val activity = controller.get()
        val copiesDone = CountDownLatch(2)
        ShareUtils.namedShareCopy = { src, out ->
            src.copyTo(out, overwrite = false)
            copiesDone.countDown()
        }
        try {
            val docs = listOf(fakeDoc("dead1", "Dead", byteArrayOf(1)), fakeDoc("dead2", "Dead", byteArrayOf(2)))
            ShareUtils.shareDocuments(activity, docs)
            assertTrue("the copies completed", copiesDone.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("no chooser may be posted on a finishing activity", activity.started.isEmpty())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // ---- R09 NEW-1: an empty ZIP is a failure ------------------------------

    @Test fun `zip export with no readable entries fails and leaves no file`() {
        val docs = listOf(fakeDoc("missing", "Missing", null), fakeDoc("empty", "Empty", byteArrayOf()))
        val out = File(app.cacheDir, "b4_empty_${System.nanoTime()}.zip")
        try {
            ZipExporter.export(docs, out)
            fail("an archive with zero entries must not be reported as a success")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no entries"))
        }
        assertFalse("no empty ZIP is published", out.exists())
    }

    @Test fun `zip export still writes every real entry with numbered names`() {
        val dir = File(app.cacheDir, "b4_zip_${System.nanoTime()}").apply { mkdirs() }
        val a = File(dir, "a.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val b = File(dir, "b.pdf").apply { writeBytes(byteArrayOf(4, 5)) }
        val out = File(dir, "out.zip")
        ZipExporter.exportFiles(listOf(a to "Report.pdf", b to "Report.pdf"), out)
        val names = mutableListOf<String>()
        ZipFile(out).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) names += entries.nextElement().name
        }
        assertEquals(listOf("Report.pdf", "Report (2).pdf"), names)
    }

    // ---- R01 F1: undo restore resolves a title claimed during the window ---

    private fun escrowStore() = ImportDiscardStore(
        app, ScanRepository(app), { 1_000L }, { 7 }, { 1_700_000_000_000L }
    )

    @Test fun `undo restore suffixes a title claimed while the receipt was live`() {
        val repo = ScanRepository(app)
        val store = escrowStore()
        val first = DocFixtures.saved(repo, app, pages = 1, name = "UndoTitle")
        val token = store.discard(first, 8_000)
        // A second document takes the free title: uniqueDocumentName cannot see the escrow.
        val replacement = DocFixtures.saved(repo, app, pages = 1, name = "UndoTitle")
        assertEquals("UndoTitle", repo.getDocument(replacement)!!.name)

        val restored = store.restore(token.key)
        assertNotNull("Undo must still work", restored)
        assertNotEquals("the restored item is a different document", first, restored)
        assertEquals("UndoTitle (2)", repo.getDocument(restored!!)!!.name)
        assertEquals("the newer document is untouched", "UndoTitle", repo.getDocument(replacement)!!.name)
    }

    @Test fun `undo restore without a collision keeps the original title`() {
        val repo = ScanRepository(app)
        val store = escrowStore()
        val id = DocFixtures.saved(repo, app, pages = 1, name = "SoloUndo")
        val token = store.discard(id, 8_000)
        DocFixtures.saved(repo, app, pages = 1, name = "Unrelated")
        val restored = store.restore(token.key)
        assertNotNull(restored)
        assertEquals("SoloUndo", repo.getDocument(restored!!)!!.name)
    }
}
