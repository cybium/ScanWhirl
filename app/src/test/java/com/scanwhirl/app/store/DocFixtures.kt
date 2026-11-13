package com.scanwhirl.app.store

import android.app.Application
import android.net.Uri
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PageReplace
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Real documents created through the transactional save (PM-0.2b). Keep pages small (Robolectric heap). */
object DocFixtures {

    /**
     * Saves a [pages]-page document. Page `i` is `200 + 20 * i` px wide, so every page has a
     * different size and a page swap is detectable. Returns the document id.
     */
    fun saved(repo: ScanRepository, app: Application, pages: Int = 3, name: String = "Doc"): String {
        val staging = File(app.cacheDir, "fx_${UUID.randomUUID()}").apply { mkdirs() }
        val files = (0 until pages).map { i ->
            File(staging, "p$i.jpg").apply { writeBytes(ImportFixtures.image(width = 200 + 20 * i, height = 300)) }
        }
        return runBlocking { repo.saveScan(files.map { Uri.fromFile(it) }, null, name) }.id
    }

    /** A saved document that has been password-protected. */
    fun locked(repo: ScanRepository, app: Application, password: String = "1234", pages: Int = 3): String {
        val id = saved(repo, app, pages)
        runBlocking { repo.protectPdf(id, password) }
        return id
    }

    /** Writes OCR text and geometry for every page file of [id]. */
    fun withOcr(repo: ScanRepository, id: String) {
        val doc = requireNotNull(repo.getDocument(id)) { "document $id not found" }
        doc.pageFiles.forEach { file ->
            val (w, h) = requireNotNull(PageReplace.bounds(file)) { "unreadable page ${file.name}" }
            repo.ocrStore.putPage(id, file.name, "text ${file.name}", OcrPage(w, h, listOf(OcrLine("text", 1, 1, 10, 10))))
        }
    }
}
