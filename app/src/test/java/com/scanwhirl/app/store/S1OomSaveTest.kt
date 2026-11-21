package com.scanwhirl.app.store

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.ImportFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * S1 review P2: runSaveTransaction must clean up an owned (unpublished) directory for ANY
 * throwable, including an Error. A heap failure may not leave a half-prepared document behind.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class S1OomSaveTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private val docsDir get() = File(app.filesDir, "documents").apply { mkdirs() }

    @Before @After
    fun reset() {
        SaveFaults.reset()
        docsDir.deleteRecursively()
    }

    @Test
    fun `an OutOfMemoryError during the save transaction removes the owned directory`() {
        val staged = File(app.filesDir, "pending_scans").apply { mkdirs() }.let { root ->
            File(root, "pending_${System.nanoTime()}").apply { mkdirs() }
        }
        val page = File(staged, "page_001.jpg").apply { writeBytes(ImportFixtures.image(width = 120)) }
        SaveFaults.hook = { step -> if (step == "verified") throw OutOfMemoryError("forced for the test") }

        try {
            runBlocking { repo.saveScan(listOf(Uri.fromFile(page)), null, "Doc", reviewDir = staged) }
            fail("the OutOfMemoryError must reach the caller")
        } catch (e: OutOfMemoryError) {
            // expected: an Error is not an Exception and must still be rethrown after cleanup
        } finally {
            SaveFaults.reset()
        }

        assertEquals(
            "no half-prepared document directory survives an Error",
            emptyList<String>(),
            docsDir.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        )
        org.junit.Assert.assertTrue("nothing is published", runBlocking { repo.listDocuments() }.isEmpty())
    }
}
