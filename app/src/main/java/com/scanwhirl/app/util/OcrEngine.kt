package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR (Pro): Google's on-device Text Recognition runs completely on the phone — the model
 * downloads on first use and no image ever leaves the app. Honest label used in the UI: works
 * best on printed text; handwriting is unreliable.
 *
 * Since 2026-11 the pipeline is split so it can be tested and so Hindi works:
 *  - [OcrScriptRecognizers] create one ML Kit recognizer per script (Latin, Devanagari);
 *  - [OcrMerge] merges the scripts line by line, cleans the text ([OcrText]) and orders it with
 *    [OcrLayout] (columns, spreads, rotated pages);
 *  - [assemble] keeps the block-level API the rest of the app uses.
 *
 * The recognizers are behind [rawReader] / [rawBitmapReader] so Robolectric tests can supply
 * synthetic pages (ML Kit cannot run in a JVM).
 *
 * PERF item 5: a multi-page job wraps its pages in [withRecognizerSession], which reuses one
 * recognizer per script for the job and closes them all when the job ends. Single-page calls
 * outside a session still create/close immediately, so no model stays resident by default.
 * A [Mutex] keeps recognition to one job at a time (ML Kit recognizers are not guaranteed
 * thread-safe). An idle timer and the app's onTrimMemory are additional release safety nets.
 */
object OcrEngine {

    /**
     * M06: true when recognition itself is not available on this device, as
     * opposed to a page failing. This is ML Kit's `UNAVAILABLE` (e.g. Play
     * services has not downloaded the text model yet, as on an emulator without
     * the vision.ocr module). It is retryable later and is not the page's fault.
     */
    fun isUnavailable(error: Throwable): Boolean {
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth++ < 8) {
            if (e is com.google.mlkit.common.MlKitException &&
                e.errorCode == com.google.mlkit.common.MlKitException.UNAVAILABLE
            ) return true
            val msg = e.message.orEmpty()
            if (msg.contains("model to be downloaded", ignoreCase = true)) return true
            e = e.cause
        }
        return false
    }

    /** One recognised text block, reduced to what reading order needs. */
    data class OcrBlock(
        val top: Int,
        val left: Int,
        val lines: List<String>,
        /** Optional geometry; when every block has it, column/spread order is used. */
        val right: Int = 0,
        val bottom: Int = 0
    )

    /** A full OCR result: assembled plain text plus per-line geometry. */
    data class OcrResult(val text: String, val page: OcrPage)

    /** One word/element box from ML Kit (Mask Aadhaar needs element geometry). */
    data class WordBox(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    data class WordsResult(val page: OcrPage, val words: List<WordBox>)

    /**
     * Longest side of the bitmap handed to ML Kit. A 6 MP page is a 24 MB ARGB bitmap before
     * the recognizer's own copy; 2048 px keeps the cost bounded on 2 GB phones (C06).
     */
    private const val OCR_MAX_DIM = 2048

    // ------------------------------------------------------------------
    // Test seams (never set in production)
    // ------------------------------------------------------------------

    /** Reads one page file with one script. Replaced by tests with synthetic lines. */
    @Volatile
    internal var rawReader: (suspend (File, OcrScript) -> OcrRawPage)? = null

    /** Reads one in-memory bitmap with one script. Replaced by tests with synthetic lines. */
    @Volatile
    internal var rawBitmapReader: (suspend (Bitmap, OcrScript) -> OcrRawPage)? = null

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    suspend fun recognize(page: File, language: OcrLanguage = OcrLanguage.ENGLISH): String =
        recognizeFile(page, language).text

    /**
     * Recognises a single page image file with [language]'s recognizers and returns both the
     * assembled text and the per-line geometry ([OcrPage]). The bitmap is decoded once and each
     * script runs on it; lines are merged, cleaned and ordered in reading order.
     */
    suspend fun recognizeFile(
        page: File,
        language: OcrLanguage = OcrLanguage.ENGLISH
    ): OcrResult = withContext(Dispatchers.IO) {
        require(page.exists() && page.length() > 0L) { "Page is empty" }
        val reader = rawReader
        val rawPages = if (reader != null) {
            language.scripts.map { script -> reader(page, script) }
        } else {
            val bitmap = ImageTools.decodeSampledFile(page, OCR_MAX_DIM)
            val rotation = exifRotation(page)
            try {
                language.scripts.map { script -> mlKit(bitmap, script, rotation) }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        toResult(OcrMerge.merge(rawPages))
    }

    /**
     * Mask Aadhaar: ML Kit **element** (word) boxes for the page. Uses the Latin recognizer only
     * (Aadhaar digits are Latin); the mask needs elements so the first two 4-digit groups are
     * covered exactly. Fails like [recognizeFile] when the model is unavailable.
     */
    suspend fun recognizeWords(page: File): WordsResult = withContext(Dispatchers.IO) {
        require(page.exists() && page.length() > 0L) { "Page is empty" }
        val bitmap = ImageTools.decodeSampledFile(page, OCR_MAX_DIM)
        val rotation = exifRotation(page)
        try {
            val analysis = withScriptRecognizer(OcrScript.LATIN) { it.analyze(bitmap, rotation) }
            val lines = analysis.lines.map { line ->
                OcrLine(line.text, line.box.left, line.box.top, line.box.right, line.box.bottom)
            }
            WordsResult(OcrPage(bitmap.width, bitmap.height, lines), analysis.words)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** OCR directly from encoded image bytes (device PDFs and gallery images). */
    suspend fun recognizeBytes(
        bytes: ByteArray,
        language: OcrLanguage = OcrLanguage.ENGLISH
    ): String = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "Image is empty" }
        // R08 F2: BitmapFactory never applies EXIF. The gallery-crop fallback hands this path
        // raw provider bytes, so a sideways photo must be turned upright before ML Kit sees it —
        // the same tag/transform recognizeFile and the main OCR path already honour.
        val bitmap = ImageTools.decodeUpright(bytes, OCR_MAX_DIM, ImageTools.exifOrientation(bytes))
        try {
            recognizeBitmap(bitmap, language)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** OCR a bitmap the caller owns (it is not recycled here). */
    suspend fun recognizeBitmap(
        bitmap: Bitmap,
        language: OcrLanguage = OcrLanguage.ENGLISH
    ): String = withContext(Dispatchers.IO) {
        val reader = rawBitmapReader
        val rawPages = if (reader != null) {
            language.scripts.map { script -> reader(bitmap, script) }
        } else {
            language.scripts.map { script -> mlKit(bitmap, script) }
        }
        OcrMerge.assemble(OcrMerge.merge(rawPages))
    }

    /**
     * Pure reading-order assembly for callers that already have blocks. Blocks with full
     * geometry go through [OcrLayout] (columns, spreads); geometry-less blocks keep the
     * long-standing top-then-left order.
     */
    fun assemble(blocks: List<OcrBlock>): String {
        val nonEmpty = blocks.filter { block -> block.lines.any { it.isNotBlank() } }
        if (nonEmpty.isEmpty()) return ""
        val withGeometry = nonEmpty.all { it.right > it.left && it.bottom > it.top }
        val ordered = if (withGeometry) {
            OcrLayout.valuesInReadingOrder(
                units = nonEmpty.map { OcrUnit(OcrBox(it.left, it.top, it.right, it.bottom), it) },
                pageWidth = nonEmpty.maxOf { it.right },
                pageHeight = nonEmpty.maxOf { it.bottom }
            )
        } else {
            nonEmpty.sortedWith(compareBy({ it.top }, { it.left }))
        }
        return ordered.joinToString("\n\n") { block ->
            block.lines.dropWhile { it.isBlank() }
                .dropLastWhile { it.isBlank() }
                .joinToString("\n")
        }.trim()
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun toResult(page: OcrRawPage): OcrResult {
        val lines = page.lines.map { line ->
            OcrLine(
                text = line.text,
                left = line.box.left,
                top = line.box.top,
                right = line.box.right,
                bottom = line.box.bottom
            )
        }
        return OcrResult(OcrMerge.assemble(page), OcrPage(page.width, page.height, lines))
    }

    /**
     * The boundary around a native recognizer. The extraction here is the same
     * code the per-page path used before PERF item 5; only its lifetime changed.
     */
    internal interface ScriptRecognizer : Closeable {
        suspend fun analyze(bitmap: Bitmap, rotationDegrees: Int): Analysis
        override fun close()
    }

    /** Lines and element boxes extracted from one ML Kit result for one script. */
    internal data class Analysis(
        val lines: List<OcrRawLine>,
        val words: List<WordBox>
    )

    private class MlKitRecognizer(private val script: OcrScript) : ScriptRecognizer {
        private val recognizer: TextRecognizer = OcrScriptRecognizers.create(script)

        override suspend fun analyze(bitmap: Bitmap, rotationDegrees: Int): Analysis {
            val text = suspendCancellableCoroutine<Text> { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, rotationDegrees))
                    .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                    .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
            }
            val lines = text.textBlocks.flatMap { block ->
                block.lines
                    .filter { it.text.isNotBlank() }
                    .map { line ->
                        val box = line.boundingBox ?: block.boundingBox
                        OcrRawLine(
                            text = line.text,
                            box = box?.let { OcrBox(it.left, it.top, it.right, it.bottom) }
                                ?: OcrBox(0, 0, 0, 0),
                            script = script,
                            confidence = scriptConfidence(line.text, script)
                        )
                    }
            }
            val words = text.textBlocks
                .flatMap { it.lines }
                .flatMap { it.elements }
                .mapNotNull { element ->
                    val box = element.boundingBox ?: return@mapNotNull null
                    if (element.text.isBlank()) null
                    else WordBox(
                        element.text,
                        box.left,
                        box.top,
                        box.right,
                        box.bottom
                    )
                }
            return Analysis(lines, words)
        }

        override fun close() {
            recognizer.close()
        }
    }

    /** Marks the coroutine of a multi-page job; only jobs in this context cache recognizers. */
    private class SessionElement : AbstractCoroutineContextElement(SessionElement) {
        companion object Key : CoroutineContext.Key<SessionElement>
    }

    /** One recognizer per script, reused only while a [withRecognizerSession] is active. */
    private val recognizers = LinkedHashMap<OcrScript, ScriptRecognizer>()
    private val recognizerMutex = Mutex()
    private val useGeneration = AtomicLong(0)

    /** Idle safety net: a recognizer left cached by a stuck job is closed this long after last use. */
    private val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal var idleCloseMs: Long = 30_000L

    /** Test seam: fakes count create/analyze/close instead of loading ML Kit models. */
    @Volatile
    internal var recognizerFactory: (OcrScript) -> ScriptRecognizer = { MlKitRecognizer(it) }

    /**
     * Wraps a multi-page OCR job. One recognizer per script is created on first use,
     * shared by every page of the job, and closed in [finally] when the job ends.
     * Nested sessions are harmless: only the outermost one closes recognizers.
     */
    suspend fun <T> withRecognizerSession(block: suspend () -> T): T {
        if (currentCoroutineContext()[SessionElement] != null) return block()
        return withContext(SessionElement()) {
            try {
                block()
            } finally {
                // A cancelled job still has to release native models.
                withContext(NonCancellable) { closeRecognizers() }
            }
        }
    }

    /** Rearms the idle release; a stale timer from an earlier use is invalidated. */
    private fun noteUse() {
        val generation = useGeneration.incrementAndGet()
        idleScope.launch {
            delay(idleCloseMs)
            // No use for the whole idle window: a stalled job must not pin a model.
            if (generation == useGeneration.get()) closeRecognizers()
        }
    }

    /**
     * One job at a time: the mutex is held for the whole analysis, so a shared
     * recognizer is never used concurrently. Cancellation releases the mutex;
     * an unavailable/OOM-failed recognizer is closed and removed so a retry
     * starts from a clean native instance. Outside a session the recognizer is
     * closed in [finally], so a single-page call never leaves a model resident.
     */
    private suspend fun <T> withScriptRecognizer(
        script: OcrScript,
        block: suspend (ScriptRecognizer) -> T
    ): T = recognizerMutex.withLock {
        val inSession = currentCoroutineContext()[SessionElement] != null
        val recognizer = if (inSession) {
            recognizers[script] ?: recognizerFactory(script).also { recognizers[script] = it }
        } else {
            recognizerFactory(script)
        }
        if (inSession) noteUse()
        var failed = false
        try {
            block(recognizer)
        } catch (e: Throwable) {
            failed = isUnavailable(e) || e is OutOfMemoryError
            throw e
        } finally {
            if (failed && recognizers[script] === recognizer) recognizers.remove(script)
            if (!inSession || failed) recognizer.close()
        }
    }

    /**
     * Closes every recognizer cached by the current session (session end, app
     * memory trim, idle timer or test teardown). Safe to call repeatedly; the
     * next recognition creates a fresh one per script.
     */
    suspend fun closeRecognizers() = recognizerMutex.withLock {
        useGeneration.incrementAndGet()
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }

    private suspend fun mlKit(
        bitmap: Bitmap,
        script: OcrScript,
        rotationDegrees: Int = 0
    ): OcrRawPage = withScriptRecognizer(script) { recognizer ->
        val analysis = recognizer.analyze(bitmap, rotationDegrees)
        OcrRawPage(bitmap.width, bitmap.height, analysis.lines)
    }

    /** Does the text look like the script that produced it? Used when two scripts disagree. */
    private fun scriptConfidence(text: String, script: OcrScript): Float = when (script) {
        OcrScript.DEVANAGARI -> if (OcrText.hasDevanagari(text)) 1f else 0.2f
        OcrScript.LATIN -> if (OcrText.looksLatin(text)) 1f else 0.2f
    }

    /**
     * EXIF orientation of a page file in degrees (0/90/180/270). ML Kit is given the rotation so
     * it returns upright boxes; a sideways notebook photo therefore does not scramble the
     * reading order. Unreadable EXIF means "upright".
     */
    internal fun exifRotation(file: File): Int = try {
        val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
        when (exif.getAttributeInt(
            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        )) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }
}

/** Creates the ML Kit client for one script. DEVICE-UNPROVEN (needs Play services / bundled model). */
internal object OcrScriptRecognizers {

    fun create(script: OcrScript): TextRecognizer = when (script) {
        OcrScript.LATIN ->
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        OcrScript.DEVANAGARI ->
            TextRecognition.getClient(
                com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions.Builder().build()
            )
    }
}
