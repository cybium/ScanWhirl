package com.scanwhirl.app.store

import com.scanwhirl.app.model.PageRef
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.Durable
import java.io.File
import java.io.IOException

/** New-copy transaction only. Replace/split and protection are later slices. */
internal class DigitalTransaction(private val repo: ScanRepository) {
    private val storage get() = repo.digitalStorage
    companion object {
        private val active = mutableSetOf<String>() // under repository writer locks
        private const val READY = ".ready"
        private const val INTENT = ".intent"
        internal const val ORPHAN_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_RECLAIMS = 32
    }

    /**
     * Caller owns a durable UUID token. A retry requires identical input bytes;
     * the verifier runs on staged bytes, never a mutable provider URI.
     * No production import callers until W7b-3.
     */
    fun publish(source: File, name: String, token: String, verify: (File) -> List<PageRef>): ScanDocument {
        require(DigitalStorage.token(token))
        if (!storage.writesEnabled()) throw IOException("Digital import is not enabled")
        require(source.isFile && source.length() in 1..DigitalStorage.MAX_PDF_BYTES)
        val hash = DigitalStorage.boundedHash(source)
        return transact(token, token, hash, source.length(), "import") { stage ->
            storage.writeVerified(stage, source, name, token, repo::copyDigitalStagePdf, verify)
        }
    }

    /** Re-adopt an authenticated, validated backup authority, even with import disabled. */
    fun restore(input: File): ScanDocument {
        val doc = DigitalStorage.verify(input) ?: throw IOException("Digital backup unavailable")
        val id = input.name
        require(DigitalStorage.safeId(id))
        val generation = requireNotNull(doc.contentGeneration)
        val hash = requireNotNull(doc.contentSha256)
        try {
        return transact(id, generation, hash, doc.cachedSize, "restore") { stage ->
            for (member in DigitalStorage.members) {
                if (member == DigitalStorage.PDF) repo.copyDigitalStagePdf(File(input, member), stage)
                else Durable.copyFile(File(input, member), File(stage, member))
                OpFaults.at("digital:restore_member:$member")
            }
            val meta = File(stage, DigitalStorage.META)
            val lines = DigitalStorage.boundedLines(meta, DigitalStorage.MAX_META_BYTES)
            // Export ownership is phone-local, not portable authority.
            Durable.atomicWriteText(meta, (lines.take(3) + lines.drop(3).filterNot {
                it.startsWith("exports=") || it.startsWith("exportsigs=") || it.startsWith("exportmade=")
            }).joinToString("\n", postfix = "\n"))
            DigitalStorage.verify(stage) ?: throw IOException("Digital restore verification failed")
        }
        } finally { DigitalStorage.forget(input) }
    }

    private fun transact(id: String, generation: String, hash: String, bytes: Long, operation: String,
                         build: (File) -> ScanDocument): ScanDocument {
        val stage = storage.child(DigitalStorage.STAGING, id)
        val intent = "scanwhirl-digital-intent-v1\n$id\n$generation\n$hash\n$operation\n"
        val live = storage.child(DigitalStorage.LIVE, id)
        if (live.isDirectory && DigitalStorage.read(live) == null) DigitalStorage.verify(live)
        // Do not read/hash a stage that an active producer is still building.
        val sealedCandidate = repo.withImportDiscardLock { stage.path !in active && File(stage, READY).isFile }
        val prepared = if (sealedCandidate) verifiedSeal(stage, full = true) else null
        val replay = repo.withImportDiscardLock {
            if (live.exists()) {
                val doc = DigitalStorage.read(live)
                if (doc?.contentGeneration == generation && doc.contentSha256 == hash &&
                    !File(repo.docsDir, id).exists()) return@withImportDiscardLock doc
                throw IOException("Digital identity conflict")
            }
            if (stage.path in active) throw IOException("Digital transaction already active")
            if (repo.digitalIdReserved(id, ignoreStage = true)) throw IOException("Digital identity reserved")
            val need = bytes * 2 + (16L shl 20)
            if (ScanRepository.usableSpace(stage.parentFile!!) < need) throw IOException("Not enough storage")
            if (stage.exists()) {
                if (!stage.isDirectory || !DigitalStorage.owned(stage) ||
                    readMarker(File(stage, INTENT), 512) != intent) throw IOException("Digital transaction requires recovery")
                if (File(stage, READY).exists()) {
                    return@withImportDiscardLock publishSealed(stage, prepared ?: throw IOException("Digital seal invalid"))
                }
                // Retry only our exact source. Do not follow links or delete unknown members.
                val allowed = DigitalStorage.members + listOf(INTENT, "meta.txt.tmp", ".ready.tmp")
                if (stage.listFiles()?.all { DigitalStorage.owned(it) && it.isFile && it.name in allowed } != true)
                    throw IOException("Unexpected digital staging member")
                for (file in stage.listFiles().orEmpty()) {
                    if (file.name != INTENT && !file.delete()) throw IOException("Cannot prepare digital retry")
                }
            } else {
                if (!stage.mkdir()) throw IOException("Cannot reserve digital transaction")
                Durable.syncDir(stage.parentFile!!)
                Durable.writeFile(File(stage, INTENT), intent.toByteArray(Charsets.UTF_8))
                Durable.syncDir(stage)
            }
            active += stage.path
            null
        }
        if (replay != null) return replay
        var sealed = false
        try {
            OpFaults.at("digital:reserved")
            val built = build(stage)
            if (built.contentSha256 != hash || built.contentGeneration != generation)
                throw IOException("Digital source changed during copy")
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            // build() performed full verification off the writer locks; the proof
            // binds both member inodes, lengths, times, hashes and generation.
            val identity = DigitalStorage.identity(stage) ?: throw IOException("Digital verification failed")
            val sealText = seal(built, identity)
            return repo.withImportDiscardLock {
                if (DigitalStorage.identity(stage) != identity || DigitalStorage.read(stage) == null)
                    throw IOException("Digital stage changed after verification")
                Durable.syncDir(stage)
                OpFaults.at("digital:before_seal")
                Durable.atomicWriteText(File(stage, READY), sealText)
                sealed = true
                Durable.syncDir(stage)
                OpFaults.at("digital:sealed")
                publishSealed(stage, Prepared(identity, sealText))
            }
        } catch (e: Exception) {
            if (!sealed && stage.exists()) {
                DigitalStorage.forget(stage)
                DigitalStorage.deleteOwnedTree(stage)
            }
            throw e
        } finally {
            repo.withImportDiscardLock { active.remove(stage.path) }
        }
    }

    private data class Prepared(val identity: DigitalStorage.Identity, val sealText: String)
    private fun seal(doc: ScanDocument, identity: DigitalStorage.Identity): String =
        "scanwhirl-digital-ready-v1\n${doc.id}\n${doc.contentGeneration}\n${doc.contentSha256}\n" +
            identity.manifestHash + "\n"

    private fun readMarker(file: File, limit: Long): String? =
        try { DigitalStorage.boundedLines(file, limit).joinToString("\n", postfix = "\n") }
        catch (_: Exception) { null }

    private fun verifiedSeal(stage: File, full: Boolean): Prepared? {
        val doc = (if (full) DigitalStorage.verify(stage) else DigitalStorage.read(stage)) ?: return null
        val identity = DigitalStorage.identity(stage) ?: return null
        val text = seal(doc, identity)
        return Prepared(identity, text).takeIf { readMarker(File(stage, READY), 512) == text }
    }

    private fun finishReceipt(dir: File) {
        // Remove intent first; if killed the ready seal still authorizes completion.
        for (name in listOf(INTENT, READY)) {
            val marker = File(dir, name)
            if (marker.exists() && !marker.delete()) throw IOException("Cannot finish digital receipt")
            Durable.syncDir(dir)
        }
    }

    private fun publishSealed(stage: File, prepared: Prepared): ScanDocument {
        if (DigitalStorage.identity(stage) != prepared.identity ||
            readMarker(File(stage, READY), 512) != prepared.sealText ||
            DigitalStorage.read(stage) == null) throw IOException("Digital transaction is not verified")
        val dest = storage.child(DigitalStorage.LIVE, stage.name)
        if (dest.exists() || repo.digitalIdReserved(stage.name, ignoreStage = true))
            throw IOException("Digital identity conflict")
        repo.resolveNameBeforePublish(stage)
        if (DigitalStorage.identity(stage) != prepared.identity)
            throw IOException("Digital identity changed before publish")
        OpFaults.at("digital:before_rename")
        if (!stage.renameTo(dest)) throw IOException("Cannot publish digital document")
        OpFaults.at("digital:renamed")
        Durable.syncDir(dest.parentFile!!)
        Durable.syncDir(stage.parentFile!!)
        OpFaults.at("digital:parents_synced")
        finishReceipt(dest)
        OpFaults.at("digital:receipt_finished")
        return DigitalStorage.read(dest)?.takeIf { it.contentSha256 == prepared.identity.hash }
            ?: throw IOException("Digital document unavailable")
    }

    /**
     * Reclaim at most 32 old unsealed, owned staging directories per startup.
     * No ready marker (including temporary/torn markers), no active lease,
     * and either no payload bytes or an independently verified identical live
     * authority. An unsealed complete/partial PDF without another authority
     * may be the last copy after the provider disappears: retain it.
     */
    private fun reclaim(stage: File, now: Long): Boolean {
        if (!DigitalStorage.owned(stage) || !DigitalStorage.safeId(stage.name) ||
            stage.lastModified() <= 0 || now - stage.lastModified() < ORPHAN_AGE_MS) return false
        val files = stage.listFiles() ?: return false
        val allowed = DigitalStorage.members + listOf(INTENT, "meta.txt.tmp")
        if (!files.all { DigitalStorage.owned(it) && it.isFile && it.name in allowed &&
                it.lastModified() > 0 && now - it.lastModified() >= ORPHAN_AGE_MS }) return false
        if (files.isEmpty()) return repo.withImportDiscardLock {
            if (stage.path in active || stage.listFiles()?.isEmpty() != true) false
            else stage.delete().also { if (it) Durable.syncDir(stage.parentFile!!) }
        }
        val intent = readMarker(File(stage, INTENT), 512)?.lines()?.filter { it.isNotEmpty() }
        if (intent == null || intent.size != 5 || intent[0] != "scanwhirl-digital-intent-v1" ||
            intent[1] != stage.name || !DigitalStorage.token(intent[2]) ||
            !Regex("[0-9a-f]{64}").matches(intent[3]) || intent[4] !in setOf("import", "restore")) return false
        val observed = files.associate { it.name to (it.length() to it.lastModified()) }
        val pdf = File(stage, DigitalStorage.PDF)
        val live = File(storage.existingRoot(DigitalStorage.LIVE), stage.name)
        var stageProof: DigitalStorage.Identity? = null
        val duplicate = if (pdf.length() > 0) {
            val doc = DigitalStorage.verify(stage) ?: return false
            val other = DigitalStorage.verify(live) ?: return false
            if (doc.contentGeneration != intent[2] || doc.contentSha256 != intent[3] ||
                doc.contentGeneration != other.contentGeneration || doc.contentSha256 != other.contentSha256 ||
                doc.pageRefs != other.pageRefs) return false
            stageProof = DigitalStorage.identity(stage) ?: return false
            DigitalStorage.identity(live) ?: return false
        } else null
        return repo.withImportDiscardLock {
            if (stage.path in active || stage.listFiles()?.associate { it.name to (it.length() to it.lastModified()) } != observed ||
                (duplicate != null && (DigitalStorage.identity(live) != duplicate || DigitalStorage.read(live) == null ||
                    DigitalStorage.identity(stage) != stageProof || DigitalStorage.read(stage) == null)))
                return@withImportDiscardLock false
            DigitalStorage.forget(stage)
            DigitalStorage.deleteOwnedTree(stage).also { if (it) Durable.syncDir(stage.parentFile!!) }
        }
    }

    /** Full hashes off-lock. Brief writer lock only for identity recheck and publication. */
    fun recover(now: Long = System.currentTimeMillis()): Int {
        var count = 0
        // Populate proof cache for existing committed authorities before library load.
        storage.liveDirs().forEach { dir ->
            val doc = DigitalStorage.verify(dir)
            if (doc != null && File(dir, READY).exists()) {
                val prepared = verifiedSeal(dir, full = false)
                repo.withImportDiscardLock {
                    if (prepared != null && DigitalStorage.identity(dir) == prepared.identity &&
                        readMarker(File(dir, READY), 512) == prepared.sealText) {
                        try { Durable.syncDir(dir.parentFile!!); finishReceipt(dir); count++ }
                        catch (_: IOException) { /* leave verified authority intact */ }
                    }
                }
            }
        }
        var reclaimed = 0
        storage.dirs(DigitalStorage.STAGING).forEach { stage ->
            val idle = repo.withImportDiscardLock { stage.path !in active }
            if (!idle || !DigitalStorage.safeId(stage.name)) return@forEach
            val prepared = if (File(stage, READY).exists()) verifiedSeal(stage, full = true) else null
            if (prepared != null) {
                repo.withImportDiscardLock {
                    if (stage.path !in active) {
                        try { publishSealed(stage, prepared); count++ } catch (_: IOException) { /* retain */ }
                    }
                }
            } else if (reclaimed < MAX_RECLAIMS && reclaim(stage, now)) {
                reclaimed++
                count++
            }
        }
        return count
    }
}
