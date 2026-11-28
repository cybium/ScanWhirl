package com.scanwhirl.spike.pdf

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.security.Security

/**
 * Runtime, on-device measurement of a PDFBox-derived engine. All documents are fictional
 * and generated at runtime; no real student data and no network access.
 */
@RunWith(AndroidJUnit4::class)
class PdfEngineSpikeTest {

    private val results = LinkedHashMap<String, Any>()

    private fun value(key: String, v: Any) {
        results[key] = v
    }

    private fun check(name: String, ok: Boolean, detail: String = "") {
        results["check.$name"] = ok
        if (detail.isNotEmpty()) results["detail.$name"] = detail
    }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = SystemClock.elapsedRealtime()
        val result = block()
        return result to SystemClock.elapsedRealtime() - start
    }

    private fun heapUsed(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun pssKb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss.toLong()
    }

    private fun extract(file: File, password: String = "", page: Int = 0): String {
        return PDDocument.load(file, password).use { doc ->
            val stripper = PDFTextStripper()
            if (page > 0) {
                stripper.startPage = page
                stripper.endPage = page
            }
            stripper.getText(doc)
        }
    }

    private fun errorOf(block: () -> Unit): String {
        return try {
            block()
            "NO_ERROR"
        } catch (t: Throwable) {
            val message = t.message?.replace('\n', ' ')?.take(240) ?: ""
            "${t.javaClass.simpleName}: $message"
        }
    }

    /** Creates a fictional, selectable-text PDF; optionally with an embedded font, link and outline. */
    private fun makeTextPdf(
        file: File,
        pages: Int,
        marker: String,
        links: Boolean = false,
        embeddedFont: Boolean = false,
        metadataSecret: String? = null
    ): String {
        var fontDescription = "PDType1Font/Helvetica"
        PDDocument().use { doc ->
            val bodyFont = if (embeddedFont) {
                val roboto = File("/system/fonts/Roboto-Regular.ttf")
                if (roboto.exists()) {
                    try {
                        val font = PDType0Font.load(doc, roboto)
                        fontDescription = "PDType0Font/Roboto-Regular.ttf (embedded)"
                        font
                    } catch (t: Throwable) {
                        fontDescription = "PDType1Font/Helvetica (Roboto load failed: ${t.javaClass.simpleName})"
                        PDType1Font.HELVETICA
                    }
                } else {
                    fontDescription = "PDType1Font/Helvetica (no system Roboto)"
                    PDType1Font.HELVETICA
                }
            } else {
                PDType1Font.HELVETICA
            }

            repeat(pages) { index ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(bodyFont, 14f)
                    cs.newLineAtOffset(48f, 720f)
                    cs.showText("$marker page ${index + 1}")
                    cs.endText()
                    cs.beginText()
                    cs.setFont(PDType1Font.COURIER, 10f)
                    cs.newLineAtOffset(48f, 690f)
                    cs.showText("Fictional ScanWhirl engine spike content")
                    cs.endText()
                }
                if (links && index == 0) {
                    val uri = PDActionURI().apply { this.uri = "https://example.invalid/fictional-chapter" }
                    val link = PDAnnotationLink().apply {
                        rectangle = PDRectangle(48f, 650f, 220f, 18f)
                        action = uri
                    }
                    page.annotations = listOf(link)
                }
            }
            if (links) {
                val outline = PDDocumentOutline()
                val item = PDOutlineItem().apply {
                    title = "Fictional chapter one"
                    setDestination(doc.getPage(0))
                }
                outline.addLast(item)
                doc.documentCatalog.documentOutline = outline
            }
            metadataSecret?.let { secret ->
                doc.documentInformation = PDDocumentInformation().apply {
                    title = "Notice for $secret"
                    subject = secret
                    keywords = "fictional, $secret"
                }
            }
            doc.save(file)
        }
        return fontDescription
    }

    private fun signaturePng(): ByteArray {
        val bitmap = Bitmap.createBitmap(180, 90, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.rgb(18, 62, 138)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, 180f, 90f, paint)
        paint.color = Color.WHITE
        paint.textSize = 20f
        canvas.drawText("SPIKE SIGN", 14f, 52f, paint)
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        bitmap.recycle()
        return bytes.toByteArray()
    }

    @Test
    fun measureAllCapabilities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(context.cacheDir, "pdf-spike-work").apply { mkdirs() }
        val startRealtime = SystemClock.elapsedRealtime()

        value("library", "com.tom-roush:pdfbox-android:2.0.27.0 (upstream PDFBox 2.0.27)")
        value("device_sdk", android.os.Build.VERSION.SDK_INT)
        value("device_model", android.os.Build.MODEL)
        value("ram_size_mb", (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .memoryClass)

        val bcError = errorOf { Security.addProvider(BouncyCastleProvider()) }
        value("bouncycastle_provider_note", bcError)
        PDFBoxResourceLoader.init(context)
        value("pdfbox_resource_loader_ready", PDFBoxResourceLoader.isReady())

        val base = File(work, "base.pdf")
        val fontUsed = makeTextPdf(
            base, pages = 3, marker = "PUBLIC_ALPHA", links = true, embeddedFont = true
        )
        value("font_used", fontUsed)
        value("base_pdf_bytes", base.length())

        // Cold load = init + first parse/first text extraction in this process.
        val firstText = timed { extract(base) }
        value("cold_init_plus_first_open_and_extract_ms", SystemClock.elapsedRealtime() - startRealtime)
        value("first_open_extract_ms", firstText.second)
        check("base_text_selectable", firstText.first.contains("PUBLIC_ALPHA page 1"))
        check("base_links_and_outline", firstText.first.contains("PUBLIC_ALPHA page 1"))

        // 1. Page copy, preserving text.
        val copy = File(work, "copy.pdf")
        PDDocument.load(base).use { src ->
            PDDocument().use { out ->
                out.importPage(src.getPage(0))
                out.importPage(src.getPage(2))
                out.save(copy)
            }
        }
        val copyText = extract(copy)
        check("copy_pages", copyText.contains("PUBLIC_ALPHA page 1") &&
            copyText.contains("PUBLIC_ALPHA page 3") && !copyText.contains("page 2"))
        value("copy_pdf_bytes", copy.length())

        // 2. Reorder, rotate, delete; text must survive on the remaining page.
        val edited = File(work, "edited.pdf")
        // Rebuild in the order [page 3, page 1, page 2] from vetted page objects,
        // rotate the new first page, then delete the other two.
        PDDocument.load(base).use { src ->
            PDDocument().use { doc ->
                doc.importPage(src.getPage(2))
                doc.importPage(src.getPage(0))
                doc.importPage(src.getPage(1))
                doc.getPage(0).rotation = 90
                doc.removePage(1)
                doc.removePage(1)
                doc.save(edited)
            }
        }
        var editedOk = false
        var editedRotation = -1
        PDDocument.load(edited).use { doc ->
            editedRotation = doc.getPage(0).rotation
            editedOk = doc.numberOfPages == 1 &&
                PDFTextStripper().getText(doc).contains("PUBLIC_ALPHA page 3")
        }
        check("reorder_rotate_delete", editedOk && editedRotation == 90, "rotation=$editedRotation")

        // 3. Merge + 4. split, with independent text verification.
        val second = File(work, "second.pdf")
        makeTextPdf(second, pages = 2, marker = "PUBLIC_BETA")
        val merged = File(work, "merged.pdf")
        val mergeSmall = timed {
            PDFMergerUtility().apply {
                addSource(base)
                addSource(second)
                setDestinationStream(FileOutputStream(merged))
            }.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        }
        value("five_page_merge_ms", mergeSmall.second)
        val mergedText = extract(merged)
        check("merge_pages", mergedText.contains("PUBLIC_ALPHA page 3") &&
            mergedText.contains("PUBLIC_BETA page 2"))

        val splitA = File(work, "split-a.pdf")
        val splitB = File(work, "split-b.pdf")
        PDDocument.load(merged).use { src ->
            PDDocument().use { a ->
                PDDocument().use { b ->
                    for (i in 0 until 2) a.importPage(src.getPage(i))
                    for (i in 2 until 5) b.importPage(src.getPage(i))
                    a.save(splitA)
                    b.save(splitB)
                }
            }
        }
        check("split_pages", extract(splitA).contains("PUBLIC_ALPHA page 2") &&
            extract(splitB).contains("PUBLIC_BETA page 1") &&
            !extract(splitA).contains("PUBLIC_BETA"))

        // 5. Non-raster overlay: PNG signature + text + date appended to original content.
        val overlay = File(work, "overlay.pdf")
        PDDocument.load(base).use { doc ->
            val page = doc.getPage(0)
            val resources = page.resources ?: PDResources().also { page.resources = it }
            val image = PDImageXObject.createFromByteArray(doc, signaturePng(), "spike-signature.png")
            resources.put(COSName.getPDFName("SpikeSignature"), image)
            PDPageContentStream(doc, page, AppendMode.APPEND, true, true).use { cs ->
                cs.drawImage(image, 390f, 60f, 150f, 75f)
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 11f)
                cs.newLineAtOffset(48f, 620f)
                cs.showText("SIGNED BY SPIKE TEST 2026-11-28")
                cs.endText()
            }
            doc.save(overlay)
        }
        val overlayText = extract(overlay)
        check("overlay_text_and_original_text", overlayText.contains("PUBLIC_ALPHA page 1") &&
            overlayText.contains("SIGNED BY SPIKE TEST 2026-11-28"))
        var overlayHasImage = false
        PDDocument.load(overlay).use { doc ->
            overlayHasImage = doc.getPage(0).resources.xObjectNames
                .contains(COSName.getPDFName("SpikeSignature"))
        }
        check("overlay_is_image_xobject", overlayHasImage)

        // 6. Redaction: only the redacted page is rasterised, all secrets are gone.
        val secret = "TOP_SECRET_ALPHA_4821"
        val publicMarker = "UNREDACTED_DIGITAL_PAGE_9001"
        val redactSource = File(work, "redact-source.pdf")
        PDDocument().use { doc ->
            val secretPage = PDPage(PDRectangle.A4)
            doc.addPage(secretPage)
            PDPageContentStream(doc, secretPage).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 16f)
                cs.newLineAtOffset(48f, 720f)
                cs.showText(secret)
                cs.endText()
            }
            val uri = PDActionURI().apply { uri = "https://example.invalid/$secret" }
            secretPage.annotations = listOf(PDAnnotationLink().apply {
                rectangle = PDRectangle(48f, 690f, 300f, 18f)
                action = uri
            })
            val digitalPage = PDPage(PDRectangle.A4)
            doc.addPage(digitalPage)
            PDPageContentStream(doc, digitalPage).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 14f)
                cs.newLineAtOffset(48f, 720f)
                cs.showText(publicMarker)
                cs.endText()
            }
            doc.documentInformation = PDDocumentInformation().apply {
                title = "Notice $secret"
                subject = secret
            }
            doc.save(redactSource)
        }
        val safe = File(work, "redacted-safe.pdf")
        PDDocument.load(redactSource).use { src ->
            PDDocument().use { out ->
                val bitmap = PDFRenderer(src).renderImageWithDPI(0, 144f, ImageType.RGB)
                val canvas = Canvas(bitmap)
                canvas.drawRect(
                    0f, 0f, bitmap.width.toFloat(), bitmap.height * 0.45f,
                    Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
                )
                val png = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
                bitmap.recycle()
                val box = PDRectangle(src.getPage(0).cropBox.cosArray)
                val imageOnlyPage = PDPage(box)
                out.addPage(imageOnlyPage)
                val image = PDImageXObject.createFromByteArray(out, png.toByteArray(), "redacted.png")
                PDPageContentStream(out, imageOnlyPage).use { cs ->
                    cs.drawImage(image, 0f, 0f, box.width, box.height)
                }
                // Second page is untouched digital content.
                out.importPage(src.getPage(1))
                out.save(safe) // full rewrite, never saveIncremental
            }
        }
        val safeRaw = String(safe.readBytes(), Charset.forName("ISO-8859-1"))
        val eofCount = Regex("%%EOF").findAll(safeRaw).count()
        val safeText = extract(safe)
        val pageZeroText = extract(safe, page = 1)
        value("redacted_pdf_bytes", safe.length())
        value("redacted_eof_count", eofCount)
        check("redacted_text_absent", !safeText.contains(secret) && !pageZeroText.contains(secret))
        check("redacted_bytes_absent", !safeRaw.contains(secret))
        check("redacted_metadata_clean", !safeRaw.contains("/Names") && !safeRaw.contains("/Annots"))
        check("redacted_single_revision", eofCount == 1 && !safeRaw.contains("/Prev"))
        check("redacted_other_page_still_digital", safeText.contains(publicMarker))
        check("redacted_page_image_only", pageZeroText.trim().isEmpty())
        PDDocument.load(safe).use { doc ->
            val info = doc.documentInformation
            check(
                "redacted_info_dictionary_clean",
                !(info.title ?: "").contains(secret) && !(info.subject ?: "").contains(secret)
            )
        }

        // 7. Encrypted open/save, wrong password and malformed input must not crash.
        val encrypted = File(work, "encrypted.pdf")
        val encMarker = "ENCRYPTED_MARKER_7777"
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 14f)
                cs.newLineAtOffset(48f, 720f)
                cs.showText(encMarker)
                cs.endText()
            }
            doc.protect(StandardProtectionPolicy("owner-fictional", "user-fictional", AccessPermission()))
            doc.save(encrypted)
        }
        check("encrypted_open_with_password", extract(encrypted, "user-fictional").contains(encMarker))
        val decrypted = File(work, "decrypted.pdf")
        PDDocument.load(encrypted, "user-fictional").use { src ->
            PDDocument().use { out ->
                out.importPage(src.getPage(0))
                out.save(decrypted)
            }
        }
        check("encrypted_page_copy", extract(decrypted).contains(encMarker))
        val reencrypted = File(work, "reencrypted.pdf")
        PDDocument.load(decrypted).use { doc ->
            doc.protect(StandardProtectionPolicy("owner-two", "user-two", AccessPermission()))
            doc.save(reencrypted)
        }
        check("encrypted_resave", extract(reencrypted, "user-two").contains(encMarker))
        val wrongPasswordError = errorOf { PDDocument.load(encrypted, "not-the-password").use {} }
        val oldPasswordError = errorOf { PDDocument.load(reencrypted, "user-fictional").use {} }
        value("wrong_password_error", wrongPasswordError)
        value("old_password_error", oldPasswordError)
        check("wrong_password_rejected", wrongPasswordError != "NO_ERROR")
        check("old_password_rejected", oldPasswordError != "NO_ERROR")

        val malformed = File(work, "malformed.pdf")
        malformed.writeText("%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")
        val malformedError = errorOf { PDDocument.load(malformed).use {} }
        value("malformed_error", malformedError)
        check("malformed_rejected", malformedError != "NO_ERROR")

        // 8. 200 + 200 page merge on the low-RAM profile.
        val largeA = File(work, "large-a.pdf")
        val largeB = File(work, "large-b.pdf")
        val generation = timed {
            makeTextPdf(largeA, 200, "LARGE_A")
            makeTextPdf(largeB, 200, "LARGE_B")
        }
        value("large_pdf_generation_ms", generation.second)
        value("large_pdf_a_bytes", largeA.length())
        value("large_pdf_b_bytes", largeB.length())
        val beforeHeap = heapUsed()
        val beforePss = pssKb(context)
        val largeMerged = File(work, "large-merged.pdf")
        val bigMerge = timed {
            PDFMergerUtility().apply {
                addSource(largeA)
                addSource(largeB)
                setDestinationStream(FileOutputStream(largeMerged))
            }.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        }
        val afterHeap = heapUsed()
        val afterPss = pssKb(context)
        value("merge_200plus200_ms", bigMerge.second)
        value("merge_200plus200_heap_delta_bytes", afterHeap - beforeHeap)
        value("merge_200plus200_pss_delta_kb", afterPss - beforePss)
        value("merged_400_pdf_bytes", largeMerged.length())
        var largeOk = false
        PDDocument.load(largeMerged).use { doc ->
            largeOk = doc.numberOfPages == 400 &&
                PDFTextStripper().let { s ->
                    s.startPage = 1; s.endPage = 1
                    s.getText(doc).contains("LARGE_A page 1")
                } &&
                PDFTextStripper().let { s ->
                    s.startPage = 400; s.endPage = 400
                    s.getText(doc).contains("LARGE_B page 200")
                }
        }
        check("merge_400_pages_text", largeOk)

        // Results go to the app's external files dir and to internal files for `run-as` pull.
        val json = JSONObject()
        for ((key, v) in results) json.put(key, v)
        val report = json.toString()
        val external = File(context.getExternalFilesDir(null), "pdf_spike_results.json")
        external.parentFile?.mkdirs()
        external.writeText(report)
        File(context.filesDir, "pdf_spike_results.json").writeText(report)
        Log.i("PDF_SPIKE_RESULT", report)

        val failed = results.filter { it.key.startsWith("check.") && it.value == false }.keys.toList()
        assertTrue("Spike capability checks failed: $failed", failed.isEmpty())
    }
}
