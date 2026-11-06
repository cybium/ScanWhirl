package com.scanwhirl.app.store

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.CategoryRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Category persistence through meta.txt line 3 (test23). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CategoryPersistTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private var uriCounter = 0
    private val fakeJpeg = ByteArray(2048) { (it % 251).toByte() }

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://cat/fake_${uriCounter++}.jpg")
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    @Test
    fun `new scans are auto-classified from their name`() = runBlocking {
        val repo = ScanRepository(context)
        val receipt = repo.saveScan(listOf(contentUri(fakeJpeg)), null, "Rent receipt October")
        val notes = repo.saveScan(listOf(contentUri(fakeJpeg)), null, "Exam revision notes")
        val misc = repo.saveScan(listOf(contentUri(fakeJpeg)), null, "Scanned page")

        assertEquals(CategoryRules.RECEIPTS, repo.getDocument(receipt.id)?.category)
        assertEquals(CategoryRules.STUDY, repo.getDocument(notes.id)?.category)
        assertEquals(CategoryRules.OTHER, repo.getDocument(misc.id)?.category)
    }

    @Test
    fun `setCategory overrides the guess and survives a reload`() = runBlocking {
        val repo = ScanRepository(context)
        val doc = repo.saveScan(listOf(contentUri(fakeJpeg)), null, "Mystery pages")
        assertEquals(CategoryRules.OTHER, repo.getDocument(doc.id)?.category)

        repo.setCategory(doc.id, CategoryRules.FORMS)
        assertEquals(CategoryRules.FORMS, repo.getDocument(doc.id)?.category)

        // Still there after an unrelated rename (line 3 must not be dropped).
        repo.renameDocument(doc.id, "Renamed pages")
        val reloaded = repo.getDocument(doc.id)
        assertEquals("Renamed pages", reloaded?.name)
        assertEquals(CategoryRules.FORMS, reloaded?.category)
    }

    @Test
    fun `legacy two-line meta reads as Other`() {
        val dir = File(File(context.filesDir, "documents"), "legacy01").apply { mkdirs() }
        File(dir, "page_000.jpg").writeBytes(fakeJpeg)
        File(dir, "meta.txt").writeText("Old scan\n1700000000000\n")
        File(dir, "scan.pdf").writeBytes(byteArrayOf(1))

        val doc = ScanRepository(context).getDocument("legacy01")
        assertEquals(CategoryRules.OTHER, doc?.category)
    }

    @Test
    fun `duplicates inherit the category even under a new name`() = runBlocking {
        val repo = ScanRepository(context)
        val doc = repo.saveScan(listOf(contentUri(fakeJpeg)), null, "Passport copy source")
        repo.setCategory(doc.id, CategoryRules.STUDY)

        val copy = repo.duplicateDocument(doc.id, "Totally unrelated name")
        assertEquals(CategoryRules.STUDY, repo.getDocument(copy.id)?.category)
        assertTrue(repo.listDocuments().size >= 2)
    }
}
