package com.scanwhirl.app.pdf.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.PdfDecryptor
import com.scanwhirl.app.util.PdfEncryptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * Harm prevented: a page edit must not silently turn a digital PDF into pixels or
 * retain a hidden string. Every output is re-opened with Apache PDFBox 3.0.8, an
 * independent reader that is test-only and never shipped in the APK.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfBoxEngineDigitalPagesTest {

    private lateinit var dir: File
    private lateinit var engine: PdfBoxEngine

    @Before
    fun setUp() {
        dir = File(File(java.io.File.separator, "tmp"), "w7a-engine-${System.nanoTime()}")
        dir.mkdirs()
        // Robolectric runs tests on the main looper; the production guard stays on.
        engine = PdfBoxEngine(
            ApplicationProvider.getApplicationContext(), File(dir, "work"),
            enforceWorkerThread = false
        )
    }

    @After
    fun tearDown() {
        engine.close()
        dir.deleteRecursively()
    }

    private fun engineWith(budgets: PdfEngineBudgets): PdfBoxEngine = PdfBoxEngine(
        ApplicationProvider.getApplicationContext(), File(dir, "budget-${System.nanoTime()}"),
        budgets = budgets, enforceWorkerThread = false
    )

    private fun makePdf(
        name: String, pages: Int, marker: String,
        secret: String? = null, secretOnKeptPage: Boolean = false, outlineSecret: Boolean = false
    ): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            repeat(pages) { index ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                    cs.newLineAtOffset(48f, 720f)
                    cs.showText("$marker page ${index + 1}")
                    cs.endText()
                    if (secret != null && index == 0) {
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA, 12f)
                        cs.newLineAtOffset(48f, 690f)
                        cs.showText(secret)
                        cs.endText()
                    }
                }
                if (secret != null && (index == 0 || (secretOnKeptPage && index == 1))) {
                    val action = PDActionURI().apply { uri = "https://example.invalid/$secret" }
                    page.annotations = listOf(PDAnnotationLink().apply {
                        rectangle = PDRectangle(48f, 650f, 300f, 18f)
                        this.action = action
                    })
                }
            }
            if (secret != null) {
                doc.documentInformation = PDDocumentInformation().apply {
                    title = "Private $secret"
                    subject = secret
                }
            }
            if (outlineSecret && secret != null) {
                val outline = PDDocumentOutline()
                val item = PDOutlineItem().apply {
                    title = "Chapter $secret"
                    setDestination(doc.getPage(0))
                }
                outline.addLast(item)
                doc.documentCatalog.documentOutline = outline
            }
            doc.save(file)
        }
        return file
    }

    private fun oracleText(file: File): String = Loader.loadPDF(file).use { doc ->
        PDFTextStripper().getText(doc)
    }

    private fun iso(text: String): ByteArray = text.toByteArray(Charset.forName("ISO-8859-1"))

    private fun iso(bytes: ByteArray): String = String(bytes, Charset.forName("ISO-8859-1"))

    private fun startxrefOf(raw: String): Int =
        Regex("startxref\\s+(\\d+)").findAll(raw).last().groupValues[1].toInt()

    private fun rootRefOf(raw: String): String {
        val match = Regex("/Root\\s+(\\d+)\\s+(\\d+)\\s+R").findAll(raw).last()
        return "${match.groupValues[1]} ${match.groupValues[2]} R"
    }

    private fun xrefRow(offset: Int, generation: Int): String =
        offset.toString().padStart(10, '0') + " " +
            generation.toString().padStart(5, '0') + " n \n"

    /** A plain PDF 1.5 whose only xref is an uncompressed /Type /XRef stream. */
    private fun xrefStreamPdf(file: File, marker: String): File {
        val out = ByteArrayOutputStream()
        val offsets = HashMap<Int, Int>()
        fun write(text: String) = out.write(iso(text))
        fun objectAt(number: Int, body: String) {
            offsets[number] = out.size()
            write("$number 0 obj\n$body\nendobj\n")
        }
        write("%PDF-1.5\n%\u00E2\u00E3\u00CF\u00D3\n")
        objectAt(1, "<< /Type /Catalog /Pages 2 0 R >>")
        objectAt(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        objectAt(
            3,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>"
        )
        val content = "BT /F1 24 Tf 72 720 Td ($marker) Tj ET"
        objectAt(4, "<< /Length ${iso(content).size} >>\nstream\n$content\nendstream")
        objectAt(5, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        val xrefNumber = 6
        val xrefOffset = out.size()
        offsets[xrefNumber] = xrefOffset
        val rows = ByteArray((xrefNumber + 1) * 7)
        fun row(number: Int, type: Int, offset: Int, generation: Int) {
            val base = number * 7
            rows[base] = type.toByte()
            rows[base + 1] = (offset ushr 24).toByte()
            rows[base + 2] = (offset ushr 16).toByte()
            rows[base + 3] = (offset ushr 8).toByte()
            rows[base + 4] = offset.toByte()
            rows[base + 5] = (generation ushr 8).toByte()
            rows[base + 6] = generation.toByte()
        }
        row(0, 0, 0, 65535)
        for (number in 1..xrefNumber) row(number, 1, offsets.getValue(number), 0)
        write(
            "$xrefNumber 0 obj\n<< /Type /XRef /Size ${xrefNumber + 1} /W [1 4 2] " +
                "/Root 1 0 R /Length ${rows.size} >>\nstream\n"
        )
        out.write(rows)
        write("\nendstream\nendobj\nstartxref\n$xrefOffset\n%%EOF\n")
        file.writeBytes(out.toByteArray())
        return file
    }

    /** A plain PDF with one appended incremental update section (/Prev), no new pages. */
    private fun incrementalUpdatePdf(base: File, target: File): File {
        val baseBytes = base.readBytes()
        val raw = iso(baseBytes)
        val out = ByteArrayOutputStream()
        out.write(baseBytes)
        if (!raw.endsWith("\n")) out.write('\n'.code)
        val objectNumber = 90
        val objectOffset = out.size()
        out.write(iso("$objectNumber 0 obj\n<< /Title (Incremental update fixture) >>\nendobj\n"))
        val xrefOffset = out.size()
        out.write(iso("xref\n$objectNumber 1\n" + xrefRow(objectOffset, 0)))
        out.write(
            iso(
                "trailer\n<< /Size ${objectNumber + 1} /Root ${rootRefOf(raw)} " +
                    "/Info $objectNumber 0 R /Prev ${startxrefOf(raw)} >>\n" +
                    "startxref\n$xrefOffset\n%%EOF\n"
            )
        )
        target.writeBytes(out.toByteArray())
        return target
    }

    /** A plain hybrid-reference PDF: a classic section whose trailer names an /XRefStm stream. */
    private fun hybridXrefStmPdf(base: File, target: File): File {
        val baseBytes = base.readBytes()
        val raw = iso(baseBytes)
        val out = ByteArrayOutputStream()
        out.write(baseBytes)
        if (!raw.endsWith("\n")) out.write('\n'.code)
        val streamNumber = 91
        val size = streamNumber + 1
        val streamOffset = out.size()
        val row = byteArrayOf(0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte())
        out.write(
            iso(
                "$streamNumber 0 obj\n<< /Type /XRef /Size $size /Index [0 1] " +
                    "/W [1 4 2] /Length ${row.size} >>\nstream\n"
            )
        )
        out.write(row)
        out.write(iso("\nendstream\nendobj\n"))
        val xrefOffset = out.size()
        out.write(iso("xref\n$streamNumber 1\n" + xrefRow(streamOffset, 0)))
        out.write(
            iso(
                "trailer\n<< /Size $size /Root ${rootRefOf(raw)} /Prev ${startxrefOf(raw)} " +
                    "/XRefStm $streamOffset >>\nstartxref\n$xrefOffset\n%%EOF\n"
            )
        )
        target.writeBytes(out.toByteArray())
        return target
    }

    /** Adds one entry to the last trailer dictionary; xref offsets are all before it. */
    private fun withTrailerEntry(source: File, target: File, entry: String): File {
        val raw = iso(source.readBytes())
        val trailer = raw.lastIndexOf("trailer")
        check(trailer >= 0) { "fixture has no trailer" }
        val dict = raw.indexOf("<<", trailer)
        check(dict >= 0) { "fixture trailer has no dictionary" }
        target.writeBytes(iso(raw.substring(0, dict + 2) + " $entry " + raw.substring(dict + 2)))
        return target
    }

    /** Replaces the number after the last startxref with [value]. */
    private fun replaceLastStartxref(raw: String, value: Int): String {
        val at = raw.lastIndexOf("startxref")
        check(at >= 0) { "fixture has no startxref" }
        val start = at + "startxref".length
        val lineEnd = raw.indexOf('\n', start).let { if (it < 0) raw.length else it }
        return raw.substring(0, start) + "\n$value" + raw.substring(lineEnd)
    }

    private fun probeOf(file: File): String = engine.encryptionProbeName(file.readBytes())

    private fun signaturePng(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(120, 60, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawRect(0f, 0f, 120f, 60f, android.graphics.Paint().apply {
            color = android.graphics.Color.BLUE
        })
        val bytes = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
        bitmap.recycle()
        return bytes.toByteArray()
    }

    private fun expectError(block: () -> Unit): PdfEngineError {
        try {
            block()
        } catch (e: PdfEngineError) {
            return e
        }
        fail("expected a typed PdfEngineError")
        throw AssertionError("unreachable")
    }

    @Test
    fun pageOperationsKeepExactDigitalPages() {
        val base = makePdf("base.pdf", 3, "PUBLIC_ALPHA")
        val other = makePdf("other.pdf", 2, "PUBLIC_BETA")

        val copy = engine.copyPages(engine.open(base), intArrayOf(0, 2), File(dir, "copy.pdf").path)
        val copyText = oracleText(copy.file)
        assertTrue(copyText.contains("PUBLIC_ALPHA page 1"))
        assertTrue(copyText.contains("PUBLIC_ALPHA page 3"))
        assertFalse(copyText.contains("page 2"))

        // A non-rotated sequence proves reorder and delete actually changed the pages.
        val edited = engine.copyPages(engine.open(base), intArrayOf(0, 1, 2), File(dir, "edited.pdf").path)
        engine.reorder(edited, intArrayOf(2, 0, 1))
        engine.delete(edited, intArrayOf(1))
        val editedDoc = Loader.loadPDF(edited.file)
        val editedText = PDFTextStripper().getText(editedDoc).replace(Regex("\\s+"), " ")
        assertEquals(2, editedDoc.numberOfPages)
        assertTrue(editedText.contains("PUBLIC_ALPHA page 3"))
        assertTrue(editedText.contains("PUBLIC_ALPHA page 2"))
        assertFalse(editedText.contains("page 1"))
        editedDoc.close()

        // Rotation is checked structurally because text order on a rotated page is reader-specific.
        engine.rotate(edited, 0, 90)
        Loader.loadPDF(edited.file).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertEquals(90, doc.getPage(0).rotation)
            assertTrue(PDFTextStripper().getText(doc).isNotBlank())
        }

        val merged = engine.merge(listOf(base, other), File(dir, "merged.pdf").path)
        assertEquals(5, merged.pageCount)
        val mergedText = oracleText(merged.file)
        assertTrue(mergedText.contains("PUBLIC_ALPHA page 3"))
        assertTrue(mergedText.contains("PUBLIC_BETA page 2"))

        val parts = engine.split(merged, listOf(0..1, 2..4))
        assertEquals(2, parts.size)
        assertTrue(oracleText(parts[0].file).contains("PUBLIC_ALPHA page 2"))
        assertTrue(oracleText(parts[1].file).contains("PUBLIC_BETA page 1"))
        assertFalse(oracleText(parts[0].file).contains("PUBLIC_BETA"))
    }

    @Test
    fun overlayAddsSelectableSignatureWithoutRasterising() {
        val base = makePdf("overlay.pdf", 1, "PUBLIC_ALPHA")
        val handle = engine.open(base)
        engine.overlay(
            handle, 0, signaturePng(), PdfRect(360f, 60f, 160f, 80f),
            "SIGNED BY SCANWHIRL TEST", "2026-11-28"
        )
        val text = oracleText(handle.file)
        assertTrue(text.contains("PUBLIC_ALPHA page 1"))
        assertTrue(text.contains("SIGNED BY SCANWHIRL TEST"))
        assertTrue(text.contains("2026-11-28"))
    }

    @Test
    fun redactionDropsSecretsFromEveryPageCarrier() {
        val secret = "TOP_SECRET_ALPHA_4821"
        val source = makePdf(
            "redact-source.pdf", 2, "PUBLIC", secret,
            secretOnKeptPage = true, outlineSecret = true
        )
        val handle = engine.open(source)
        val safe = engine.redact(
            handle, 0, listOf(PdfRect(0f, 620f, 400f, 160f)), File(dir, "redacted.pdf").path
        )
        val text = oracleText(safe.file)
        assertFalse(text.contains(secret))
        assertTrue(text.contains("PUBLIC page 2"))
        val raw = String(safe.file.readBytes(), Charset.forName("ISO-8859-1"))
        assertFalse(raw.contains(secret))
        assertFalse(raw.contains("/Annots"))
        assertFalse(raw.contains("/Names"))
        assertFalse(raw.contains("/Outlines"))
        assertFalse(raw.contains("/Prev"))
        assertEquals(1, Regex("%%EOF").findAll(raw).count())
        assertFalse(PDDocument.load(safe.file).use { it.documentInformation.title ?: "" }.contains(secret))
        Loader.loadPDF(safe.file).use { doc ->
            assertTrue(PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(doc).trim().isEmpty())
        }
    }

    @Test
    fun wrongPasswordMalformedUnsupportedAndBudgetsGiveTypedErrors() {
        val plain = makePdf("plain.pdf", 1, "PUBLIC_ALPHA")
        val protected = File(dir, "protected.pdf")
        PdfEncryptor.encrypt(plain, protected, "right-password")

        assertTrue(expectError { engine.open(protected, "wrong-password") } is PdfEngineError.InvalidPassword)
        assertEquals(1, engine.open(protected, "right-password").pageCount)

        // A literal "/Encrypt" in page text must not turn a plain PDF into an encrypted one.
        val literal = makePdf("literal-encrypt.pdf", 1, "/Encrypt literal text")
        assertEquals(1, engine.open(literal).pageCount)

        // An encrypted file whose /Filter is not Standard must be refused before PDFBox sees it.
        val patched = String(protected.readBytes(), Charset.forName("ISO-8859-1"))
            .replace("/Standard", "/NoStdFlt").toByteArray(Charset.forName("ISO-8859-1"))
        val unsupported = File(dir, "unsupported-filter.pdf")
        unsupported.writeBytes(patched)
        assertTrue(expectError { engine.open(unsupported, "right-password") } is PdfEngineError.UnsupportedFeature)

        val broken = File(dir, "broken.pdf")
        broken.writeText("%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")
        assertTrue(expectError { engine.open(broken) } is PdfEngineError.MalformedDocument)

        assertTrue(engineWith(PdfEngineBudgets(maxInputBytes = 16)).use {
            expectError { it.open(plain) } is PdfEngineError.LimitExceeded
        })
        assertTrue(engineWith(PdfEngineBudgets(maxPages = 1)).use {
            expectError { it.open(makePdf("many.pdf", 2, "M")) } is PdfEngineError.LimitExceeded
        })
        assertTrue(engineWith(PdfEngineBudgets(maxRenderPixels = 1)).use { limited ->
            val ok = expectError {
                limited.redact(limited.open(plain), 0, emptyList(), File(dir, "px.pdf").path)
            } is PdfEngineError.LimitExceeded
            assertEquals("failed redact leaked a temp", 0, limited.stagedTempPaths().size)
            ok
        })
        assertTrue(engineWith(PdfEngineBudgets(wallTimeMs = -1)).use {
            expectError { it.open(plain) } is PdfEngineError.LimitExceeded
        })
        assertTrue(engineWith(PdfEngineBudgets(maxOutputBytes = 4)).use { limited ->
            val ok = expectError {
                limited.copyPages(limited.open(plain), intArrayOf(0), File(dir, "tiny.pdf").path)
            } is PdfEngineError.LimitExceeded
            assertEquals("failed save leaked a temp", 0, limited.stagedTempPaths().size)
            ok
        })
        val bigHandle = engine.open(plain)
        assertTrue(engineWith(PdfEngineBudgets(maxInputBytes = 4)).use { limited ->
            expectError {
                limited.overlay(bigHandle, 0, signaturePng(),
                    PdfRect(0f, 0f, 10f, 10f), "x", "y")
            } is PdfEngineError.LimitExceeded
        })
    }

    @Test
    fun plaintextTempsArePrivateAndRemoved() {
        val plain = makePdf("private.pdf", 1, "PUBLIC_ALPHA")
        val protected = File(dir, "private-protected.pdf")
        PdfEncryptor.encrypt(plain, protected, "user-fictional")
        val scoped = PdfBoxEngine(
            ApplicationProvider.getApplicationContext(), File(dir, "private-work"),
            enforceWorkerThread = false
        )
        val handle = scoped.open(protected, "user-fictional")
        val retainedPaths = scoped.stagedTempPaths()
        assertTrue(retainedPaths.isNotEmpty()) // the open() handle legitimately owns one decrypted temp
        assertTrue(retainedPaths.all {
            it.parentFile == ApplicationProvider.getApplicationContext<Context>().filesDir
        })
        scoped.saveEncrypted(handle, File(dir, "private-save.pdf"), "user-fictional")
        // Path equality, not just a count: one deleted plus one leaked must not pass.
        assertEquals("saveEncrypted leaked a plaintext temp", retainedPaths, scoped.stagedTempPaths())
        scoped.verifyText(protected, "user-fictional")
        assertEquals("verifyText leaked a plaintext temp", retainedPaths, scoped.stagedTempPaths())

        // A second engine must not sweep the first engine's live handle temp.
        val other = PdfBoxEngine(
            ApplicationProvider.getApplicationContext(), File(dir, "other-work"),
            enforceWorkerThread = false
        )
        assertEquals(retainedPaths, scoped.stagedTempPaths())
        other.close()
        assertEquals(retainedPaths, scoped.stagedTempPaths())

        scoped.cancel()
        assertTrue(expectError { scoped.verifyText(protected, "user-fictional") } is PdfEngineError.Cancelled)
        assertEquals(retainedPaths, scoped.stagedTempPaths())
        scoped.close()
        assertTrue(scoped.stagedTempPaths().isEmpty())

        val cancelled = PdfBoxEngine(
            ApplicationProvider.getApplicationContext(), File(dir, "cancel-work"),
            enforceWorkerThread = false
        )
        cancelled.cancel()
        assertTrue(expectError { cancelled.open(plain) } is PdfEngineError.Cancelled)
        cancelled.close()
        assertTrue(cancelled.stagedTempPaths().isEmpty())
    }

    @Test
    fun encryptedRoundTripUsesOnlyTheAppEncryptor() {
        val plain = makePdf("round.pdf", 1, "ENCRYPTED_MARKER_7777")
        val handle = engine.open(plain)
        val protected = File(dir, "round-protected.pdf")
        engine.saveEncrypted(handle, protected, "user-fictional")
        val decrypted = File(dir, "round-plain.pdf")
        decrypted.writeBytes(PdfDecryptor.decrypt(protected.readBytes(), "user-fictional"))
        assertTrue(oracleText(decrypted).contains("ENCRYPTED_MARKER_7777"))
        assertTrue(engine.verifyText(protected, "user-fictional").contains("ENCRYPTED_MARKER_7777"))
        assertTrue(expectError { engine.open(protected, "wrong-password") } is PdfEngineError.InvalidPassword)
    }

    @Test
    fun modernPlainPdfShapesOpenCopyAndKeepDigitalText() {
        val xrefStream = xrefStreamPdf(File(dir, "xref-stream.pdf"), "XREF_STREAM_MARKER")
        val incremental = incrementalUpdatePdf(
            makePdf("incremental-base.pdf", 2, "INCREMENTAL_MARKER"),
            File(dir, "incremental-update.pdf")
        )
        val hybrid = hybridXrefStmPdf(
            makePdf("hybrid-base.pdf", 1, "HYBRID_XREFSTM_MARKER"),
            File(dir, "hybrid-xrefstm.pdf")
        )
        val cases = listOf(
            Triple(xrefStream, "XREF_STREAM_MARKER", 1),
            Triple(incremental, "INCREMENTAL_MARKER", 2),
            Triple(hybrid, "HYBRID_XREFSTM_MARKER", 1)
        )
        for ((source, marker, pages) in cases) {
            // Harm prevented: a valid modern plain PDF must not be refused as encrypted.
            val handle = engine.open(source)
            assertEquals("${source.name} page count", pages, handle.pageCount)
            assertTrue("${source.name} lost text", engine.verifyText(source).contains(marker))
            val copy = engine.copyPages(handle, intArrayOf(0), File(dir, "copy-${source.name}").path)
            assertEquals(1, copy.pageCount)
            assertTrue("${source.name} copy lost digital text", oracleText(copy.file).contains(marker))
        }
    }

    @Test
    fun craftedTrailerStringCannotHideEncrypt() {
        // A '%' inside a trailer literal string is content, not a comment: a plain PDF opens.
        val plain = withTrailerEntry(
            makePdf("string-plain-base.pdf", 1, "PUBLIC_ALPHA"),
            File(dir, "string-plain.pdf"),
            "/Note (a% b) /Hex <25>"
        )
        assertEquals(1, engine.open(plain).pageCount)

        // Nested and escaped parens plus a hex string around /Encrypt must stay strings.
        val nestedEntry = "/Note (a(b\\)c% d) /Hex <25>"
        val nestedPlain = withTrailerEntry(
            makePdf("string-nested-base.pdf", 1, "PUBLIC_ALPHA"),
            File(dir, "string-nested-plain.pdf"),
            nestedEntry
        )
        assertEquals("Absent", probeOf(nestedPlain))
        assertEquals(1, engine.open(nestedPlain).pageCount)

        // With a non-standard /Filter the decryptor refuses; the strings must not hide /Encrypt
        // from the engine's probe, or encrypted bytes would reach PDFBox.
        val encrypted = File(dir, "string-encrypted.pdf")
        PdfEncryptor.encrypt(makePdf("string-protected-base.pdf", 1, "PUBLIC_ALPHA"), encrypted, "pw-fictional")
        val crafted = withTrailerEntry(encrypted, File(dir, "string-patched.pdf"), nestedEntry)
        crafted.writeBytes(iso(iso(crafted.readBytes()).replace("/Standard", "/NoStdFlt")))
        assertEquals("Present", probeOf(crafted))
        assertTrue(
            expectError { engine.open(crafted, "pw-fictional") } is PdfEngineError.UnsupportedFeature
        )
    }

    @Test
    fun decoyStartxrefAfterEofCannotBypassEncryption() {
        val encrypted = File(dir, "decoy-a.pdf")
        PdfEncryptor.encrypt(
            makePdf("decoy-a-base.pdf", 1, "PUBLIC_ALPHA", "INFO_MARKER_9"),
            encrypted, "pw-fictional"
        )
        val raw = iso(encrypted.readBytes())
        val plainObjectOffset = raw.indexOf("1 0 obj")
        assertTrue("fixture has no catalog object", plainObjectOffset > 0)

        // Decoy A: data after the real %%EOF, pointing at a plain dictionary object.
        val decoy = File(dir, "decoy-a-appended.pdf")
        decoy.writeBytes(iso(raw + "\nstartxref\n$plainObjectOffset\n"))
        assertFalse("a decoy after %%EOF looked plain", probeOf(decoy) == "Absent")
        val error = expectError { engine.open(decoy, "wrong-password") }
        assertTrue(
            error is PdfEngineError.InvalidPassword || error is PdfEngineError.MalformedDocument ||
                error is PdfEngineError.UnsupportedFeature
        )

        // Decoy B: the only startxref is replaced to point at the same plain object.
        val replaced = File(dir, "decoy-b-replaced.pdf")
        replaced.writeBytes(iso(replaceLastStartxref(raw, plainObjectOffset)))
        assertFalse("a replaced startxref looked plain", probeOf(replaced) == "Absent")
        val replacedError = expectError { engine.open(replaced, "wrong-password") }
        assertTrue(
            replacedError is PdfEngineError.InvalidPassword ||
                replacedError is PdfEngineError.MalformedDocument ||
                replacedError is PdfEngineError.UnsupportedFeature
        )
    }
}
