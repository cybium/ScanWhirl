package com.scanwhirl.app.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * X1 follow-up: the OCR editor's page-name key strings (tabs, status, copy-all/export) must use
 * the same numeric rule as the page-file readers, so they follow document order past index 999.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrEditorPageOrderTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val indices = listOf(99, 100, 101, 999, 1000)
    private fun pageName(index: Int) = String.format(Locale.ROOT, "page_%03d.jpg", index)
    private fun marker(index: Int) = "Z${index}Z"

    private fun seedDoc(): String {
        val repo = ScanRepository(app)
        val dir = File(repo.docsDir, "x1ocr${System.nanoTime().toString().takeLast(8)}").apply { mkdirs() }
        File(dir, "meta.txt").writeText("X1 OCR seam\n${System.currentTimeMillis()}\nOther\n")
        val files = indices.map { i ->
            File(dir, pageName(i)).apply { writeBytes(ImportFixtures.image(width = 120, height = 80)) }
        }
        PdfGenerator.imagesToPdf(files, File(dir, "scan.pdf"))
        repo.updateOcrPages(dir.name, indices.associate { i -> pageName(i) to marker(i) })
        return dir.name
    }

    private fun launch(id: String, block: (OcrEditorActivity) -> Unit) {
        val intent = Intent(app, OcrEditorActivity::class.java).apply {
            putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
        }
        ActivityScenario.launch<OcrEditorActivity>(intent).use { it.onActivity(block) }
    }

    @Test
    fun `page tabs follow numeric order across the 999 to 1000 seam`() {
        val id = seedDoc()
        val expected = indices.map(::pageName)
        launch(id) { activity ->
            val group = activity.findViewById<ChipGroup>(R.id.pageTabs)
            val tags = (0 until group.childCount).map { (group.getChildAt(it) as Chip).tag as String }
            assertEquals("tabs use the document's numeric page order", expected, tags)
        }
    }

    @Test
    fun `copy all exports text in numeric page order`() {
        val id = seedDoc()
        launch(id) { activity ->
            activity.findViewById<View>(R.id.btnCopyAll).performClick()
            val cb = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            val positions = indices.map { text.indexOf(marker(it)) }
            assertTrue("every page marker is copied: $text", positions.all { it >= 0 })
            assertEquals("copy-all follows numeric page order", positions.sorted(), positions)
        }
    }
}
