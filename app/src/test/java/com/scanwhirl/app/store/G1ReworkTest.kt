package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

/**
 * G1 independent review P2: unchecked lock deletion, floor-division sampling, unresolved recovery
 * markers and the locked re-check of an orphan reservation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class G1ReworkTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After
    fun tearDown() = Faults.resetAll()

    private fun dirOf(id: String) = File(repo.docsDir, id)
    private fun pdfOf(id: String) = File(dirOf(id), PdfProtection.PDF_FILE)
    private fun lockOf(id: String) = File(dirOf(id), PdfProtection.LOCK_FILE)

    private fun unlockMarker(): File? = File(app.filesDir, "op_staging").listFiles { it -> it.isDirectory }
        ?.firstNotNullOfOrNull { d -> File(d, ".unlock").takeIf { it.isFile } }

    @Test
    fun `a failed lock deletion keeps the marker and never silently unlocks`() = runBlocking {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val pdfBefore = Sha256.of(pdfOf(id))

        ScanRepository.removeLockFile = { false }
        val failed = runCatching { repo.unlockPdf(id, "1234") }

        assertTrue("the failure must reach the caller: ${failed.exceptionOrNull()}", failed.isFailure)
        assertEquals("honest localized message", app.getString(R.string.unlock_lock_not_removed), failed.exceptionOrNull()?.message)
        assertTrue("the lock label stays", lockOf(id).exists())
        assertNotNull("the recovery marker stays", unlockMarker())
        assertTrue("no silent unlock: the plain PDF still carries a lock label", repo.protectionState(id) == PdfProtection.State.NEEDS_REPAIR)
        assertTrue("the durable unlocked PDF is in place", Sha256.of(pdfOf(id)) != pdfBefore)

        // A later start retries with a working delete and finishes cleanly.
        ScanRepository.removeLockFile = { it.delete() }
        OpStaging.resetForTests()
        repo.recoverOperations()
        assertFalse("the label is gone", lockOf(id).exists())
        assertNull("the marker is resolved", unlockMarker())
        assertEquals(PdfProtection.State.NONE, repo.protectionState(id))
    }

    @Test
    fun `an unresolved marker survives the startup sweep and is retried later`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val death = Faults.dieAtOp("unlock:swapped")
        runCatching { runBlocking { repo.unlockPdf(id, "1234") } }
        assertTrue("the unlock must die at the swap", death.fired)
        Faults.resetAll()

        ScanRepository.removeLockFile = { false }
        repo.recoverOperations()

        val marker = unlockMarker()
        assertNotNull("marker retained while the label cannot be removed", marker)
        assertTrue("the recovery folder is NOT swept", marker!!.parentFile.exists())
        assertTrue("the label stays", lockOf(id).exists())

        ScanRepository.removeLockFile = { it.delete() }
        repo.recoverOperations()

        assertNull("the marker is gone after a successful retry", unlockMarker())
        assertFalse("the label is gone", lockOf(id).exists())
        assertTrue("staging is swept once resolved", File(app.filesDir, "op_staging").list().orEmpty().isEmpty())
    }

    @Test
    fun `a stale unlock marker with an unknown digest is ignored and removed safely`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val pdfBefore = Sha256.of(pdfOf(id))
        val record = lockOf(id).readText()
        val work = OpStaging.create(app.filesDir)
        File(work, ".unlock").writeText(
            "scanwhirl-unlock-v1\nid=$id\nold=$record\npdf=${"0".repeat(64)}\nnewPdf=${"1".repeat(64)}\n"
        )
        // A new process has no live staging folders, so sweep must see this one.
        OpStaging.resetForTests()

        repo.recoverOperations()

        assertEquals("the document PDF is untouched", pdfBefore, Sha256.of(pdfOf(id)))
        assertTrue("still locked", lockOf(id).exists())
        assertEquals(PdfProtection.State.LOCKED, repo.protectionState(id))
        assertTrue("staging is swept", File(app.filesDir, "op_staging").list().orEmpty().isEmpty())
    }

    @Test
    fun `a reservation that gains data after the snapshot is not deleted`() {
        val orphan = File(repo.docsDir, "deadbeef10").apply { assertTrue(mkdirs()) }
        ScanRepository.orphanCleanupProbe = {
            // Stand-in for a write racing the snapshot: the locked re-check must see it.
            File(orphan, "page_001.jpg").writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        repo.recoverOperations()
        assertTrue("a dir with fresh page data is never deleted", orphan.exists())
        assertEquals(listOf<Byte>(1, 2, 3, 4), File(orphan, "page_001.jpg").readBytes().toList())
    }

    @Test
    fun `an 8001 pixel source decodes with a long side of at most 4000`() {
        val file = File(app.cacheDir, "g1_8001_${System.nanoTime()}.jpg")
        file.writeBytes(ImportFixtures.image(width = 8001, height = 100))
        val decoded = ScanRepository.refilterDecode(file)
        try {
            assertTrue("floor division left ${decoded.width} px, over the 4000 cap", decoded.width <= 4000)
        } finally {
            decoded.recycle()
            file.delete()
        }
    }
}
