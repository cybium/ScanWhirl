package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.ContentKind
import com.scanwhirl.app.model.PageBox
import com.scanwhirl.app.model.PageKind
import com.scanwhirl.app.model.PageRef
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.backup.FullBackup
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DigitalStorageTest {
    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var input: File
    private val flags get() = app.getSharedPreferences("digital_capabilities", 0)
    private val roots = listOf(DigitalStorage.LIVE, DigitalStorage.TRASH, DigitalStorage.ESCROW,
        DigitalStorage.STAGING, DigitalStorage.DRAFTS, "documents", "trash", "import_discard", "drafts")

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        VaultSession.close()
        roots.forEach { File(app.filesDir, it).deleteRecursively() }
        flags.edit().clear().commit()
        ScanRepository.usableSpace = { Long.MAX_VALUE }
        input = File(app.cacheDir, "fictional-${UUID.randomUUID()}.pdf")
        input.writeText("%PDF-fictional test-only bytes; no real parser in this slice")
    }

    @After fun tearDown() {
        OpFaults.reset()
        DigitalStorage.verificationProbe = null
        DigitalStorage.clearVerificationCacheForTests()
        FullBackup.archiveStreamProbe = null
        Durable.resetForTests()
        ScanRepository.resetTestSeams()
        ImportDiscardStore.fault = {}
        roots.forEach { File(app.filesDir, it).deleteRecursively() }
        flags.edit().clear().commit()
        input.delete()
        VaultSession.close()
    }

    private fun enable() {
        flags.edit().putBoolean("digital_store_read", true).putBoolean("digital_import", true).commit()
    }

    private fun verified(file: File): List<PageRef> {
        assertArrayEquals(input.readBytes(), file.readBytes())
        val box = PageBox(0.0, 0.0, 595.0, 842.0)
        return listOf(PageRef(UUID.randomUUID().toString(), 0, box, box, 0, PageKind.DIGITAL))
    }

    private fun publish(id: String = UUID.randomUUID().toString(), name: String = "Fictional form"): ScanDocument =
        repo.digitalTransaction.publish(input, name, id, ::verified)

    private fun raster(id: String = "old_raster", name: String = "Fictional form"): File {
        val dir = File(repo.docsDir, id).apply { mkdirs() }
        File(dir, "meta.txt").writeText("$name\n1000\nOther\n")
        File(dir, "scan.pdf").writeBytes(byteArrayOf(37, 80, 68, 70))
        File(dir, "page_000.jpg").writeBytes(byteArrayOf(1, 2, 3))
        return dir
    }

    private fun snapshot(dir: File): Map<String, String> =
        dir.listFiles()!!.associate { it.name to Sha256.of(it) }

    @Test fun oldRasterUnchangedAndDigitalRecognizedWhenFlagsOff() {
        val old = raster()
        val before = snapshot(old)
        try { publish(); fail("writes must default off") } catch (_: IOException) {}
        enable()
        val digital = publish()
        flags.edit().clear().commit()
        val reopened = repo.getDocument(digital.id)!!
        assertEquals(ContentKind.DIGITAL, reopened.contentKind)
        assertEquals(1, reopened.pageCount)
        assertEquals(digital.pageRefs, reopened.pageRefs)
        assertTrue(reopened.pageFiles.isEmpty())
        assertArrayEquals(input.readBytes(), reopened.authoritativePdf.readBytes())
        assertEquals(ContentKind.RASTER, repo.getDocument(old.name)!!.contentKind)
        assertEquals(before, snapshot(old))
        assertEquals(2, repo.listDocuments().size)
        assertEquals(1, repo.docsDir.listFiles()!!.size)
        assertNotEquals(repo.getDocument(old.name)!!.name, reopened.name)
    }

    @Test fun rasterToolsCannotRebuildDigitalAuthority() = runBlocking {
        enable()
        val doc = publish()
        val before = snapshot(doc.pdfFile.parentFile!!)
        assertTrue(repo.pageFilesIn(doc.pdfFile.parentFile!!).isEmpty())
        try { repo.rotatePage(doc.id, 0); fail("digital rotate must be refused") } catch (_: IOException) {}
        try { repo.extractPages(doc.id, listOf(0)); fail("digital extract must be refused") } catch (_: IOException) {}
        try { repo.duplicateDocument(doc.id, null); fail("digital duplicate must be refused") } catch (_: IOException) {}
        repo.refreshPdfAfterIndex(doc.id)
        assertEquals(before, snapshot(doc.pdfFile.parentFile!!))
        assertTrue(repo.docsDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun malformedManifestOrPdfNeverRegenerated() {
        enable()
        val doc = publish()
        val dir = doc.pdfFile.parentFile!!
        val manifest = File(dir, DigitalStorage.PAGES)
        manifest.appendText("invalid")
        assertNull(repo.getDocument(doc.id))
        repo.digitalTransaction.recover()
        repo.refreshPdfAfterIndex(doc.id)
        assertNull(repo.getDocument(doc.id))
        assertArrayEquals(input.readBytes(), doc.pdfFile.readBytes())
        assertTrue(manifest.readText().endsWith("invalid"))
        assertTrue(repo.digitalStorage.reserves(doc.id))
    }

    @Test fun failedVerifierCannotPublish() {
        enable()
        val id = UUID.randomUUID().toString()
        try {
            repo.digitalTransaction.publish(input, "Bad fixture", id) { throw IOException("refused") }
            fail("verification must fail")
        } catch (_: IOException) {}
        assertNull(repo.getDocument(id))
        assertTrue(repo.digitalStorage.dirs(DigitalStorage.STAGING).isEmpty())
        assertTrue(input.isFile)
    }

    @Test fun eachPublishBoundarySurvivesProcessDeathAndRetry() {
        enable()
        val beforeSeal = listOf("digital:reserved", "digital:pdf_synced", "digital:manifest_synced",
            "digital:meta_synced", "digital:before_seal")
        val afterSeal = listOf("digital:sealed", "digital:before_rename", "digital:renamed",
            "digital:parents_synced", "digital:receipt_finished")
        for (step in beforeSeal + afterSeal) {
            val id = UUID.randomUUID().toString()
            OpFaults.hook = { if (it == step) throw SimulatedProcessDeath(step) }
            try { publish(id); fail("Expected fault $step") } catch (_: SimulatedProcessDeath) {}
            OpFaults.reset()
            val reopened = ScanRepository(app)
            reopened.digitalTransaction.recover()
            if (step in beforeSeal) assertNull("must not publish unsealed $step", reopened.getDocument(id))
            else assertNotNull("sealed authority survives $step", reopened.getDocument(id))
            val retried = publish(id)
            assertEquals(id, retried.id)
            assertArrayEquals(input.readBytes(), retried.pdfFile.readBytes())
            assertEquals(1, repo.listDocuments().count { it.id == id })
            assertFalse(File(retried.pdfFile.parentFile, ".ready").exists())
        }
    }

    @Test fun fileSyncFailureLeavesNoPartialLiveAuthority() {
        enable()
        for (member in listOf(DigitalStorage.PDF, DigitalStorage.PAGES, DigitalStorage.META, ".ready.tmp")) {
            val id = UUID.randomUUID().toString()
            Durable.beforeFileSync = { if (it.name == member) throw IOException("synthetic sync failure") }
            try { publish(id); fail("must fail sync $member") } catch (_: IOException) {}
            Durable.resetForTests()
            repo.digitalTransaction.recover()
            assertNull(repo.getDocument(id))
            assertTrue(input.isFile)
        }
    }

    @Test fun existingAuthorityCannotBeOverwrittenByConflictingIdOrReplay() {
        enable()
        val id = UUID.randomUUID().toString()
        val old = raster(id)
        val oldHash = snapshot(old)
        try { publish(id); fail("must refuse cross-root collision") } catch (_: IOException) {}
        assertEquals(oldHash, snapshot(old))
        val doc = publish()
        val before = snapshot(doc.pdfFile.parentFile!!)
        input.appendText("different")
        try { publish(doc.id); fail("must refuse changed source") } catch (_: IOException) {}
        assertEquals(before, snapshot(doc.pdfFile.parentFile!!))
    }

    @Test fun trashAndEscrowKeepCanonicalBytesAndNeverReuseId() {
        enable()
        val doc = publish()
        val hash = doc.contentSha256
        val trash = repo.moveToTrash(doc.id)!!
        assertTrue(trash.startsWith("digital:"))
        assertNull(repo.getDocument(doc.id))
        assertTrue(repo.digitalStorage.reserves(doc.id))
        assertTrue(repo.trashDir.listFiles().orEmpty().isEmpty())
        val restored = repo.restoreFromTrash(trash)
        assertEquals(hash, restored.contentSha256)
        assertEquals(doc.id, restored.id)
        val escrow = ImportDiscardStore(app, repo, elapsed = { 1000L }, boot = { 1 }, wall = { 100000L })
        val token = escrow.discard(doc.id, 8000)
        assertTrue(token.key.startsWith("digital:"))
        assertNull(repo.getDocument(doc.id))
        assertTrue(repo.digitalStorage.reserves(doc.id))
        assertEquals(doc.id, escrow.restore(token.key))
        assertEquals(hash, repo.getDocument(doc.id)!!.contentSha256)
        assertArrayEquals(input.readBytes(), repo.getDocument(doc.id)!!.pdfFile.readBytes())
    }

    @Test fun mixedBackupRestoresDigitalAndRasterWithoutFlagOrPreview() {
        enable()
        val old = raster()
        val oldHash = snapshot(old)
        val digital = publish()
        val hash = digital.contentSha256
        val backup = FullBackup(app)
        val archive = File(app.cacheDir, "digital-${UUID.randomUUID()}.swbackup")
        val password = "fictional password 123"
        try {
            assertEquals(2, backup.write(archive, password, FullBackup.Wrap.PASSWORD).documents)
            assertEquals(3, backup.header(archive).version)
            repo.docsDir.deleteRecursively()
            repo.digitalStorage.existingRoot(DigitalStorage.LIVE).deleteRecursively()
            flags.edit().clear().commit()
            val report = backup.restore(archive, password)
            assertEquals(2, report.documents)
            assertEquals(0, report.docsFailed)
            assertEquals(hash, repo.getDocument(digital.id)!!.contentSha256)
            assertEquals(digital.pageRefs, repo.getDocument(digital.id)!!.pageRefs)
            assertEquals(oldHash, snapshot(File(repo.docsDir, old.name)))
            assertArrayEquals(input.readBytes(), repo.getDocument(digital.id)!!.pdfFile.readBytes())
        } finally { archive.delete() }
    }

    @Test fun oldFormatBackupStillRestoresRasterExactly() {
        val old = raster()
        val before = snapshot(old)
        val backup = FullBackup(app)
        val archive = File(app.cacheDir, "raster-${UUID.randomUUID()}.swbackup")
        val password = "fictional password 123"
        try {
            backup.write(archive, password, FullBackup.Wrap.PASSWORD)
            assertEquals(2, backup.header(archive).version)
            repo.docsDir.deleteRecursively()
            assertEquals(1, backup.restore(archive, password).documents)
            assertEquals(before, snapshot(File(repo.docsDir, old.name)))
        } finally { archive.delete() }
    }
    @Test fun startupDoesNotPurgeUnprovenAuthorityOrFollowLinks() {
        enable()
        val doc = publish()
        val other = publish()
        val hidden = File(repo.digitalStorage.root(DigitalStorage.TRASH), ".purge-unproven")
        assertTrue(doc.pdfFile.parentFile!!.renameTo(hidden))
        val before = snapshot(hidden)
        repo.recoverTrash()
        assertEquals(before, snapshot(hidden))
        // Host CI is Linux; prove the digital parser does not follow an unexpected child link.
        val pdf = other.pdfFile
        assertTrue(pdf.delete())
        java.nio.file.Files.createSymbolicLink(pdf.toPath(), File(hidden, DigitalStorage.PDF).toPath())
        assertNull(repo.getDocument(other.id))
        try { repo.deleteDocument(other.id); fail("linked authority must not be purged") } catch (_: IOException) {}
        assertEquals(before, snapshot(hidden))
        assertTrue(pdf.delete())
    }

    @Test fun interruptedAuthorizedPurgeDeletesOnlyThatAuthority() {
        enable()
        val first = publish()
        val second = publish()
        val secondHash = snapshot(second.pdfFile.parentFile!!)
        OpFaults.hook = { if (it.startsWith("digital:purge_member:")) throw SimulatedProcessDeath(it) }
        try { repo.deleteDocument(first.id); fail("expected interrupted deletion") } catch (_: SimulatedProcessDeath) {}
        OpFaults.reset()
        assertNull(repo.getDocument(first.id))
        assertTrue(repo.digitalStorage.reserves(first.id))
        repo.recoverTrash()
        assertFalse(repo.digitalStorage.reserves(first.id))
        assertEquals(secondHash, snapshot(second.pdfFile.parentFile!!))
    }

    @Test fun interruptedDigitalRestoreRetriesFromUnchangedBackup() {
        enable()
        val doc = publish()
        val archive = File(app.cacheDir, "restore-interrupted-${UUID.randomUUID()}.swbackup")
        val password = "fictional password 123"
        val backup = FullBackup(app)
        try {
            backup.write(archive, password, FullBackup.Wrap.PASSWORD)
            val archiveHash = Sha256.of(archive)
            repo.digitalStorage.existingRoot(DigitalStorage.LIVE).deleteRecursively()
            flags.edit().clear().commit()
            OpFaults.hook = { if (it == "digital:restore_member:pages.v1") throw SimulatedProcessDeath(it) }
            try { backup.restore(archive, password); fail("expected interrupted restore") } catch (_: SimulatedProcessDeath) {}
            OpFaults.reset()
            assertNull(repo.getDocument(doc.id))
            assertEquals(archiveHash, Sha256.of(archive))
            repo.digitalTransaction.recover()
            assertNull(repo.getDocument(doc.id))
            assertEquals(1, backup.restore(archive, password).documents)
            assertEquals(doc.contentSha256, repo.getDocument(doc.id)!!.contentSha256)
            assertEquals(archiveHash, Sha256.of(archive))
        } finally { archive.delete() }
    }

    @Test fun renameInEitherRootCannotDuplicateTitle() {
        val old = raster()
        enable()
        val doc = publish(name = "Digital")
        repo.renameDocument(old.name, doc.name)
        assertNotEquals(repo.getDocument(old.name)!!.name, repo.getDocument(doc.id)!!.name)
        repo.renameDocument(doc.id, repo.getDocument(old.name)!!.name)
        val names = repo.listDocuments().map { it.name }
        assertEquals(names.toSet().size, names.size)
    }

    @Test fun listingAndMetadataEditsNeverHashPdfAndRecoveryRevalidatesColdCache() {
        enable()
        val doc = publish()
        var fullReads = 0
        DigitalStorage.verificationProbe = {
            assertFalse("full verification must run outside writer lock", Thread.holdsLock(ScanRepository.PDF_LOCK))
            fullReads++
        }
        repeat(10) {
            assertEquals(doc.contentSha256, repo.getDocument(doc.id)!!.contentSha256)
            assertEquals(1, repo.listDocuments().size)
            repo.renameDocument(doc.id, "New title $it")
            repo.setTags(doc.id, listOf("tag"))
        }
        assertEquals("listing and metadata changes must not hash PDF", 0, fullReads)
        DigitalStorage.clearVerificationCacheForTests()
        assertTrue(repo.digitalStorage.hasLive(doc.id))
        repo.digitalTransaction.recover()
        assertEquals(1, fullReads)
        assertEquals(doc.contentSha256, repo.getDocument(doc.id)!!.contentSha256)
        // Same-size mutation invalidates the generation-bound cache on changed file stamp.
        val pdf = doc.pdfFile
        val oldTime = pdf.lastModified()
        val bytes = pdf.readBytes()
        bytes[0] = 0
        pdf.writeBytes(bytes)
        assertTrue(pdf.setLastModified(oldTime + 2000))
        assertNull(repo.getDocument(doc.id))
        repo.digitalTransaction.recover()
        assertNull(repo.getDocument(doc.id))
    }

    @Test fun fullVerificationAndProducerDoNotHoldWriterLocks() {
        enable()
        var checks = 0
        DigitalStorage.verificationProbe = {
            assertFalse(Thread.holdsLock(ScanRepository.PDF_LOCK))
            checks++
        }
        val doc = repo.digitalTransaction.publish(input, "Off lock", UUID.randomUUID().toString()) {
            assertFalse(Thread.holdsLock(ScanRepository.PDF_LOCK))
            verified(it)
        }
        assertTrue(checks > 0)
        assertNotNull(repo.getDocument(doc.id))
        repo.digitalTransaction.recover()
        assertTrue(checks > 1)
    }

    @Test fun rasterBackupStreamDoesNotBlockConcurrentRepositoryWriter() {
        val old = raster()
        val archive = File(app.cacheDir, "lock-regression-${UUID.randomUUID()}.swbackup")
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        var exercised = false
        try {
            FullBackup.archiveStreamProbe = { path ->
                if (path.endsWith("page_000.jpg") && !exercised) {
                    exercised = true
                    assertFalse("raster stream must not own PDF lock", Thread.holdsLock(ScanRepository.PDF_LOCK))
                    executor.submit {
                        repo.withImportDiscardLock { repo.renameDocument(old.name, "Renamed while streaming") }
                    }.get(5, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            assertEquals(1, FullBackup(app).write(archive, "fictional password 123", FullBackup.Wrap.PASSWORD).documents)
            assertTrue(exercised)
            assertEquals("Renamed while streaming", repo.getDocument(old.name)!!.name)
        } finally {
            FullBackup.archiveStreamProbe = null
            executor.shutdownNow()
            archive.delete()
        }
    }

    @Test fun digitalBackupAbortsWhenSnapshotChangesDuringUnlockedStream() {
        enable()
        val doc = publish()
        val archive = File(app.cacheDir, "changed-snapshot-${UUID.randomUUID()}.swbackup")
        try {
            FullBackup.archiveStreamProbe = { path ->
                if (path.endsWith(DigitalStorage.PDF)) {
                    assertFalse(Thread.holdsLock(ScanRepository.PDF_LOCK))
                    repo.renameDocument(doc.id, "Changed during backup")
                }
            }
            try {
                FullBackup(app).write(archive, "fictional password 123", FullBackup.Wrap.PASSWORD)
                fail("changed digital snapshot must not publish backup")
            } catch (_: IOException) {}
            assertFalse(archive.exists())
            assertEquals("Changed during backup", repo.getDocument(doc.id)!!.name)
        } finally { FullBackup.archiveStreamProbe = null; archive.delete() }
    }

    @Test fun hostileSelfConsistentGeometryAndProvenanceCannotBecomeAuthority() {
        enable()
        for (badCrop in listOf(true, false)) {
            val doc = publish()
            val dir = doc.pdfFile.parentFile!!
            val manifest = File(dir, DigitalStorage.PAGES)
            val lines = manifest.readLines().toMutableList()
            val page = lines[2].split('\t').toMutableList()
            if (badCrop) page[6] = "-1,0,595,842" else page[4] = "1"
            lines[2] = page.joinToString("\t")
            manifest.writeText(lines.joinToString("\n", postfix = "\n"))
            val meta = File(dir, DigitalStorage.META)
            meta.writeText(meta.readLines().joinToString("\n", postfix = "\n") {
                if (it.startsWith("page_manifest_sha256=")) "page_manifest_sha256=${Sha256.of(manifest)}" else it
            })
            assertNull(DigitalStorage.verify(dir))
            assertNull(repo.getDocument(doc.id))
            assertTrue(doc.pdfFile.isFile)
        }
    }

    @Test fun agedOrphansReclaimOnlyEmptyOrIndependentlyVerifiedDuplicate() {
        enable()
        val doc = publish()
        val root = repo.digitalStorage.root(DigitalStorage.STAGING)
        val empty = File(root, UUID.randomUUID().toString()).apply { mkdir() }
        val duplicate = File(root, doc.id)
        assertTrue(doc.pdfFile.parentFile!!.copyRecursively(duplicate))
        File(duplicate, ".intent").writeText("scanwhirl-digital-intent-v1\n${doc.id}\n${doc.contentGeneration}\n${doc.contentSha256}\nimport\n")
        val last = File(root, UUID.randomUUID().toString()).apply { mkdir() }
        File(last, DigitalStorage.PDF).writeBytes(input.readBytes())
        File(last, ".intent").writeText("scanwhirl-digital-intent-v1\n${last.name}\n${last.name}\n${Sha256.of(input)}\nimport\n")
        val recent = File(root, UUID.randomUUID().toString()).apply { mkdir() }
        val unknown = File(root, UUID.randomUUID().toString()).apply { mkdir() }
        File(unknown, ".ready.tmp").writeText("possible torn seal")
        val old = System.currentTimeMillis() - DigitalTransaction.ORPHAN_AGE_MS - 60_000
        for (dir in listOf(empty, duplicate, last, unknown)) {
            dir.listFiles().orEmpty().forEach { assertTrue(it.setLastModified(old)) }
            assertTrue(dir.setLastModified(old))
        }
        repo.digitalTransaction.recover()
        assertFalse(empty.exists())
        assertFalse(duplicate.exists())
        assertTrue(last.exists())
        assertTrue(recent.exists())
        assertTrue(unknown.exists())
        assertArrayEquals(input.readBytes(), repo.getDocument(doc.id)!!.pdfFile.readBytes())
    }

    @Test fun hostileVerifierRejectionCannotSealEvenAfterRetryOrCrash() {
        enable()
        input.writeText("%PDF-1.7\nmalformed xref /Encrypt /JavaScript /Launch")
        val id = UUID.randomUUID().toString()
        var sealAttempts = 0
        OpFaults.hook = { if (it == "digital:before_seal") sealAttempts++ }
        try {
            repo.digitalTransaction.publish(input, "Hostile fixture", id) {
                // A test-only stand-in for W7b-1/3 syntax/protection classifier.
                assertTrue(it.readText().contains("/Encrypt"))
                throw SimulatedProcessDeath("rejecting verifier interrupted")
            }
            fail("must not publish an unverified payload")
        } catch (_: SimulatedProcessDeath) {}
        val stage = File(repo.digitalStorage.existingRoot(DigitalStorage.STAGING), id)
        assertTrue(stage.exists())
        assertFalse(File(stage, ".ready").exists())
        repo.digitalTransaction.recover()
        assertNull(repo.getDocument(id))
        try {
            repo.digitalTransaction.publish(input, "Hostile fixture", id) {
                throw IOException("reject malformed/encrypted PDF")
            }
            fail("rejecting verifier must not publish")
        } catch (_: IOException) {}
        assertEquals(0, sealAttempts)
        assertNull(repo.getDocument(id))
        assertTrue(repo.listDocuments().isEmpty())
    }

}
