package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.Durable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * L1lib store-level regressions:
 *  #5 LibraryMeta.write must not delete a document whose TITLE happens to start with
 *     `tags=` or `folder=`; only the key lines after the fixed three-line header are metadata.
 *  #6 renameFolder must be crash-safe: a disk-full failure while rewriting one document must
 *     not leave the folder list renamed while documents still carry the old name.
 *
 * Fictional fixtures only; no owner media or production data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryMetaL1LibTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Durable.resetForTests()
    }

    @After
    fun tearDown() {
        Durable.resetForTests()
    }

    // ---- #5: title lines that look like metadata --------------------------

    @Test
    fun `write keeps a title that starts with tags equals`() {
        val lines = mutableListOf(
            "tags=Important title",           // line 0: the document title
            "1700000000000",                   // line 1: createdAt
            "other",                           // line 2: category
            "tags=old-tag"                     // line 3: the real tags key
        )
        LibraryMeta.write(lines, null, listOf("new-tag"))

        assertEquals("tags=Important title", lines[0])
        assertFalse("the old tags key is replaced", lines.any { it == "tags=old-tag" })
        assertTrue("the new tags key is written", lines.contains("tags=new-tag"))
        assertEquals("only the header and the new key remain", 4, lines.size)
    }

    @Test
    fun `write keeps a title that starts with folder equals`() {
        val lines = mutableListOf(
            "folder=Meeting notes title",      // line 0: the document title
            "1700000000000",
            "other",
            "folder=OldFolder"                 // line 3: the real folder key
        )
        LibraryMeta.write(lines, "NewFolder", emptyList())

        assertEquals("folder=Meeting notes title", lines[0])
        assertFalse("the old folder key is replaced", lines.any { it == "folder=OldFolder" })
        assertTrue("the new folder key is written", lines.contains("folder=NewFolder"))
    }

    // ---- #6: crash-safe folder rename -------------------------------------

    @Test(expected = IOException::class)
    fun `renameFolder keeps the folder list and rolls documents back on a disk failure`() {
        repo.createFolder("Tax")
        val a = DocFixtures.saved(repo, app, pages = 1, name = "A")
        val b = DocFixtures.saved(repo, app, pages = 1, name = "B")
        repo.setFolder(listOf(a, b), "Tax")
        assertEquals("Tax", repo.getDocument(a)?.folder)
        assertEquals("Tax", repo.getDocument(b)?.folder)

        val owned = setOf(a, b)
        var syncs = 0
        Durable.beforeFileSync = { file ->
            if (file.name == "meta.txt.tmp" && file.parentFile.name in owned) {
                syncs++
                if (syncs == 2) throw IOException("fictional full disk")
            }
        }

        // With the list-first order on the buggy build this throws AFTER folders.txt was
        // rewritten, leaving "Tax 2026" in the list and the documents split between names.
        repo.renameFolder("Tax", "Tax 2026")
    }

    @Test
    fun `after a failed rename the folder list and every document still agree`() {
        // Same scenario as above but asserting the recovered state (a separate test so a
        // thrown exception in a JUnit expected-attribute test cannot hide the state checks).
        repo.createFolder("Work")
        val a = DocFixtures.saved(repo, app, pages = 1, name = "X")
        val b = DocFixtures.saved(repo, app, pages = 1, name = "Y")
        repo.setFolder(listOf(a, b), "Work")

        val owned = setOf(a, b)
        var syncs = 0
        Durable.beforeFileSync = { file ->
            if (file.name == "meta.txt.tmp" && file.parentFile.name in owned) {
                syncs++
                if (syncs == 2) throw IOException("fictional full disk")
            }
        }
        runCatching { repo.renameFolder("Work", "Work 2026") }
        Durable.resetForTests()

        assertTrue("the folder keeps its old name", repo.listFolders().contains("Work"))
        assertFalse("no phantom renamed folder", repo.listFolders().contains("Work 2026"))
        assertEquals("first document rolled back", "Work", repo.getDocument(a)?.folder)
        assertEquals("second document was never renamed", "Work", repo.getDocument(b)?.folder)

        // The meta.txt of a rolled-back document must still be well formed (3 header lines).
        val meta = File(File(repo.docsDir, a), "meta.txt").readLines()
        assertTrue("title survived the rollback", meta.size >= 3 && meta[0].isNotBlank())
    }
}
