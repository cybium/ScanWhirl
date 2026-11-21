package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * G1 item 4: a crash between the exclusive `mkdir` reservation and publication left a reserved
 * id directory (empty, or holding only our `.incomplete` marker) in documents/ forever. The reaper
 * cannot see a bare empty directory and only acts after a day. recoverOperations runs at startup,
 * so it now removes these data-free orphan reservations. Published documents and any directory
 * that holds page/PDF/provenance data are never touched.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SaveReservationRecoveryTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After
    fun tearDown() = Faults.resetAll()

    @Test
    fun `an empty reserved id directory from a crash is removed`() {
        val orphan = File(repo.docsDir, "deadbeef").apply { assertTrue(mkdirs()) }
        repo.recoverOperations()
        assertFalse("the empty reservation must be reclaimed", orphan.exists())
    }

    @Test
    fun `a marker-only reservation from a crash is removed`() {
        val orphan = File(repo.docsDir, "deadbeef01").apply { assertTrue(mkdirs()) }
        SaveFormat.createMarker(orphan, SaveFormat.newToken(), System.currentTimeMillis() - 5_000L)
        assertTrue(SaveFormat.isIncomplete(orphan))

        repo.recoverOperations()

        assertFalse("the marker-only reservation must be reclaimed", orphan.exists())
    }

    @Test
    fun `a published document is never removed`() {
        val id = DocFixtures.saved(repo, app, pages = 1)
        val dir = File(repo.docsDir, id)
        val meta = File(dir, "meta.txt").readBytes()

        repo.recoverOperations()

        assertNotNull("the published document survives", repo.getDocument(id))
        assertTrue("the published directory survives", dir.exists())
        assertEquals("metadata is byte-identical", meta.toList(), File(dir, "meta.txt").readBytes().toList())
    }

    @Test
    fun `an incomplete directory that holds page data is kept for replay`() {
        // Data-safety negative: never delete a reservation with real content, even if unpublished.
        val dir = File(repo.docsDir, "deadbeef02").apply { assertTrue(mkdirs()) }
        SaveFormat.createMarker(dir, SaveFormat.newToken(), System.currentTimeMillis() - 5_000L)
        val page = File(dir, "page_001.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        repo.recoverOperations()

        assertTrue("a directory with page data is never deleted", dir.exists())
        assertEquals("the page bytes are intact", listOf<Byte>(1, 2, 3, 4), page.readBytes().toList())
    }
}
