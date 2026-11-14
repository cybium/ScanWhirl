package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile

/**
 * v2 UI: 03_tools_hub — builds the sample `.pptx` and `.docx` the independent host check opens
 * with python-pptx / python-docx (the lead's check, outside our code).
 *
 * The files are first written into the test cache (always), then copied to the Windows temp
 * folder the WSL check can read when this test runs under WSL. The copy is best effort: the
 * assertions below only need the real packages.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HostSampleExportTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @Test
    fun `writes one sample pptx and one sample docx for the host check`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Host sample")
        DocFixtures.withOcr(repo, id)
        val dir = File(app.cacheDir, "hostcheck").apply { mkdirs() }
        val pptx = File(dir, "sample.pptx")
        val docx = File(dir, "sample.docx")
        val xlsx = File(dir, "sample.xlsx")

        runBlocking { repo.exportPowerPoint(id, pptx) }
        runBlocking { repo.exportWord(id, docx, includePictures = true) }
        runBlocking { repo.exportExcel(id, xlsx, tablesOnly = false, noTableNote = "No table") }

        assertTrue("sample pptx is missing", pptx.isFile && pptx.length() > 0)
        assertTrue("sample docx is missing", docx.isFile && docx.length() > 0)
        assertTrue("sample xlsx is missing", xlsx.isFile && xlsx.length() > 0)
        ZipFile(pptx).use { zip ->
            assertTrue(zip.getEntry("ppt/presentation.xml") != null)
            assertTrue(zip.getEntry("ppt/slides/slide1.xml") != null)
            assertTrue(zip.getEntry("ppt/media/image1.jpeg") != null)
        }
        ZipFile(docx).use { zip ->
            assertTrue(zip.getEntry("word/document.xml") != null)
            assertTrue(
                zip.entries().toList().any { it.name.startsWith("word/media/") && it.name.endsWith(".jpg") }
            )
        }
        ZipFile(xlsx).use { zip ->
            assertTrue(zip.getEntry("xl/workbook.xml") != null)
            assertTrue(zip.getEntry("xl/worksheets/sheet1.xml") != null)
        }

        // Lead: no copies outside the test sandbox. For a manual python-pptx/python-docx/openpyxl
        // check, set SW_HOSTCHECK_DIR to a folder and run this test alone.
        System.getenv("SW_HOSTCHECK_DIR")?.let { File(it) }?.takeIf { it.isDirectory || it.mkdirs() }?.let { host ->
            pptx.copyTo(File(host, "sample.pptx"), overwrite = true)
            docx.copyTo(File(host, "sample.docx"), overwrite = true)
            xlsx.copyTo(File(host, "sample.xlsx"), overwrite = true)
        }
    }
}
