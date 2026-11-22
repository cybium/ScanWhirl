package com.scanwhirl.app.util

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ScanWhirlApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PERF item 5 re-review. ML Kit cannot run in a JVM, so the recognizer factory
 * seam supplies fakes that count create/analyze/close across a multi-page job.
 * The contract now is JOB-scoped reuse: nothing stays open after the job ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OcrEngineRecognizerReuseTest {

    private var savedFactory: ((OcrScript) -> OcrEngine.ScriptRecognizer)? = null
    private var savedBitmapReader: (suspend (Bitmap, OcrScript) -> OcrRawPage)? = null
    private var savedIdleMs = 30_000L
    private lateinit var byScript: MutableMap<OcrScript, MutableList<FakeRecognizer>>
    private lateinit var log: MutableList<String>

    @Before
    fun setUp() {
        savedFactory = OcrEngine.recognizerFactory
        savedBitmapReader = OcrEngine.rawBitmapReader
        savedIdleMs = OcrEngine.idleCloseMs
        byScript = mutableMapOf()
        log = mutableListOf()
        OcrEngine.rawBitmapReader = null
        OcrEngine.idleCloseMs = 30_000L
        OcrEngine.recognizerFactory = { script ->
            val recognizer = FakeRecognizer(script, log)
            byScript.getOrPut(script) { mutableListOf() }.add(recognizer)
            log += "create:$script"
            recognizer
        }
    }

    @After
    fun tearDown() = runBlocking {
        OcrEngine.closeRecognizers()
        OcrEngine.recognizerFactory = savedFactory!!
        OcrEngine.rawBitmapReader = savedBitmapReader
        OcrEngine.idleCloseMs = savedIdleMs
        Unit
    }

    private fun page(): Bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(3_000L) {
            while (!condition()) delay(10)
        }
    }

    @Test
    fun `a multi-page job reuses one recognizer per script and closes all when it ends`() = runBlocking {
        val results = OcrEngine.withRecognizerSession {
            (0 until 10).map { OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH_HINDI) }
        }
        val latin = byScript.getValue(OcrScript.LATIN).single()
        val devanagari = byScript.getValue(OcrScript.DEVANAGARI).single()
        assertEquals(10, latin.analyses)
        assertEquals(10, devanagari.analyses)
        assertTrue("no recognizer left open after the job", latin.closes == 1 && devanagari.closes == 1)
        assertTrue("all pages produced the same OCR text", results.distinct().size == 1)
    }

    @Test
    fun `nothing is cached after a job ends and the next job creates fresh recognizers`() = runBlocking {
        OcrEngine.withRecognizerSession {
            repeat(3) { OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH) }
        }
        val first = byScript.getValue(OcrScript.LATIN).single()
        assertEquals(1, first.closes)
        OcrEngine.withRecognizerSession { OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH) }
        assertEquals(2, byScript.getValue(OcrScript.LATIN).size)
        assertEquals(1, first.closes)
        assertEquals(1, byScript.getValue(OcrScript.LATIN).last().closes)
    }

    @Test
    fun `a single-page call outside a session creates and closes immediately`() = runBlocking {
        OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH)
        val recognizer = byScript.getValue(OcrScript.LATIN).single()
        assertEquals(1, recognizer.analyses)
        assertEquals(1, recognizer.closes)
    }

    @Test
    fun `a nested session does not close recognizers for the outer job`() = runBlocking {
        OcrEngine.withRecognizerSession {
            repeat(2) { OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH) }
            OcrEngine.withRecognizerSession {
                OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH)
            }
            assertEquals(0, byScript.getValue(OcrScript.LATIN).single().closes)
            OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH)
        }
        val recognizer = byScript.getValue(OcrScript.LATIN).single()
        assertEquals(4, recognizer.analyses)
        assertEquals(1, recognizer.closes)
    }

    @Test
    fun `cancellation ends the session and closes its recognizers`() {
        val failing = FakeRecognizer(OcrScript.LATIN, log).apply {
            failWith = CancellationException("cancelled")
        }
        OcrEngine.recognizerFactory = { _ -> failing }
        try {
            runBlocking {
                OcrEngine.withRecognizerSession {
                    OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH)
                }
            }
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
        assertEquals(1, failing.closes)
    }

    @Test
    fun `OOM closes and drops the failed recognizer`() = runBlocking {
        val failing = FakeRecognizer(OcrScript.LATIN, log).apply { failWith = OutOfMemoryError("native heap") }
        OcrEngine.recognizerFactory = { _ -> failing }
        try {
            OcrEngine.withRecognizerSession {
                OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH)
            }
            fail("OOM must propagate")
        } catch (e: OutOfMemoryError) {
            assertEquals("native heap", e.message)
        }
        assertEquals("recognizer released on OOM", 1, failing.closes)

        val healthy = FakeRecognizer(OcrScript.LATIN, log)
        OcrEngine.recognizerFactory = { _ -> healthy }
        OcrEngine.withRecognizerSession { OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH) }
        assertEquals(1, healthy.analyses)
        assertEquals(1, failing.analyses)
    }

    @Test
    fun `idle timer closes a recognizer left cached by a stalled job`() = runBlocking {
        OcrEngine.idleCloseMs = 80L
        val gate = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            OcrEngine.withRecognizerSession {
                OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH)
                gate.await()
            }
        }
        waitUntil { byScript[OcrScript.LATIN]?.isNotEmpty() == true }
        val recognizer = byScript.getValue(OcrScript.LATIN).single()
        assertEquals(0, recognizer.closes)
        waitUntil { recognizer.closes == 1 }
        gate.complete(Unit)
        job.join()
        assertEquals(1, recognizer.closes)
    }

    @Test
    fun `app memory trim closes cached recognizers`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            OcrEngine.withRecognizerSession {
                OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH)
                gate.await()
            }
        }
        waitUntil { byScript[OcrScript.LATIN]?.isNotEmpty() == true }
        val recognizer = byScript.getValue(OcrScript.LATIN).single()
        assertEquals(0, recognizer.closes)

        val app = ApplicationProvider.getApplicationContext<ScanWhirlApp>()
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        waitUntil { recognizer.closes == 1 }
        gate.complete(Unit)
        job.join()
        assertEquals(1, recognizer.closes)
    }

    private class FakeRecognizer(
        private val script: OcrScript,
        private val log: MutableList<String>
    ) : OcrEngine.ScriptRecognizer {
        var analyses = 0
        var closes = 0
        var failWith: Throwable? = null

        override suspend fun analyze(bitmap: Bitmap, rotationDegrees: Int): OcrEngine.Analysis {
            analyses++
            log += "analyze:$script:$rotationDegrees"
            failWith?.let { throw it }
            return OcrEngine.Analysis(
                lines = listOf(OcrRawLine("Zyaada Hindi", OcrBox(1, 2, 3, 4), script, 1f)),
                words = listOf(OcrEngine.WordBox("Zyaada", 1, 2, 3, 4))
            )
        }

        override fun close() {
            closes++
            log += "close:$script"
        }
    }
}
