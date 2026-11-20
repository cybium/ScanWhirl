package com.scanwhirl.app.corpus

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.exifinterface.media.ExifInterface
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.ScanRepository.ImportDecision
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.tools.PdfAExport
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.launchTool
import com.scanwhirl.app.ui.tools.PassportActivity
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScanWhirlApp::class)
class CorpusToolsTest {
    private class Mismatch(message: String) : AssertionError(message)
    private class NeedsDevice(message: String) : Exception(message)
    private fun verify(ok: Boolean, detail: String) { if (!ok) throw Mismatch(detail) }
    private fun equal(expected: Any?, actual: Any?, label: String) =
        verify(expected == actual, "$label: expected $expected; actual $actual")

    private data class Case(val id: String, val paths: List<String>, val expected: String)
    private enum class Status { PASS, FAIL, ERROR, DEVICE_ONLY }
    private data class Artifact(val name: String, val sha: String, val bytes: Long)
    private data class PerInput(val status: Status, val detail: String, val trace: String, val artifacts: List<Artifact>)
    private data class Row(
        val tool: String, val input: String, val sourceSha: String, val status: Status, val settings: String,
        val expected: String, val detail: String, val trace: String, val ms: Long,
        val artifacts: List<Artifact>
    )

    private lateinit var corpus: File
    private lateinit var output: File
    private lateinit var expectedHashes: Map<String, String>
    private var hostImportProblem: Throwable? = null
    private val rows = mutableListOf<Row>()
    private val app get() = ApplicationProvider.getApplicationContext<ScanWhirlApp>()
    private val revision get() = System.getProperty("scanwhirl.corpus.revision", "unrecorded")

    private fun readMatrix(): List<Case> {
        val text = File(corpus, "TOOL_MATRIX.md").readText()
        val heading = Regex("(?m)^### `([a-z_]+)`[^\\r\\n]*$")
        val entries = heading.findAll(text).toList()
        val cases = entries.mapIndexed { index, h ->
            val block = text.substring(h.range.last + 1, entries.getOrNull(index + 1)?.range?.first ?: text.length)
            val files = Regex("(?m)^- `([^`]+/[^`]+)`").findAll(block).map { it.groupValues[1] }.toList()
            val expected = Regex("(?m)^\\*\\*Expected verifiable outcome:\\*\\* (.+)$")
                .find(block)?.groupValues?.get(1) ?: error("Missing expectation for ${h.value}")
            check(files.distinct().size == files.size && files.size >= 10) { "Missing or repeated inputs for ${h.value}" }
            files.forEach(::input)
            Case(h.groupValues[1], files, expected)
        }
        val registry = ToolRegistry.all.map { it.id }.toSet()
        equal(registry + setOf("duplicate_pages", "images_to_pdf"), cases.map { it.id }.toSet(), "matrix/registry inventory")
        equal(cases.size, cases.map { it.id }.distinct().size, "unique matrix tools")
        return cases
    }

    private fun input(path: String): File = File(corpus, path).canonicalFile.also {
        require(it.toPath().startsWith(corpus.toPath()) && it.isFile) { "Missing/unsafe fixture: $path" }
    }

    private inner class Fixture(val folder: File) {
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(folder, "files").apply { mkdirs() }
            override fun getCacheDir() = File(folder, "cache").apply { mkdirs() }
        }
        val repo = ScanRepository(context)
        val artifacts = mutableListOf<Artifact>()
        val perInput = mutableListOf<PerInput>()
        var detail = ""
        var settings = "Prepare fresh test Library fixture; import all pages"
        var usedIndependentStaging = false
        fun out(name: String) = File(folder, name)
        fun record(file: File) {
            verify(file.isFile && file.length() > 0, "output missing or empty: ${file.name}")
            artifacts += Artifact(file.relativeTo(folder).invariantSeparatorsPath, sha(file), file.length())
        }
        suspend fun load(file: File): ScanDocument = if (file.extension == "pdf") {
            requireNotNull(repo.importPdfAsScanPlanned(Uri.fromFile(file), file.nameWithoutExtension, onPlan = { ImportDecision.All }))
        } else {
            repo.saveScan(listOf(Uri.fromFile(file)), null, file.nameWithoutExtension)
        }
        fun pdf(doc: ScanDocument, pages: Int, record: Boolean = true) {
            if (record) record(doc.pdfFile)
            equal(pages, doc.pageCount, "library page count")
            Loader.loadPDF(doc.pdfFile).use { parsed ->
                equal(pages, parsed.numberOfPages, "PDF page count")
                if (record) for ((index, page) in parsed.pages.withIndex()) {
                    val resources = page.resources
                    val rasters = resources.xObjectNames.map { resources.getXObject(it) }.filterIsInstance<PDImageXObject>()
                    equal(1, rasters.size, "embedded images on page ${index + 1}")
                    val raster = rasters.single()
                    equal(sha(doc.pageFiles[index]), raster.cosObject.createRawInputStream().use(::sha),
                        "embedded page ${index + 1} matches its library JPEG")
                }
            }
        }
        suspend fun tracked(file: File): ScanDocument {
            if (file.extension != "pdf") return load(file)
            val doc = if (hostImportProblem == null) load(file) else {
                // Robolectric cannot execute PDFium on some hosts. Still exercise the actual
                // downstream Library operation, with a labelled fixture at the page-JPEG seam.
                // Do not substitute this for import_pdf: that adapter always calls the real import.
                usedIndependentStaging = true
                val dir = Files.createTempDirectory(folder.toPath(), "input-").toFile()
                val uris = Loader.loadPDF(file).use { source ->
                    val renderer = PDFRenderer(source).apply { isSubsamplingAllowed = true }
                    (0 until source.numberOfPages).map { i ->
                        val box = source.getPage(i).cropBox
                        val scale = minOf(150f / 72f, 2048f / maxOf(box.width, box.height))
                        val raster = render(renderer, i, scale)
                        val page = File(dir, "$i.jpg")
                        raster.writeJpeg(page)
                        Uri.fromFile(page)
                    }
                }
                repo.saveScan(uris, null, file.nameWithoutExtension)
            }
            pdf(doc, count(file), record = false)
            return doc
        }
    }

    @Test
    fun matrix() = runBlocking {
        check(System.getProperty("scanwhirl.corpus") == "true") { "Use tools/corpus/run_corpus.ps1" }
        corpus = File(requireNotNull(System.getProperty("scanwhirl.corpus.path"))).canonicalFile
        output = File(System.getProperty("scanwhirl.corpus.out") ?: File(corpus, "RESULTS").path)
        check(output.isDirectory || output.mkdirs())
        val allCases = readMatrix() + Case("import_pdf_negative", File(corpus, "pdf_broken_truncated")
            .listFiles { f -> f.isFile && f.extension == "pdf" }!!.sortedBy { it.name }
            .map { it.relativeTo(corpus).invariantSeparatorsPath },
            "Reject as damaged/empty without publishing a document; a recoverable PDF must open with all original pages.")
        val selection = System.getProperty("scanwhirl.corpus.only").orEmpty().split(',').map(String::trim)
            .filter(String::isNotEmpty).toSet()
        check(selection.all { selected -> allCases.any { it.id == selected } }) { "Unknown selected corpus tool(s): $selection" }
        val cases = if (selection.isEmpty()) allCases else allCases.filter { it.id in selection }
        val shaCell = Regex(",([a-f0-9]{64}),\\d+,")
        expectedHashes = File(corpus, "SOURCES.csv").useLines { lines ->
            lines.drop(1).mapNotNull { row ->
                val hash = shaCell.find(row)?.groupValues?.get(1) ?: return@mapNotNull null
                row.substringBefore(',').removeSurrounding("\"") to hash
            }.toMap()
        }
        val csv = File(output, "results.csv")
        val report = File(output, "RESULTS.md")
        report.writeText("# Corpus run in progress\n\nRevision `$revision`, started ${Instant.now()}.\n")
        val probe = Files.createTempDirectory(app.cacheDir.toPath(), "corpus-probe-").toFile()
        try {
            Fixture(probe).load(input("pdf_text_multipage/meeting_minutes_1p.pdf"))
        } catch (e: LinkageError) {
            hostImportProblem = e
        } catch (e: PdfImport.ImportException) {
            if (e.reason == PdfImport.Reason.EMPTY || generateSequence<Throwable>(e) { it.cause }.any {
                    it is LinkageError || it.stackTrace.any { frame -> frame.className.startsWith("android.graphics.pdf.") }
                }) hostImportProblem = e
        } finally {
            probe.deleteRecursively()
        }
        var finished = false
        csv.bufferedWriter().use { writer ->
            writer.appendLine("tool,input,input_sha256,status,settings,expected,detail,stack_head,elapsed_ms,outputs,sha256,bytes")
            try {
                for (case in cases) {
                    val ids = if (case.id in grouped) listOf(case.paths) else case.paths.map(::listOf)
                    for (paths in ids) {
                        for (row in exercise(case, paths)) {
                            rows += row
                            writer.appendLine(listOf(
                                row.tool, row.input, row.sourceSha, row.status.name.replace('_', '-'), row.settings, row.expected,
                                row.detail, row.trace, row.ms.toString(), row.artifacts.joinToString(";") { it.name },
                                row.artifacts.joinToString(";") { it.sha }, row.artifacts.joinToString(";") { it.bytes.toString() }
                            ).joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
                        }
                        writer.flush()
                    }
                }
                equal(cases.sumOf { it.paths.size }, rows.size, "reported rows")
                finished = true
            } finally {
                report.writeText(summary(finished))
            }
        }
        verify(rows.none { it.status == Status.FAIL || it.status == Status.ERROR },
            "Corpus found failures/errors; see ${report.path}")
    }

    private val grouped = setOf("merge", "zip", "batch_tools")
    private suspend fun exercise(case: Case, paths: List<String>): List<Row> {
        val start = System.nanoTime()
        val work = Files.createTempDirectory(app.cacheDir.toPath(), "corpus-").toFile()
        val fixture = Fixture(work)
        val files = paths.map(::input)
        val before = files.map(::sha)
        var status = Status.PASS
        var trace = ""
        try {
            for ((index, path) in paths.withIndex()) {
                val expected = expectedHashes[path] ?: error("No SOURCES.csv SHA-256 for $path")
                check(expected == before[index]) { "Fixture differs from SOURCES.csv: $path" }
            }
            val steps = deviceSteps(case.id) ?: if (case.id == "unlock" && files.single().name in
                setOf("aes256_owner_only.pdf", "aes256_print_denied.pdf")) {
                "Tools > Unlock PDF > Device file. Choose the owner-only fixture; check no open-password prompt, " +
                    "the restrictions remain or are explicitly reported, and no misleading 'password removed' result appears."
            } else null
            if (steps != null) {
                status = Status.DEVICE_ONLY
                fixture.settings = steps
                fixture.detail = "Not run on a phone."
            } else {
                fixture.runTool(case.id, files)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeedsDevice) {
            status = Status.DEVICE_ONLY
            fixture.settings += "; ${e.message}"
            fixture.detail = "No device result."
        } catch (e: LinkageError) {
            status = Status.ERROR
            fixture.detail = "Host linkage error: ${e.javaClass.simpleName}: ${e.message}"
            trace = e.stackTraceToString().lineSequence().take(10).joinToString("\n")
        } catch (e: AssertionError) {
            status = if (e is Mismatch) Status.FAIL else Status.ERROR
            fixture.detail = "${e.javaClass.simpleName}: ${e.message}"
            trace = e.stackTraceToString().lineSequence().take(10).joinToString("\n")
        } catch (e: Exception) {
            status = Status.ERROR
            fixture.detail = "${e.javaClass.simpleName}: ${e.message}"
            trace = e.stackTraceToString().lineSequence().take(10).joinToString("\n")
        } finally {
            work.deleteRecursively()
        }
        if (files.map(::sha) != before) {
            status = Status.FAIL
            fixture.detail = "DATA LOSS: original corpus bytes changed. ${fixture.detail}"
        }
        if (fixture.usedIndependentStaging) {
            fixture.detail += " Input setup used PDFBox-rendered JPEGs plus repository Save Scan; Android PDF import not covered."
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        return paths.mapIndexed { i, path ->
            val individual = if (status == Status.PASS) fixture.perInput.getOrNull(i) else null
            Row(case.id, path, before[i], individual?.status ?: status, fixture.settings, case.expected,
                listOf(fixture.detail, individual?.detail.orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
                individual?.trace ?: trace, ms, individual?.artifacts ?: fixture.artifacts.toList())
        }
    }

    private suspend fun Fixture.runTool(id: String, files: List<File>) {
        val f = files.first()
        when (id) {
            "organize_pages", "batch_rename" -> {
                settings = "ToolRegistry launch with entitlement granted; test dispatch only"
                val events = mutableListOf<Int?>()
                val tool = ToolRegistry.all.single { it.id == id }
                object : ToolHost {
                    override fun open(tool: SwTool) { events += null }
                    override fun comingSoon(tool: SwTool) { events += tool.soonNote }
                }.launchTool(tool)
                equal(listOf(if (id == "organize_pages") R.string.soon_organize_pages else R.string.soon_batch_rename),
                    events, "Coming soon dispatch")
                detail = "Coming-soon routing only, not a conversion."
            }
            "import_pdf" -> {
                settings = "Library import; all pages, default 150 DPI"
                val doc = load(f)
                pdf(doc, count(f))
                equal(doc.id, repo.listDocuments().single().id, "published document")
                // First image is what the Library thumbnail decodes; compare actual page content.
                Loader.loadPDF(f).use { original ->
                    val renderer = PDFRenderer(original).apply { isSubsamplingAllowed = true }
                    for ((index, file) in doc.pageFiles.withIndex()) {
                        similar(render(renderer, index, 0.55f), image(file),
                            if (index == 0) "first-page thumbnail" else "imported page ${index + 1}")
                    }
                }
            }
            "import_pdf_negative" -> {
                settings = "Import PDF; All; observe typed failure and empty library"
                val doc = try { load(f) } catch (e: PdfImport.ImportException) {
                    verify(e.reason in setOf(PdfImport.Reason.DAMAGED, PdfImport.Reason.EMPTY), "unexpected rejection: ${e.reason}")
                    if (hostImportProblem != null && e.cause != null) {
                        throw IllegalStateException("Native renderer is unavailable; this rejection is not verified", e)
                    }
                    verify(repo.listDocuments().isEmpty(), "invalid input published a document")
                    detail = "Gracefully rejected (${e.reason})."
                    return
                }
                val pages = try { count(f) } catch (_: java.io.IOException) { 0 }
                verify(pages > 0, "damaged PDF was published")
                pdf(doc, pages)
                detail = "Recoverable according to independent PDFBox parser; page count matched."
            }
            "merge" -> {
                settings = "Library merge in matrix order; one 66-page output"
                val docs = files.map { tracked(it) }
                val pages = docs.flatMap { it.pageFiles }
                val digests = pages.map(::sha)
                val doc = repo.mergeDocuments(docs.map { it.id }, "Corpus merge")
                pdf(doc, 66)
                pageFiles(doc, digests)
                equal(digests, pages.map(::sha), "original library pages")
                equal(docs.size + 1, repo.listDocuments().size, "library after merge")
                detail = "Shared invocation and output; exact ordered page-image bytes match library inputs."
            }
            "zip" -> {
                settings = "One ordered ZIP; first name includes Hindi"
                val docs = files.map { tracked(it) }
                repo.renameDocument(docs.first().id, "परीक्षण")
                val archive = out("export.zip")
                ZipExporter.export(docs.map { requireNotNull(repo.getDocument(it.id)) }, archive)
                record(archive)
                ZipFile(archive).use { zip ->
                    val entries = zip.entries().toList()
                    equal(docs.size, entries.size, "ZIP entry count")
                    equal("परीक्षण.pdf", entries.first().name, "UTF-8 name")
                    equal(docs.map { sha(it.pdfFile) }, entries.map { zip.getInputStream(it).use(::sha) }, "ZIP PDF bytes/order")
                }
                detail = "Shared invocation and output."
            }
            "batch_tools" -> {
                settings = "Batch compress JPEG 65%, max side 1200px; one missing ID midway"
                val docs = files.map { tracked(it) }
                val original = docs.map { sha(it.pdfFile) }
                val ids = docs.map { it.id }.toMutableList().apply { add(5, "absent-corpus-id") }
                val items = repo.runBatch(ids, ScanRepository.BatchAction.Compress(65, 1200))
                equal(ids, items.map { it.docId }, "batch result order")
                verify(!items[5].ok && !items[5].reason.isNullOrBlank(), "missing document not reported")
                for ((item, doc) in items.filterIndexed { i, _ -> i != 5 }.zip(docs)) {
                    val index = artifacts.size
                    var itemStatus = Status.PASS
                    var itemTrace = ""
                    var itemDetail: String
                    try {
                        verify(item.ok, "batch rejected document: ${item.reason}")
                        val result = requireNotNull(repo.getDocument(requireNotNull(item.outputId)))
                        pdf(result, doc.pageCount)
                        itemDetail = "Measured ${doc.pdfFile.length()} → ${result.pdfFile.length()} bytes."
                        equal(doc.pageFiles.size, result.pageFiles.size, "batch output page count")
                    } catch (e: Mismatch) {
                        itemStatus = Status.FAIL
                        itemDetail = e.message.orEmpty()
                        itemTrace = e.stackTraceToString().lineSequence().take(10).joinToString("\n")
                    } catch (e: Exception) {
                        itemStatus = Status.ERROR
                        itemDetail = "${e.javaClass.simpleName}: ${e.message}"
                        itemTrace = e.stackTraceToString().lineSequence().take(10).joinToString("\n")
                    }
                    perInput += PerInput(itemStatus, itemDetail, itemTrace, artifacts.drop(index))
                }
                equal(original, docs.map { sha(it.pdfFile) }, "batch originals")
                detail = "Shared scenario time; PDF output mapped to each successful input. Result UI not exercised."
            }
            "split", "extract", "duplicate_pages", "insert_pages", "folders" -> editLibrary(id, f)
            "compress", "resize", "remove_metadata", "id_card" -> transform(id, f)
            "protect", "unlock", "pdfa" -> secure(id, f)
            "passport" -> passport(f)
            "vault" -> vault(f)
            "pdf_to_images", "long_image", "to_powerpoint", "to_word", "to_excel", "to_text", "searchable_pdf" ->
                export(id, f)
            "sign", "add_text", "draw", "highlight", "watermark", "page_numbers", "stamp", "redact" ->
                mark(id, f)
            else -> error("No corpus adapter for $id")
        }
    }

    private suspend fun Fixture.editLibrary(id: String, f: File) {
        val doc = tracked(f)
        val before = sha(doc.pdfFile)
        val pages = doc.pageFiles.map(::sha)
        when (id) {
            "folders" -> {
                settings = "Create, assign, remove Corpus folder"
                verify(repo.createFolder("Corpus"), "could not create folder")
                repo.setFolder(listOf(doc.id), "Corpus")
                equal("Corpus", repo.listDocuments().single().folder, "folder assignment")
                repo.deleteFolder("Corpus")
                equal(null, repo.listDocuments().single().folder, "folder removal")
                pdf(doc, pages.size)
            }
            "split" -> {
                settings = "Split 1-1 and 2-end; reject 0, N+1, 2-1"
                val parts = repo.splitByRange(doc.id, PageRanges.parse("1-1, 2-", pages.size))
                equal(2, parts.size, "number of parts")
                pdf(parts[0], 1)
                pdf(parts[1], pages.size - 1)
                equal(pages, parts.flatMap { it.pageFiles }.map(::sha), "split page order/content")
                val published = repo.listDocuments().map { it.id }.toSet()
                for (bad in listOf("0", "${pages.size + 1}", "2-1")) {
                    val error = runCatching { repo.splitByRange(doc.id, PageRanges.parse(bad, pages.size)) }.exceptionOrNull()
                    verify(error is IllegalArgumentException, "invalid range accepted or crashed: $bad ($error)")
                }
                equal(published, repo.listDocuments().map { it.id }.toSet(), "invalid ranges publish nothing")
            }
            "extract" -> {
                val picks = listOf(1, 3).filter { it in pages.indices }
                settings = "Extract 1-based ${picks.map { it + 1 }}; then All"
                for (indices in listOf(picks, pages.indices.toList())) {
                    val out = repo.extractPages(doc.id, indices)
                    pdf(out, indices.size)
                    pageFiles(out, indices.map(pages::get))
                }
            }
            "duplicate_pages", "insert_pages" -> {
                val duplicate = id == "duplicate_pages"
                val other = if (duplicate) doc.pageFiles.first() else tracked(
                    input("pdf_text_multipage/meeting_minutes_1p.pdf")).pageFiles.first()
                val positions = if (duplicate) listOf(1) else listOf(0, pages.size / 2, pages.size).distinct()
                settings = "Insert at zero-based $positions; save as new; discard draft"
                for (pos in positions) {
                    repo.draftInsertPages(doc.id, listOf(other), pos)
                    val out = repo.saveDraftAsNew(doc.id, "Corpus $id")
                    pdf(out, pages.size + 1)
                    pageFiles(out, pages.toMutableList().apply { add(pos, sha(other)) })
                    repo.discardDraft(doc.id)
                }
                repo.draftInsertPages(doc.id, listOf(other), positions.first())
                val published = repo.listDocuments().map { it.id }.toSet()
                repo.discardDraft(doc.id)
                equal(published, repo.listDocuments().map { it.id }.toSet(), "cancel leaves Library unchanged")
                equal(pages, repo.draftPages(doc.id).map(::sha), "cancel restores draft baseline")
            }
        }
        equal(before, sha(doc.pdfFile), "original library PDF")
    }

    private fun pageFiles(doc: ScanDocument, expected: List<String>) =
        equal(expected, doc.pageFiles.map(::sha), "exact page-image order/content")

    // These two result screens resolve the repo from the Application. Keep their real callbacks,
    // but route reads/writes to this case's isolated files and restore the default immediately.
    private suspend fun <T> Fixture.withUiRepository(block: suspend () -> T): T {
        val field = ScanWhirlApp::class.java.getDeclaredField("repository").apply { isAccessible = true }
        val old = app.repository
        field.set(app, repo)
        try { return block() } finally { field.set(app, old) }
    }

    private suspend fun awaitUi(label: String, ready: () -> Boolean) {
        val stop = System.nanoTime() + 12_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (ready()) return
            delay(15)
        } while (System.nanoTime() < stop)
        throw Mismatch("Timed out waiting for $label")
    }

    private suspend fun Fixture.passport(f: File) {
        settings = "Passport photo; return gallery test URI; accept default crop; test 35:45 ratio without assuming a DPI"
        withUiRepository {
            val controller = Robolectric.buildActivity(PassportActivity::class.java, PassportActivity.intent(app)).setup()
            try {
                val activity = controller.get()
                val shadow = shadowOf(activity)
                val picker = requireNotNull(shadow.nextStartedActivityForResult) { "Passport gallery picker did not open" }
                shadow.receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(f)))
                awaitUi("passport gallery decode") {
                    activity.findViewById<View>(R.id.toolSetupProgress).visibility == View.GONE
                }
                verify(!activity.isFinishing, "gallery portrait could not be decoded")
                activity.findViewById<View>(R.id.toolCropApply).performClick()
                awaitUi("passport result") { repo.listDocuments().isNotEmpty() }
                val doc = repo.listDocuments().single()
                pdf(doc, 1)
                val output = image(doc.pageFiles.single())
                verify(abs(output.width - output.height * 35.0 / 45.0) <= 2.0,
                    "crop does not match 35:45 ratio (${output.width}×${output.height})")
                detail = "Emitted ${output.width}×${output.height} px. No fixed pixel preset is assumed; face centering needs visual review."
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }

    private suspend fun Fixture.vault(f: File) {
        settings = "VaultStore; setup confirmation; fake hardware key provider; wrong and valid passcode; lock and reopen"
        val source = tracked(f)
        val digest = sha(source.pdfFile)
        val pass = 104242.toString() // fictional fixture passcode; never a device credential
        val vault = VaultStore(context, repo, keys = FakeVaultKeys(), iterations = 2000)
        try {
            vault.setup(pass)
            vault.markConfirmationPassed()
            vault.lock()
            verify(!vault.unlockWithPasscode(pass.reversed()).ok, "wrong passcode opened the Vault")
            equal(digest, sha(source.pdfFile), "failed unlock leaves Library intact")
            verify(vault.unlockWithPasscode(pass).ok, "correct passcode did not open the Vault")
            val entry = vault.addDocument(source.id)
            equal(source.pageCount, entry.pageCount, "Vault page count")
            verify(repo.getDocument(source.id) == null && !source.pdfFile.exists(), "plaintext remains in Library")
            verify(repo.listTrash().isEmpty(), "plaintext remains in Trash after Vault move")
            val opened = vault.openDocument(entry.id)
            record(opened.pdfFile)
            equal(digest, sha(opened.pdfFile), "Vault round-trip PDF")
            equal(source.pageCount, count(opened.pdfFile), "independent reopened PDF page count")
            vault.lock()
            verify(!opened.dir.exists(), "plaintext cache survived lock")
            detail = "Encrypted storage path tested; real device credentials, cancel and export confirmation remain for G."
        } finally {
            vault.lock()
        }
    }

    private suspend fun Fixture.transform(id: String, f: File) {
        when (id) {
            "id_card" -> {
                settings = "A4 one-page front/top, matching back/bottom; preconfirmed complete card crop"
                val stem = f.nameWithoutExtension.substringBeforeLast('_')
                val front = File(f.parentFile, "${stem}_front.jpg")
                val back = File(f.parentFile, "${stem}_back.jpg")
                val doc = repo.createDocument("Corpus ID", listOf(IdCard.compose(front.readBytes(), back.readBytes())))
                pdf(doc, 1)
                val rendered = image(doc.pageFiles.single())
                equal(1240 to 1754, rendered.width to rendered.height, "A4 page dimensions")
                for ((isFront, side) in listOf(true to front, false to back)) {
                    val box = if (isFront) IdCard.frontRect() else IdCard.backRect()
                    val clip = rendered.getSubimage(box.left.roundToInt(), box.top.roundToInt(), box.width().toInt(), box.height().toInt())
                    similar(image(side), clip, "ID side ${side.name}")
                }
                detail = "Paired composition tested; interactive crop and specimen watermark readability need device review."
            }
            "resize" -> {
                settings = "Resize to 300 px wide, keep aspect, JPEG, 96 DPI"
                val bytes = f.inputStream().use(ImageTools::readPageBytes)
                val result = try { ImageTools.resize(bytes, ImageTools.ResizeOptions(width = 300)) }
                catch (e: IllegalArgumentException) {
                    if (f.extension.lowercase() !in setOf("heic", "tiff", "tif", "bmp")) throw e
                    detail = "Optional platform format rejected: ${e.message}"
                    return
                }
                val file = out("resized.jpg").apply { writeBytes(result.bytes) }
                record(file)
                val img = image(file)
                equal(300, img.width, "emitted JPEG width")
                val dimensions = androidx.exifinterface.media.ExifInterface(f).let { exif ->
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(f.path, bounds)
                    if (exif.rotationDegrees % 180 == 0) bounds.outWidth to bounds.outHeight
                    else bounds.outHeight to bounds.outWidth
                }
                val height = 300.0 * dimensions.second / dimensions.first
                verify(abs(img.height - height) <= 1, "aspect: expected height within 1px of $height; actual ${img.height}")
            }
            "compress" -> {
                settings = "Library target 102400 bytes; no Replace; compare final PDF byte count"
                val doc = tracked(f)
                val original = sha(doc.pdfFile)
                val target = 102400L
                val result = repo.compressScanToTarget(doc.id, target)
                pdf(result, doc.pageCount)
                equal(original, sha(doc.pdfFile), "original library PDF")
                for ((i, pair) in doc.pageFiles.zip(result.pageFiles).withIndex()) {
                    similar(image(pair.first), image(pair.second), "compressed page ${i + 1}")
                }
                val actual = result.pdfFile.length()
                withUiRepository {
                    val intent = ToolResultActivity.intent(app, "compress", docIds = listOf(result.id),
                        targetBytes = target, beforeBytes = doc.pdfFile.length(), sourceKept = true)
                    val controller = Robolectric.buildActivity(ToolResultActivity::class.java, intent).setup()
                    try {
                        val activity = controller.get()
                        val actualLine = activity.findViewById<TextView>(R.id.toolResultSummary).text.toString().lineSequence().first()
                        val expectedLine = activity.getString(
                            if (actual <= target) R.string.compress_result_under_limit else R.string.compress_result_over_limit,
                            StatusLine.formatBytes(actual), StatusLine.formatBytes(target)
                        )
                        equal(expectedLine, actualLine, "target result wording from measured bytes")
                    } finally {
                        controller.pause().stop().destroy()
                    }
                }
                detail = "Measured $actual bytes, target $target, library input ${doc.pdfFile.length()} bytes. " +
                    (if (actual <= target) "Target met. " else "Target missed. ") + "Actual result reported it correctly."
                Loader.loadPDF(f).use { source ->
                    if (PDFTextStripper().getText(source).isNotBlank()) {
                        Loader.loadPDF(result.pdfFile).use {
                            verify(PDFTextStripper().getText(it).isNotBlank(),
                                "selectable source text lost in compressed PDF (target=$target, actual=$actual)")
                        }
                    }
                }
            }
            "remove_metadata" -> {
                settings = "Library Remove metadata; preserve pixels/orientation and PDF text"
                val doc = tracked(f)
                val original = sha(doc.pdfFile)
                val result = repo.removeMetadata(doc.id)
                pdf(result, doc.pageCount)
                equal(original, sha(doc.pdfFile), "original library file")
                for ((source, clean) in doc.pageFiles.zip(result.pageFiles)) {
                    similar(image(source), image(clean), "clean page")
                    val exif = ExifInterface(clean)
                    for (tag in listOf("Make", "Model", "GPSLatitude", "GPSLongitude", "DateTime", "DateTimeOriginal")) {
                        equal(null, exif.getAttribute(tag), "EXIF $tag")
                    }
                    equal(0, exif.rotationDegrees, "output EXIF rotation")
                }
                Loader.loadPDF(result.pdfFile).use { clean ->
                    val info = clean.documentInformation
                    verify(listOf(info.author, info.producer, info.creator, info.title).all { it.isNullOrBlank() },
                        "document Info fields remain")
                    equal(null, clean.documentCatalog.metadata, "XMP")
                    if (f.extension == "pdf") Loader.loadPDF(f).use { source ->
                        equal(PDFTextStripper().getText(source), PDFTextStripper().getText(clean), "PDF text unchanged")
                    }
                }
            }
        }
    }

    private suspend fun Fixture.secure(id: String, f: File) {
        when (id) {
            "protect" -> {
                settings = "Library Protect PDF; fictional password; fresh readers with none, wrong, and correct"
                val pw = "corpus-test-value"
                val doc = tracked(f)
                val originalText = Loader.loadPDF(doc.pdfFile).use { PDFTextStripper().getText(it) }
                val protected = repo.protectPdf(doc.id, pw)
                record(protected.pdfFile)
                for (bad in listOf("", "not-the-corpus-value")) {
                    val rejected = runCatching { Loader.loadPDF(protected.pdfFile, bad).close() }.exceptionOrNull()
                    verify(rejected is InvalidPasswordException, "independent reader did not require the expected password")
                }
                Loader.loadPDF(protected.pdfFile, pw).use {
                    verify(it.isEncrypted, "output is not encrypted")
                    equal(5, it.encryption.version, "AES-256 encryption handler version")
                    equal(256, it.encryption.length, "AES key bits")
                    equal(doc.pageCount, it.numberOfPages, "protected page count")
                    equal(originalText, PDFTextStripper().getText(it), "library text retained")
                }
                detail = "Library protection path; UI minimum-length validation and external-file Keep both not exercised."
            }
            "unlock" -> {
                settings = "Production PdfDecryptor; exact fictional user password from PASSWORDS.txt, then wrong password"
                val block = File(f.parentFile, "PASSWORDS.txt").readText().replace("\r\n", "\n")
                    .substringAfter("\n${f.name}\n", "")
                val token = Regex("(?m)^  user password\\s*:\\s*([^\\r\\n]+)").find(block)?.groupValues?.get(1)?.trim()
                    ?: error("No fixture password entry")
                val pw = if (token == "(empty)") "" else token
                val bytes = f.readBytes()
                if (pw.isEmpty()) {
                    Loader.loadPDF(f).use { verify(it.isEncrypted && !it.currentAccessPermission.isOwnerPermission,
                        "owner-only fixture should open with restricted user access") }
                    detail = "Owner-only input opens with restrictions; removal/UX not exercised."
                    return
                }
                val plain = try { PdfDecryptor.decrypt(bytes, pw) } catch (e: PdfDecryptor.Unsupported) {
                    throw Mismatch("Valid PDF is unsupported by the in-app offline decryptor: ${e.message}")
                }
                val result = out("unlocked.pdf").apply { writeBytes(plain) }
                record(result)
                val wrong = runCatching { PdfDecryptor.decrypt(bytes, "not-the-corpus-value") }.exceptionOrNull()
                verify(wrong is PdfDecryptor.WrongPassword, "wrong password not classified ($wrong)")
                Loader.loadPDF(f, pw).use { source ->
                    Loader.loadPDF(result).use {
                        verify(!it.isEncrypted, "output remains encrypted")
                        equal(source.numberOfPages, it.numberOfPages, "unlocked page count")
                        equal(PDFTextStripper().getText(source), PDFTextStripper().getText(it), "unlocked text")
                    }
                }
            }
            "pdfa" -> {
                settings = "PDF/A-2b default export from Library import"
                val doc = tracked(f)
                val out = PdfAExport(context, repo).export(doc.id)
                record(out)
                Loader.loadPDF(out).use {
                    verify(!it.isEncrypted, "PDF/A encrypted")
                    equal(doc.pageCount, it.numberOfPages, "PDF/A page count")
                    val catalog = it.documentCatalog
                    verify(catalog.outputIntents.any { intent ->
                        intent.destOutputIntent?.createInputStream()?.use { stream ->
                            val header = stream.readNBytes(128)
                            header.size == 128 && String(header, 16, 4, Charsets.US_ASCII) == "RGB " &&
                                String(header, 36, 4, Charsets.US_ASCII) == "acsp"
                        } == true
                    }, "PDF/A sRGB ICC stream missing or invalid")
                    val xml = catalog.metadata?.createInputStream()?.use(::parseXml)
                        ?: throw Mismatch("PDF/A XMP missing")
                    val ns = "http://www.aiim.org/pdfa/ns/id/"
                    equal("2", xml.getElementsByTagNameNS(ns, "part").item(0)?.textContent, "PDF/A part")
                    equal("B", xml.getElementsByTagNameNS(ns, "conformance").item(0)?.textContent, "PDF/A level")
                    for (page in it.pages) {
                        val resources = page.resources
                        val images = resources.xObjectNames.map { name -> resources.getXObject(name) }.filterIsInstance<PDImageXObject>()
                        verify(images.none { img -> img.cosObject.containsKey(org.apache.pdfbox.cos.COSName.SMASK) },
                            "PDF/A page image contains a soft mask")
                        for (name in resources.extGStateNames) {
                            val state = resources.getExtGState(name)
                            verify((state.strokingAlphaConstant ?: 1f) == 1f &&
                                (state.nonStrokingAlphaConstant ?: 1f) == 1f, "PDF/A page uses transparency")
                        }
                    }
                }
                detail = "PDFBox structure/XMP/ICC checks only, not a veraPDF conformance result."
            }
        }
    }

    private fun parseXml(stream: InputStream): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(stream)

    private fun xml(zip: ZipFile, entry: String): Document {
        val part = zip.getEntry(entry) ?: throw Mismatch("Missing OpenXML part: $entry")
        return zip.getInputStream(part).use(::parseXml)
    }

    private fun texts(document: Document, localName: String): List<String> {
        val nodes = document.getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).map { nodes.item(it).textContent }
    }

    private suspend fun Fixture.export(id: String, f: File) {
        settings = "Import all; ${when (id) {
            "pdf_to_images" -> "JPG ZIP, quality 100"
            "long_image" -> "default long-image splitting"
            "to_powerpoint" -> "one source page per slide"
            "to_word" -> "text only; seed OCR geometry from PDFBox vector text"
            "to_excel" -> "table export; seed OCR geometry from PDFBox vector text"
            else -> "seed OCR text from PDFBox vector text"
        }}"
        val doc = tracked(f)
        val original = sha(doc.pdfFile)
        when (id) {
            "pdf_to_images" -> {
                val zip = repo.pdfToImages(doc.id, ScanRepository.ImageExportFormat.JPG, true, out("pages.zip"))
                record(zip)
                ZipFile(zip).use { z ->
                    val entries = z.entries().toList()
                    equal(doc.pageCount, entries.size, "exported image count")
                    for ((i, entry) in entries.withIndex()) {
                        val decoded = z.getInputStream(entry).use(::image)
                            ?: throw Mismatch("Unrecognized exported image ${entry.name}")
                        similar(image(doc.pageFiles[i]), decoded, "export image #${i + 1}")
                    }
                }
            }
            "long_image" -> {
                val exports = repo.exportLongImage(doc.id, out("long-image"))
                exports.forEach(::record)
                equal(1, exports.size, "long image file count")
                val long = image(exports.single())
                var y = 0
                for ((i, source) in doc.pageFiles.withIndex()) {
                    val page = image(source)
                    if (i > 0) y += 12 // document separator, measured in output pixels
                    val scaled = ((page.height.toLong() * long.width + page.width - 1) / page.width).toInt()
                    verify(y + scaled <= long.height + 2, "page ${i + 1} absent or clipped")
                    val height = minOf(scaled, long.height - y)
                    verify(height > 0, "page ${i + 1} absent")
                    similar(page, long.getSubimage(0, y, long.width, height), "long image segment ${i + 1}")
                    y += height
                }
                verify(abs(y - long.height) <= doc.pageCount, "unexpected trailing pixels")
            }
            "to_powerpoint" -> {
                val out = repo.exportPowerPoint(doc.id, out("export.pptx"))
                record(out)
                ZipFile(out).use { zip ->
                    xml(zip, "[Content_Types].xml")
                    xml(zip, "_rels/.rels")
                    val presentation = xml(zip, "ppt/presentation.xml")
                    equal(doc.pageCount, presentation.getElementsByTagNameNS("*", "sldId").length, "PPT slide count")
                    val size = presentation.getElementsByTagNameNS("*", "sldSz").item(0) as Element
                    val slideWidth = size.getAttribute("cx").toLong()
                    val slideHeight = size.getAttribute("cy").toLong()
                    val aspect = slideWidth.toDouble() / slideHeight
                    verify(listOf(16.0 / 9, 4.0 / 3).any { abs(it - aspect) < .001 }, "unexpected PPT slide ratio $aspect")
                    for (i in 1..doc.pageCount) {
                        val slide = xml(zip, "ppt/slides/slide$i.xml")
                        val rels = xml(zip, "ppt/slides/_rels/slide$i.xml.rels")
                        val imageRel = rels.getElementsByTagNameNS("*", "Relationship")
                        val target = (0 until imageRel.length).map { imageRel.item(it) as org.w3c.dom.Element }
                            .single { it.getAttribute("Type").endsWith("/image") }.getAttribute("Target")
                        val embedded = zip.getEntry("ppt/" + target.removePrefix("../"))
                            ?: throw Mismatch("Missing slide image $target")
                        val decoded = zip.getInputStream(embedded).use(::image)
                            ?: throw Mismatch("Unreadable embedded slide image")
                        similar(image(doc.pageFiles[i - 1]), decoded, "slide $i embedded page")
                        val picture = slide.getElementsByTagNameNS("*", "pic").item(0) as Element
                        val ext = picture.getElementsByTagNameNS("*", "ext").item(0) as Element
                        val width = ext.getAttribute("cx").toLong()
                        val height = ext.getAttribute("cy").toLong()
                        verify(width in 1..slideWidth && height in 1..slideHeight &&
                            abs(width.toDouble() / height - decoded.width.toDouble() / decoded.height) < .01,
                            "slide $i does not fit its picture without stretching")
                    }
                }
            }
            "to_word", "to_excel", "to_text" -> {
                val seeded = seedText(f, doc)
                detail = "Post-recognition export only; seeded ${seeded.count { it.isNotBlank() }}/${doc.pageCount} pages with PDFBox text."
                if (id == "to_word") {
                    val result = try { repo.exportWord(doc.id, out("export.docx")) }
                    catch (e: ScanRepository.NoTextForExportException) {
                        verify(seeded.all { it.isBlank() }, "nonempty supplied vector text was refused")
                        detail += " Image-only fixture: exporter returned a typed no-text error; no OCR attempted."
                        return
                    }
                    record(result)
                    ZipFile(result).use { zip ->
                        xml(zip, "[Content_Types].xml")
                        xml(zip, "_rels/.rels")
                        val text = texts(xml(zip, "word/document.xml"), "t").joinToString("\n")
                        verify(text.isNotBlank(), "empty Word text")
                        if (f.name == "column_markers_1p.pdf") {
                            // The matrix abbreviates the labels; the corpus generator prints
                            // LEFT-COLUMN-ALPHA etc. Check the literals actually in the source.
                            val positions = listOf("LEFT-COLUMN-ALPHA", "LEFT-COLUMN-BETA", "LEFT-COLUMN-GAMMA",
                                "RIGHT-COLUMN-DELTA", "RIGHT-COLUMN-EPSILON", "RIGHT-COLUMN-ZETA").map(text::indexOf)
                            verify(positions.all { it >= 0 } && positions.zipWithNext().all { (a, b) -> a < b },
                                "Word column marker order: positions=$positions")
                        }
                        knownText(f, text)
                    }
                } else if (id == "to_excel") {
                    val result = out("export.xlsx")
                    repo.exportExcel(doc.id, result)
                    record(result)
                    ZipFile(result).use { zip ->
                        xml(zip, "[Content_Types].xml")
                        xml(zip, "_rels/.rels")
                        val book = xml(zip, "xl/workbook.xml")
                        val sheets = book.getElementsByTagNameNS("*", "sheet").length
                        equal(doc.pageCount, sheets, "XLSX sheet count")
                        val values = (1..sheets).map { xml(zip, "xl/worksheets/sheet$it.xml") }
                        val text = values.flatMap { texts(it, "t") + texts(it, "v") }.joinToString("\n")
                        verify("#REF!" !in text && "#VALUE!" !in text, "formula error in Excel")
                        val tokens = when (f.name) {
                            "tax_invoice_1p.pdf" -> listOf("6714.40")
                            "bank_statement_3p.pdf" -> listOf("24500.00")
                            "marksheet_1p.pdf" -> listOf("513")
                            else -> emptyList()
                        }
                        for (token in tokens) {
                            verify(values.flatMap { texts(it, "v") }
                                .any { it.toDoubleOrNull() == token.toDouble() }, "missing numeric cell $token")
                        }
                        val rows = values.flatMap { sheet ->
                            val nodes = sheet.getElementsByTagNameNS("*", "row")
                            (0 until nodes.length).map { nodes.item(it) as Element }
                        }
                        when (f.name) {
                            "marksheet_1p.pdf" -> verifyMarksheetPercent(zip, values)
                            "bank_statement_3p.pdf" -> equal(45, rows.count {
                                val cells = it.getElementsByTagNameNS("*", "c")
                                val textCells = (0 until cells.length).map { i -> cells.item(i).textContent.trim() }
                                textCells.any(dateInStatement::matches) &&
                                    textCells.none { cell -> cell == "Opening Balance" || cell == "Closing Balance" }
                            }, "bank XLSX transaction rows")
                            "tax_invoice_1p.pdf" -> equal(7, invoiceItems.count { it in text }, "invoice XLSX line items")
                            "stock_register_6p.pdf" -> equal(120, Regex("""Item\s+\d{3}\b""").findAll(text)
                                .map { it.value }.toSet().size, "distinct stock items in XLSX")
                            "wide_table_10col_1p.pdf" -> equal(14, rows.count {
                                val cells = it.getElementsByTagNameNS("*", "c")
                                cells.length == 10 && (cells.item(0).textContent.trim().toIntOrNull() ?: 0) in 1..14
                            }, "wide-table data rows with 10 cells")
                        }
                    }
                } else {
                    // The editor export is a device flow; exercise the production save path with its
                    // page-ordered repository values rather than writing the file ourselves.
                    val text = doc.pageFiles.mapNotNull { repo.ocrDoc(doc.id)[it.name] }.joinToString("\n")
                    if (text.isBlank()) throw NeedsDevice("Open fixture > Text > Read (choose source language) > Export text. Check output is UTF-8 and nonempty, or the app explains why it could not read text.")
                    val emitted = out("export.txt")
                    val media = FakeMediaStore().also {
                        it.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
                        ShadowContentResolver.registerProviderInternal("media", it)
                    }
                    try {
                        val saved = DownloadSaver.saveText(context, text, "Corpus text.txt")
                        context.contentResolver.openInputStream(saved.uri)!!.use { stream ->
                            emitted.outputStream().use { stream.copyTo(it) }
                        }
                    } finally {
                        media.close()
                    }
                    record(emitted)
                    val readback = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(emitted.readBytes())).toString()
                    equal(text, readback, "UTF-8 export and page order")
                    knownText(f, readback)
                }
            }
        }
        equal(original, sha(doc.pdfFile), "source library PDF stays unchanged")
    }

    private fun knownText(f: File, text: String) {
        val words = when (f.name) {
            "accented_names_2p.pdf" -> listOf("José Müller", "Ānand Rāo")
            "municipal_circular_2p.pdf" -> listOf("Municipal Corporation")
            "marksheet_1p.pdf" -> listOf("Rohan Gupta", "513", "85.50%")
            "rent_agreement_3p.pdf" -> listOf("Receipt of Deposit")
            else -> emptyList()
        }
        val normalized = java.text.Normalizer.normalize(text.replace(Regex("\\s+"), " "), java.text.Normalizer.Form.NFC)
        for (word in words) verify(word in normalized, "export is missing '$word'")
    }

    private val dateInStatement = Regex("""\d{2}/01/2026""")
    private val invoiceItems = listOf("A4 copier paper", "Ball pen", "File folder", "Stapler",
        "Highlighter set", "Notebook", "Whiteboard marker")

    private fun verifyMarksheetPercent(zip: ZipFile, sheets: List<Document>) {
        val cells = sheets.flatMap { sheet ->
            val nodes = sheet.getElementsByTagNameNS("*", "c")
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
        val percentCell = cells.firstOrNull { cell ->
            val value = cell.getElementsByTagNameNS("*", "v").item(0)?.textContent?.toDoubleOrNull()
            value != null && abs(value - .855) < 1e-8 && cell.getAttribute("t") in setOf("", "n")
        } ?: throw Mismatch("Missing numeric percentage .855 for 85.50%")
        val styles = xml(zip, "xl/styles.xml")
        val formats = styles.getElementsByTagNameNS("*", "cellXfs").item(0) as Element
        val index = percentCell.getAttribute("s").toIntOrNull() ?: 0
        val formatId = (formats.getElementsByTagNameNS("*", "xf").item(index) as? Element)?.getAttribute("numFmtId")
        val custom = styles.getElementsByTagNameNS("*", "numFmt")
        val formatCode = (0 until custom.length).map { custom.item(it) as Element }
            .firstOrNull { it.getAttribute("numFmtId") == formatId }?.getAttribute("formatCode")
        verify(formatId == "10" || formatCode == "0.00%",
            "85.50% is stored as .855, but Excel display format ${formatCode ?: formatId} does not show two decimal places")
    }

    // This is a fixture adapter, not a substitute OCR engine; no inference from pixels.
    private fun Fixture.seedText(f: File, doc: ScanDocument): List<String> =
        Loader.loadPDF(f).use { original ->
            doc.pageFiles.mapIndexed { i, page ->
                val bounds = image(page)
                val crop = original.getPage(i).cropBox
                val sx = bounds.width / crop.width
                val sy = bounds.height / crop.height
                val lines = mutableListOf<OcrLine>()
                val seen = object : PDFTextStripper() {
                    override fun writeString(text: String, positions: MutableList<TextPosition>) {
                        if (text.isBlank() || positions.isEmpty()) return
                        // Split very large gaps into cells/independent column lines, not paragraphs.
                        val pieces = mutableListOf(mutableListOf<TextPosition>())
                        for (p in positions) {
                            val prev = pieces.last().lastOrNull()
                            if (prev != null && p.xDirAdj - prev.xDirAdj - prev.widthDirAdj > 18f) pieces.add(mutableListOf())
                            pieces.last().add(p)
                        }
                        for (part in pieces) {
                            val label = if (pieces.size == 1) text else part.joinToString("") { it.unicode }
                            if (label.isBlank()) continue
                            lines += OcrLine(label.trim(),
                                (part.minOf { it.xDirAdj } * sx).roundToInt(),
                                (part.minOf { it.yDirAdj - it.heightDir } * sy).roundToInt(),
                                (part.maxOf { it.xDirAdj + it.widthDirAdj } * sx).roundToInt(),
                                (part.maxOf { it.yDirAdj } * sy).roundToInt())
                        }
                    }
                }.apply { startPage = i + 1; endPage = i + 1; sortByPosition = true }
                seen.getText(original)
                val text = lines.joinToString("\n") { it.text }
                repo.ocrStore.putPage(doc.id, page.name, text, OcrPage(bounds.width, bounds.height, lines))
                text
            }
        }

    private suspend fun Fixture.mark(id: String, f: File) {
        val box = PageStamp.Box(.08f, .08f, .38f, .18f)
        val stroke = PageStamp.Stroke(listOf(PageStamp.Point(.1f, .1f), PageStamp.Point(.8f, .7f), PageStamp.Point(.9f, .2f)))
        val token = if (f.parentFile.name.contains("hindi")) "परीक्षण" else "CORPUS CHECK"
        val mark = when (id) {
            "sign" -> PageStamp.Mark.Signature(listOf(stroke), box, Color.BLUE)
            "draw" -> PageStamp.Mark.Freehand(listOf(PageStamp.Stroke(
                listOf(PageStamp.Point(.10f, .10f), PageStamp.Point(.22f, .16f), PageStamp.Point(.34f, .11f)))), Color.BLUE)
            "highlight" -> PageStamp.Mark.Highlight(box)
            "add_text" -> PageStamp.Mark.Text(token, box)
            "watermark" -> PageStamp.Mark.Watermark(token)
            "page_numbers" -> PageStamp.Mark.PageNumber(format = "Corpus page %d of %d")
            "stamp" -> PageStamp.Mark.Badge(PageStamp.BadgeKind.APPROVED, box, "20 Nov 2026")
            else -> null
        }
        settings = if (id == "redact") "Manual opaque black redaction; vector-sensitive line boxes, whole page for unverifiable raster coordinates"
            else "$mark; keep original; ${if (id in setOf("watermark", "page_numbers")) "all pages" else "page 1"}"
        val doc = tracked(f)
        var before = sha(doc.pdfFile)
        if (id == "redact") {
            val table = File(f.parentFile, "REDACTION_GROUND_TRUTH.txt").readText()
            val values = Regex("(?m)^  [^:\\n]+: ([^\\r\\n]+)").findAll(table).map { it.groupValues[1].trim() }
                .flatMap { it.split(Regex("\\s{2,}IFSC:\\s*")) }.filter { it.length >= 6 }.toSet()
            verify(values.isNotEmpty(), "missing redaction ground truth")
            seedText(f, doc)
            // Give vector cases a real, independently checked text layer to strip, rather
            // than accepting an output with no text when its Library input already had none.
            repo.makeSearchable(doc.id)
            before = sha(doc.pdfFile)
            val geometry = repo.ocrGeometry(doc.id)
            val sourceText = Loader.loadPDF(doc.pdfFile).use { PDFTextStripper().getText(it) }
            val visibleTokens = values.filter { token -> geometry.values.any { page -> page.lines.any { token in it.text } } }
            if (visibleTokens.isNotEmpty()) check(visibleTokens.any { it in sourceText }) {
                "redaction setup did not produce a selectable source token"
            }
            val fullPage = PageStamp.Box(0f, 0f, 1f, 1f)
            val edits = doc.pageFiles.mapIndexed { i, page ->
                val geo = geometry[page.name]
                val boxes = geo?.lines.orEmpty().filter { line ->
                    values.any { it in line.text }
                }.map { line ->
                    requireNotNull(geo)
                    PageStamp.Box(
                        ((line.left - 6f) / geo.width).coerceIn(0f, 1f),
                        ((line.top - 12f) / geo.height).coerceIn(0f, 1f),
                        ((line.right + 6f) / geo.width).coerceIn(0f, 1f),
                        ((line.bottom + 8f) / geo.height).coerceIn(0f, 1f)
                    )
                }
                val texts = geo?.lines.orEmpty().joinToString(" ") { it.text }
                val compact = texts.filter(Char::isLetterOrDigit)
                val incomplete = values.any { value ->
                    value.filter(Char::isLetterOrDigit).let { it.isNotBlank() && it in compact && it !in texts }
                }
                val pageBoxes = if (boxes.isEmpty() || incomplete ||
                    f.name in setOf("mixed_text_and_scan.pdf", "hindi_contact_slip.pdf"))
                    listOf(fullPage) else boxes
                PageStamp.PageEdits(i, pageBoxes.map { PageStamp.Mark.Redact(it) })
            }
            val redacted = repo.stampDocument(doc.id, edits, "Corpus redaction")
            pdf(redacted, doc.pageCount)
            Loader.loadPDF(redacted.pdfFile).use { removed ->
                val contents = PDFTextStripper().getText(removed) + " " +
                    removed.documentInformation.cosObject.toString() + " " +
                    removed.documentCatalog.metadata?.createInputStream()?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                verify(values.none { it in contents }, "sensitive ground truth remains in text or metadata")
                val renderer = PDFRenderer(removed)
                for (edit in edits) {
                    val resources = removed.getPage(edit.pageIndex).resources
                    val raster = resources.xObjectNames.map { resources.getXObject(it) }.filterIsInstance<PDImageXObject>()
                    equal(1, raster.size, "raster backing for redacted page")
                    val images = listOf(render(renderer, edit.pageIndex, .8f),
                        HostImage(requireNotNull(PDImageXObject::class.java.getMethod("getImage").invoke(raster.single()))))
                    for (mask in edit.marks) {
                        val region = (mask as PageStamp.Mark.Redact).box
                        for (page in images) for (i in 1..7) for (j in 1..7) {
                            val x = ((region.left + (region.right - region.left) * i / 8f) * page.width).toInt()
                            val y = ((region.top + (region.bottom - region.top) * j / 8f) * page.height).toInt()
                            val pixel = page.getRGB(x, y)
                            verify((0..2).all { (pixel shr (8 * it) and 255) <= 24 },
                                "redaction region not opaque black in rendered/embedded image on page ${edit.pageIndex + 1}")
                        }
                    }
                }
            }
            verify(redacted.pdfFile.parentFile.listFiles().orEmpty().none { it.extension == "orig" },
                "unredacted .orig survives beside the export")
            detail = "PDFBox text, metadata and rendered mask samples checked. Raster-only inputs use whole-page masking; narrow raster selections and warning UX need device review."
        } else {
            val global = id == "watermark" || id == "page_numbers"
            val edits = (if (global) doc.pageFiles.indices.toList() else listOf(0))
                .map { PageStamp.PageEdits(it, listOf(requireNotNull(mark))) }
            val result = repo.stampDocument(doc.id, edits, "Corpus $id")
            pdf(result, doc.pageCount)
            for (i in doc.pageFiles.indices) {
                val old = image(doc.pageFiles[i])
                val new = image(result.pageFiles[i])
                equal(old.width to old.height, new.width to new.height, "page ${i + 1} dimensions")
                if (global || i == 0) {
                    verify(sha(doc.pageFiles[i]) != sha(result.pageFiles[i]), "mark absent on page ${i + 1}")
                    var changed = 0
                    for (y in 0 until 96) for (x in 0 until 96) {
                        val a = old.getRGB(x * old.width / 96, y * old.height / 96)
                        val b = new.getRGB(x * new.width / 96, y * new.height / 96)
                        if ((0..2).any { channel -> abs((a shr (8 * channel) and 255) - (b shr (8 * channel) and 255)) > 36 }) changed++
                    }
                    verify(changed >= 2, "mark is not visible on page ${i + 1}")
                } else {
                    equal(sha(doc.pageFiles[i]), sha(result.pageFiles[i]), "untouched page ${i + 1}")
                }
            }
            Loader.loadPDF(result.pdfFile).use { out ->
                val text = PDFTextStripper().getText(out)
                if (id == "add_text") verify(token in text, "visible added text is not extractable")
                if (id == "page_numbers") verify((1..doc.pageCount).all { "Corpus page $it of ${doc.pageCount}" in text },
                    "visible page numbering is not extractable")
                if (id in setOf("sign", "highlight", "watermark")) Loader.loadPDF(f).use { source ->
                    val old = PDFTextStripper().getText(source)
                    if (old.isNotBlank()) verify(text.contains(old.trim()), "original selectable text lost after $id (import + markup)")
                }
            }
        }
        equal(before, sha(doc.pdfFile), "original library file")
    }

    private fun count(file: File): Int = Loader.loadPDF(file).use { it.numberOfPages }
    private fun sha(file: File): String = file.inputStream().use(::sha)
    private fun sha(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    // AGP compiles against the Android Java API surface, which omits java.desktop.
    // Robolectric executes in the host JDK; keep the reflection at this test-only AWT boundary.
    private class HostImage(private val value: Any) {
        val width: Int = awt.getMethod("getWidth").invoke(value) as Int
        val height: Int = awt.getMethod("getHeight").invoke(value) as Int
        fun getRGB(x: Int, y: Int): Int = rgb.invoke(value, x, y) as Int
        fun getSubimage(x: Int, y: Int, w: Int, h: Int) = HostImage(sub.invoke(value, x, y, w, h))
        fun writeJpeg(file: File) {
            check(write.invoke(null, value, "JPEG", file) == true) { "Host JPEG encoder missing" }
            flush.invoke(value)
        }

        companion object {
            private val awt = Class.forName("java.awt.image.BufferedImage")
            private val io = Class.forName("javax.imageio.ImageIO")
            private val int = Int::class.javaPrimitiveType!!
            private val rgb = awt.getMethod("getRGB", int, int)
            private val sub = awt.getMethod("getSubimage", int, int, int, int)
            private val flush = awt.getMethod("flush")
            private val write = io.getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, File::class.java)
            private val read = io.getMethod("read", InputStream::class.java)
            fun decode(input: InputStream): HostImage? = read.invoke(null, input)?.let(::HostImage)
        }
    }

    private val renderMethod by lazy {
        PDFRenderer::class.java.getMethod("renderImage", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
    }
    private fun render(renderer: PDFRenderer, index: Int, scale: Float): HostImage =
        HostImage(requireNotNull(renderMethod.invoke(renderer, index, scale)))

    private fun image(input: InputStream): HostImage? = HostImage.decode(input)
    private fun image(file: File): HostImage =
        file.inputStream().use(::image) ?: throw Mismatch("Independent ImageIO cannot decode ${file.name}")

    private fun similar(expected: HostImage, actual: HostImage, label: String) {
        var referenceInk = 0
        var actualInk = 0
        var delta = 0L
        for (y in 0 until 96) for (x in 0 until 96) {
            fun gray(img: HostImage): Int {
                val p = img.getRGB(x * img.width / 96, y * img.height / 96)
                return ((p shr 16 and 255) + (p shr 8 and 255) + (p and 255)) / 3
            }
            val a = gray(expected)
            val b = gray(actual)
            if (a < 210) referenceInk++
            if (b < 210) actualInk++
            delta += abs(a - b)
        }
        verify(abs(expected.width.toDouble() / expected.height - actual.width.toDouble() / actual.height) < 0.03,
            "$label: aspect mismatch ${expected.width}×${expected.height} vs ${actual.width}×${actual.height}")
        verify(delta < 96 * 96 * 34L && (referenceInk < 25 || actualInk >= referenceInk / 4),
            "$label: image differs or is blank (reference ink=$referenceInk, actual=$actualInk)")
    }

    private fun summary(finished: Boolean): String = buildString {
        appendLine("# ScanWhirl corpus results")
        appendLine("\nRevision `$revision`; completed all rows: $finished. Generated ${Instant.now()}.")
        System.getProperty("scanwhirl.corpus.only")?.takeIf { it.isNotBlank() }?.let {
            appendLine("Selected rerun of `$it` only; not a full corpus run.")
        }
        appendLine("Matrix SHA-256: `${sha(File(corpus, "TOOL_MATRIX.md"))}`.")
        hostImportProblem?.let { error ->
            val cause = generateSequence(error) { it.cause }.last()
            appendLine("\nAndroid import probe could not run: `${cause.javaClass.simpleName}: ${cause.message}`. " +
                "Where explicitly labelled, downstream checks started at a PDFBox-rendered JPEG fixture saved by the repository. " +
                "Do not count those checks as verification of Android import.")
        }
        appendLine("\nPASS covers the stated host checks only. ERROR is an exception (may be a host or fixture limitation). " +
            "DEVICE-ONLY means no device result. Grouped elapsed times/output digests may repeat across shared inputs. " +
            "Output digests are of real files before cleanup; this run does not retain exports.")
        appendLine("\n| Tool | PASS | FAIL | ERROR | DEVICE-ONLY |")
        appendLine("|---|---:|---:|---:|---:|")
        for ((tool, group) in rows.groupBy { it.tool }) {
            appendLine("| $tool | ${Status.entries.joinToString(" | ") { s -> group.count { it.status == s }.toString() }} |")
        }
        appendLine("| **Total** | ${Status.entries.joinToString(" | ") { s -> rows.count { it.status == s }.toString() }} |")
        appendLine("\n## Triage candidates (up to 15 distinct observed mismatches, highest impact first)")
        val mismatches = rows.filter { it.status == Status.FAIL }.groupBy {
            it.tool to it.detail.substringBefore(" (target=").substringBefore("; actual").substringBefore(" on page")
        }.values.sortedBy { impact(it.first()) }.take(15)
        if (mismatches.isEmpty()) appendLine("\nNo completed mismatch to rank. Check rehearsal or infrastructure errors below.")
        for (group in mismatches) {
            val sample = group.first()
            appendLine("\n- **${sample.tool}** (${group.size} row(s)), `${sample.input}`; ${sample.settings}. ${sample.detail}")
        }
        appendLine("\n## Failures and errors")
        for (r in rows.filter { it.status == Status.FAIL || it.status == Status.ERROR }) {
            appendLine("\n### ${r.status}: ${r.tool} / `${r.input}`")
            appendLine("- Settings: ${r.settings}\n- Expected: ${r.expected}\n- Actual: ${r.detail}")
            if (r.trace.isNotBlank()) appendLine("\n```\n${r.trace}\n```")
        }
        appendLine("\n## Workstream G device steps")
        appendLine("Use only the test APK and QA corpus folder, with G holding the phone lock. " +
            "Perform the listed recipe once per CSV input, compare to its matrix expectation, and capture only test output.")
        appendLine("See also DEVICE_G.md for the phone checks of the Vault credential/export UI, manual portrait positioning, and Android PDF import.")
        for ((tool, group) in rows.filter { it.status == Status.DEVICE_ONLY }.groupBy { it.tool }) {
            appendLine("\n- **$tool**: ${group.first().settings}")
        }
    }

    private fun impact(row: Row): Int = when {
        "DATA LOSS" in row.detail || row.tool == "redact" || row.tool == "protect" -> 0
        row.tool in setOf("unlock", "compress", "to_word", "to_excel", "import_pdf_negative", "pdfa") -> 1
        else -> 2
    }

    private fun deviceSteps(id: String): String? = when (id) {
        "scan" -> "Print/display the listed fixture, then Create > Scan; frame, confirm the crop and save. " +
            "For EXIF 6/8 also pick the original through scanner gallery; for the spread check any split option. Compare page count, crop, upright orientation and source SHA."
        "images_to_pdf" -> "Create > Import photos; pick the listed fixture and another in a known order, confirm both crops and save. Compare PDFBox page count/order and orientation; HEIC rejection must explain how to proceed."
        "qr_code" -> "Tools > QR code > Scan; show the fixture to the camera. Compare decoded text to qr_images/QR_PAYLOADS.txt and ensure no link, payment, dial or Wi-Fi action occurs automatically."
        "read_text" -> "Import the fixture, open it and choose Text > Read. Select the source language, copy result, compare to OCR_GROUND_TRUTH.txt; do not change device-wide settings."
        "searchable_pdf" -> "Import the fixture, read text in its language, then Tools > Searchable PDF. Save a copy and extract text with PDFBox; compare original page images and locate the fixture terms."
        else -> null
    }
}
