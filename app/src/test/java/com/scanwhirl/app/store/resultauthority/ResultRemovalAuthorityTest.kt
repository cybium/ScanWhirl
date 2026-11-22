package com.scanwhirl.app.store.resultauthority

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.tools.ToolResultActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Task04: real fictional document bytes, not a filename or mtime as deletion authority. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResultRemovalAuthorityTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private fun saved(name: String) = DocFixtures.saved(repo, app, pages = 1, name = name)
    private fun strong(id: String) =
        requireNotNull(ToolResultActivity.captureOutputIdentityBlocking(app, listOf(id), emptyList()))

    @Test(timeout = 30_000)
    fun changedIdCardOutputAndRepeatedDeleteLeaveOriginalAndChangedBytes() {
        val source = saved("Original")
        val output = saved("ID result")
        val old = strong(output)
        val pdf = requireNotNull(repo.getDocument(output)).pdfFile
        val before = pdf.readBytes()
        pdf.writeBytes(before.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        val altered = pdf.readBytes()
        repeat(2) {
            val result = repo.removeResultOutputsIfUnchanged(listOf(output), emptyList(), old, source)
            assertEquals(ScanRepository.ResultDeleteState.REFUSED, result.state)
            assertArrayEquals(altered, pdf.readBytes())
            assertNotNull(repo.getDocument(source))
        }
    }

    @Test(timeout = 30_000)
    fun authenticIdCardCanBeDiscardedOnceButNotRetriedAsSuccess() {
        val source = saved("Must survive")
        val output = saved("Discardable")
        val identity = strong(output)
        assertEquals(
            ScanRepository.ResultDeleteState.REMOVED,
            repo.removeResultOutputsIfUnchanged(listOf(output), emptyList(), identity, source).state
        )
        assertNull(repo.getDocument(output))
        assertNotNull(repo.getDocument(source))
        assertEquals(
            ScanRepository.ResultDeleteState.REFUSED,
            repo.removeResultOutputsIfUnchanged(listOf(output), emptyList(), identity, source).state
        )
    }

    @Test(timeout = 30_000)
    fun sameLengthAndMtimeTamperAndCheapIdentityNeverDelete() {
        val id = saved("Preserve altered bytes")
        val pdf = requireNotNull(repo.getDocument(id)).pdfFile
        val strong = strong(id)
        val before = pdf.readBytes()
        val time = pdf.lastModified()
        pdf.writeBytes(before.copyOf().apply { this[16] = (this[16].toInt() xor 17).toByte() })
        assertTrue(pdf.setLastModified(time))
        val changed = pdf.readBytes()
        assertEquals(
            ScanRepository.ResultDeleteState.REFUSED,
            repo.removeResultOutputsIfUnchanged(listOf(id), emptyList(), strong, null).state
        )
        val cheap = ToolResultActivity.cheapIdentity(app, listOf(id), emptyList())
        assertEquals(
            ScanRepository.ResultDeleteState.REFUSED,
            repo.removeResultOutputsIfUnchanged(listOf(id), emptyList(), cheap, null).state
        )
        assertArrayEquals(changed, pdf.readBytes())
    }

    @Test(timeout = 30_000)
    fun changedMemberOfMultiResultStopsAllDeletes() {
        val a = saved("A")
        val b = saved("B")
        val identity = requireNotNull(
            ToolResultActivity.captureOutputIdentityBlocking(app, listOf(a, b), emptyList())
        )
        val changed = requireNotNull(repo.getDocument(b)).pdfFile
        changed.appendBytes(byteArrayOf(19))
        assertEquals(
            ScanRepository.ResultDeleteState.REFUSED,
            repo.removeResultOutputsIfUnchanged(listOf(a, b), emptyList(), identity, null).state
        )
        assertNotNull(repo.getDocument(a))
        assertNotNull(repo.getDocument(b))
    }

    @Test(timeout = 30_000)
    fun missingLegacyAndMixedSourcesDoNotBecomeAuthorizedDeletes() {
        val source = saved("Keep input")
        val copy = saved("Output")
        val identity = strong(copy)
        for (bad in listOf(null, "", "C:D:$copy|1|2|3", "D:bad", "F:bad")) {
            assertEquals(
                ScanRepository.ResultDeleteState.REFUSED,
                repo.removeResultOutputsIfUnchanged(listOf(copy), emptyList(), bad, source).state
            )
        }
        assertEquals(
            ScanRepository.ResultDeleteState.REFUSED,
            repo.removeResultOutputsIfUnchanged(listOf(copy), emptyList(), identity, copy).state
        )
        assertEquals(
            ScanRepository.ResultDeleteState.REFUSED,
            repo.removeResultOutputsIfUnchanged(listOf(source, copy), emptyList(), identity, source).state
        )
        val fake = File(app.cacheDir, "source-not-output.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val fakeIdentity = ToolResultActivity.captureOutputIdentityBlocking(app, emptyList(), listOf(fake))
        assertEquals(
            ScanRepository.ResultDeleteState.REFUSED,
            repo.removeResultOutputsIfUnchanged(listOf(source), listOf(fake), fakeIdentity, source).state
        )
        assertTrue(fake.exists())
        assertNotNull(repo.getDocument(source))
    }

    @Test(timeout = 30_000)
    fun foreignPathAndDirectoryCannotBeDeletedWithEvenMatchingDigest() {
        val foreign = File(app.filesDir, "source-picture.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val forged = ToolResultActivity.captureOutputIdentityBlocking(app, emptyList(), listOf(foreign))
        assertEquals(
            ScanRepository.ResultDeleteState.REFUSED,
            repo.removeResultOutputsIfUnchanged(emptyList(), listOf(foreign), forged, null).state
        )
        assertTrue(foreign.exists())
        val dir = File(app.cacheDir, "documents/tools/fictional-result").apply { mkdirs() }
        val dirIdentity = ToolResultActivity.captureOutputIdentityBlocking(app, emptyList(), listOf(dir))
        assertEquals(
            ScanRepository.ResultDeleteState.REFUSED,
            repo.removeResultOutputsIfUnchanged(emptyList(), listOf(dir), dirIdentity, null).state
        )
        assertTrue(dir.isDirectory)
    }

    @Test(timeout = 30_000)
    fun actualWriterCannotInterleaveWithVerifiedPurge() {
        val id = saved("Concurrent output")
        val expected = strong(id)
        val verified = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val writerEntered = CountDownLatch(1)
        val writerCompleted = AtomicBoolean(false)
        ResultRemovalFaults.afterVerified = {
            verified.countDown()
            assertTrue("delete must be released", resume.await(10, TimeUnit.SECONDS))
        }
        try {
            val removal = Thread {
                repo.removeResultOutputsIfUnchanged(listOf(id), emptyList(), expected, null)
            }.apply { start() }
            assertTrue(verified.await(10, TimeUnit.SECONDS))
            val writer = Thread {
                writerEntered.countDown()
                runBlocking {
                    runCatching { repo.rotatePage(id, 0) }
                }
                writerCompleted.set(true)
            }.apply { start() }
            assertTrue(writerEntered.await(10, TimeUnit.SECONDS))
            assertFalse("PDF writer must block on repository lock", writerCompleted.get())
            resume.countDown()
            removal.join(10_000)
            writer.join(10_000)
            assertFalse(removal.isAlive)
            assertFalse(writer.isAlive)
            assertNull("the authorized output was deleted before a later attempted edit", repo.getDocument(id))
        } finally {
            resume.countDown()
            ResultRemovalFaults.afterVerified = null
        }
    }

    @Test(timeout = 30_000)
    fun oldResultCannotDeleteSameRestoredDocumentEvenWhenBytesMatch() {
        val original = saved("Same bytes after recovery")
        val identity = strong(original)
        val before = requireNotNull(repo.getDocument(original)).pdfFile.readBytes()
        val trash = requireNotNull(repo.moveToTrash(original))
        val restored = repo.restoreFromTrash(trash)
        assertEquals(original, restored.id)
        assertArrayEquals(before, restored.pdfFile.readBytes())
        assertEquals(
            ScanRepository.ResultDeleteState.REFUSED,
            repo.removeResultOutputsIfUnchanged(listOf(original), emptyList(), identity, null).state
        )
        assertNotNull(repo.getDocument(original))
    }

    @Test(timeout = 30_000)
    fun exportBookkeepingDoesNotInvalidateAnOtherwiseUnchangedProducedOutput() {
        val output = saved("Prepared copy")
        val identity = strong(output)
        repo.addExport(output, android.net.Uri.parse("content://fictional.example/documents/copy"))
        assertEquals(
            ScanRepository.ResultDeleteState.REMOVED,
            repo.removeResultOutputsIfUnchanged(listOf(output), emptyList(), identity, null).state
        )
        assertNull(repo.getDocument(output))
    }

    @Test(timeout = 30_000)
    fun injectedFailureAfterVerificationButBeforeMutationLeavesResultAndSource() {
        val source = saved("Unchanged source")
        val out = saved("Unchanged output")
        val sourcePdf = requireNotNull(repo.getDocument(source)).pdfFile
        val originalSource = sourcePdf.readBytes()
        val outputPdf = requireNotNull(repo.getDocument(out)).pdfFile
        val originalOutput = outputPdf.readBytes()
        ResultRemovalFaults.afterVerified = { throw java.io.IOException("fictional fault") }
        try {
            try {
                repo.removeResultOutputsIfUnchanged(listOf(out), emptyList(), strong(out), source)
                fail("expected injected failure before delete")
            } catch (_: java.io.IOException) { }
            assertArrayEquals(originalOutput, outputPdf.readBytes())
            assertArrayEquals(originalSource, sourcePdf.readBytes())
        } finally {
            ResultRemovalFaults.afterVerified = null
        }
    }

    @Test(timeout = 30_000)
    fun mixedSourceAndStrongGeneratedFileDeletesOnlyTheGeneratedFile() {
        val source = saved("Input is not an output")
        val sourcePdf = requireNotNull(repo.getDocument(source)).pdfFile
        val sourceHash = sourcePdf.readBytes()
        val folder = File(app.cacheDir, "documents/tools/generated").apply { mkdirs() }
        val output = File(folder, "result.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val strong = requireNotNull(
            ToolResultActivity.captureOutputIdentityBlocking(app, emptyList(), listOf(output))
        )
        assertEquals(
            ScanRepository.ResultDeleteState.REMOVED,
            repo.removeResultOutputsIfUnchanged(listOf(source), listOf(output), strong, null).state
        )
        assertFalse(output.exists())
        assertArrayEquals(sourceHash, sourcePdf.readBytes())
    }
}
