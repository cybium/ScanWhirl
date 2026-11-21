package com.scanwhirl.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile

/**
 * Review P2: speaker notes exist only where a page actually has non-blank text. A deck with one
 * real note and one blank note must not grow an empty notes slide for the blank page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class O1PptxNotesTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val jpeg get() = ImportFixtures.image(width = 120, height = 160)

    private fun deck(name: String, notes: (Int) -> String?): File =
        File(app.cacheDir, "o1_${name}_${System.nanoTime()}.pptx").also {
            PptxWriter.write(it, 2, true, { jpeg }, notes)
        }

    @Test
    fun `a blank note does not create an empty notes slide next to a real one`() {
        val file = deck("mixed") { index -> if (index == 1) "A real note" else "" }

        assertTrue("the deck has a real note", PptxWriter.hasNotes(file))
        ZipFile(file).use { zip ->
            assertNull("page 1 has no note text", zip.getEntry("ppt/notesSlides/notesSlide1.xml"))
            assertNotNull("page 2 has the note", zip.getEntry("ppt/notesSlides/notesSlide2.xml"))
            val rels = zip.getInputStream(zip.getEntry("ppt/slides/_rels/slide1.xml.rels")).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
            assertFalse("slide 1 has no notes relationship: $rels", rels.contains("notesSlide"))
        }
    }

    @Test
    fun `a deck whose notes are all blank has no notes parts`() {
        val file = deck("blank") { "   " }

        assertFalse(PptxWriter.hasNotes(file))
        ZipFile(file).use { zip ->
            assertNull(zip.getEntry("ppt/notesSlides/notesSlide1.xml"))
            assertNull(zip.getEntry("ppt/notesSlides/notesSlide2.xml"))
        }
    }
}
