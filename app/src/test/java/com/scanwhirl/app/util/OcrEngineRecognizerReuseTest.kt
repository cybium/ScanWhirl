package com.scanwhirl.app.util

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
 * PERF item 5. ML Kit cannot run in a JVM, so the recognizer factory seam
 * supplies fakes that count create/analyze/close across a multi-page job.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OcrEngineRecognizerReuseTest {

    private var savedFactory: ((OcrScript) -> OcrEngine.ScriptRecognizer)? = null
    private var savedBitmapReader: (suspend (Bitmap, OcrScript) -> OcrRawPage)? = null
    private lateinit var byScript: MutableMap<OcrScript, MutableList<FakeRecognizer>>
    private lateinit var log: MutableList<String>

    @Before
    fun setUp() {
        savedFactory = OcrEngine.recognizerFactory
        savedBitmapReader = OcrEngine.rawBitmapReader
        byScript = mutableMapOf()
        log = mutableListOf()
        OcrEngine.rawBitmapReader = null
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
        savedFactory?.let { OcrEngine.recognizerFactory = it }
        OcrEngine.rawBitmapReader = savedBitmapReader
        Unit
    }

    private fun page(): Bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)

    @Test
    fun `ten pages reuse one Latin recognizer and keep identical text`() = runBlocking {
        val results = (0 until 10).map { OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH) }
        val recognizer = byScript.getValue(OcrScript.LATIN).single()
        assertEquals(1, byScript.getValue(OcrScript.LATIN).size)
        assertEquals(10, recognizer.analyses)
        assertEquals(0, recognizer.closes)
        assertTrue("all pages produced the same OCR text", results.distinct().size == 1)

        OcrEngine.closeRecognizers()
        assertEquals(1, recognizer.closes)
        // A later job creates a fresh recognizer; the cached one is gone.
        OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH)
        assertEquals(2, byScript.getValue(OcrScript.LATIN).size)
    }

    @Test
    fun `bilingual job creates one recognizer per script and reuses both`() = runBlocking {
        repeat(4) { OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH_HINDI) }
        assertEquals(1, byScript.getValue(OcrScript.LATIN).size)
        assertEquals(1, byScript.getValue(OcrScript.DEVANAGARI).size)
        assertEquals(4, byScript.getValue(OcrScript.LATIN).single().analyses)
        assertEquals(4, byScript.getValue(OcrScript.DEVANAGARI).single().analyses)
        assertEquals(2, log.count { it.startsWith("create:") })
    }

    @Test
    fun `cancellation releases the job lock and keeps the recognizer reusable`() {
        // The factory above always builds a new fake; replace it for this test so the
        // cancellation happens on the first (and only) recognizer of the job.
        val first = FakeRecognizer(OcrScript.LATIN, log).apply { failWith = CancellationException("cancelled") }
        OcrEngine.recognizerFactory = { _ -> first }

        try {
            runBlocking { OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH) }
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
        assertEquals(0, first.closes)
        first.failWith = null
        // A fresh scope: the mutex was released, and the same recognizer is reused.
        runBlocking { OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH) }
        assertEquals(2, first.analyses)
        assertEquals(0, first.closes)
    }

    @Test
    fun `OOM closes and drops the failed recognizer`() = runBlocking {
        val failing = FakeRecognizer(OcrScript.LATIN, log).apply { failWith = OutOfMemoryError("native heap") }
        OcrEngine.recognizerFactory = { _ -> failing }

        try {
            OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH)
            fail("OOM must propagate")
        } catch (e: OutOfMemoryError) {
            assertEquals("native heap", e.message)
        }
        assertEquals("recognizer released on OOM", 1, failing.closes)

        val healthy = FakeRecognizer(OcrScript.LATIN, log)
        OcrEngine.recognizerFactory = { _ -> healthy }
        OcrEngine.recognizeBitmap(page(), OcrLanguage.ENGLISH)
        assertEquals(1, healthy.analyses)
        assertEquals(1, failing.analyses) // the failed attempt still counted one analysis
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
