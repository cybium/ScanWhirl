package com.scanwhirl.app.ui

import android.content.Intent
import android.view.View
import android.widget.TextView
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OcrFootnoteD04Test {
    @Test fun `footnote distinguishes typed imported and phone read text from actual PDF searchability`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = ScanRepository(app)
        for (ocr in listOf(false, true)) for (layered in listOf(false, true)) {
            val id = "foot${ocr}${layered}"
            val dir = File(repo.docsDir, id).apply { mkdirs() }
            File(dir, "meta.txt").writeText("Fictional\n1\nOther\n")
            File(dir, "scan.pdf").writeBytes(byteArrayOf(1))
            File(dir, "page_000.jpg").writeBytes(byteArrayOf(1))
            if (ocr) {
                repo.ocrStore.putPage(id, "page_000.jpg", "fictional",
                    OcrPage(100, 100, listOf(OcrLine("fictional", 1, 1, 50, 10))))
            } else {
                repo.updateOcrPage(id, "page_000.jpg", "typed or imported")
            }
            if (layered) File(dir, "text_layer.flag").writeText("")
            val intent = Intent(app, OcrEditorActivity::class.java)
                .putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
            Robolectric.buildActivity(OcrEditorActivity::class.java, intent).setup().use {
                val note = it.get().findViewById<TextView>(R.id.ocrFootNote)
                val text = if (note.visibility == View.VISIBLE) note.text.toString() else ""
                assertEquals("phone claim requires valid OCR geometry", ocr, text.contains("Read on this phone"))
                assertEquals("PDF claim requires its text-layer flag", layered, text.contains("Searchable"))
            }
        }
    }

    @Test fun `typing over recognized writing immediately removes the on phone claim`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = ScanRepository(app)
        val id = "footedit"
        val dir = File(repo.docsDir, id).apply { mkdirs() }
        File(dir, "meta.txt").writeText("Fictional\n1\nOther\n")
        File(dir, "scan.pdf").writeBytes(byteArrayOf(1))
        File(dir, "page_000.jpg").writeBytes(byteArrayOf(1))
        repo.ocrStore.putPage(id, "page_000.jpg", "recognized",
            OcrPage(100, 100, listOf(OcrLine("recognized", 1, 1, 50, 10))))
        val intent = Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent).setup().use {
            val activity = it.get()
            activity.findViewById<View>(R.id.btnEditText).performClick()
            activity.findViewById<EditText>(R.id.editText).setText("my typed replacement")
            val note = activity.findViewById<TextView>(R.id.ocrFootNote)
            assertFalse(note.visibility == View.VISIBLE && note.text.contains("Read on this phone"))
        }
    }
}
