package com.scanwhirl.app.store

import com.scanwhirl.app.model.PageRef
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.Sha256
import java.io.File
import java.io.IOException

/** New-copy transaction only. Replace/split and protection are later slices. */
internal class DigitalTransaction(private val repo: ScanRepository) {
    private val storage get() = repo.digitalStorage
    companion object {
        private val active = mutableSetOf<String>() // under repository writer locks
        private const val READY = ".ready"
        private const val INTENT = ".intent"
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
        val hash = Sha256.of(source)
        return transact(token, token, hash, source.length(), "import") { stage ->
            storage.writeVerified(stage, source, name, token, verify)
        }
    }

    /** Re-adopt an authenticated, validated backup authority, even with import disabled. */
    fun restore(input: File): ScanDocument {
        val doc = DigitalStorage.read(input) ?: throw IOException("Digital backup unavailable")
        val id = input.name
        require(DigitalStorage.safeId(id))
        val generation = requireNotNull(doc.contentGeneration)
        val hash = requireNotNull(doc.contentSha256)
        return transact(id, generation, hash, doc.cachedSize, "restore") { stage ->
            for (member in DigitalStorage.members) {
                Durable.copyFile(File(input, member), File(stage, member))
                OpFaults.at("digital:restore_member:$member")
            }
            val meta = File(stage, DigitalStorage.META)
            val lines = DigitalStorage.boundedLines(meta, DigitalStorage.MAX_META_BYTES)
            // Export ownership is phone-local, not portable authority.
            Durable.atomicWriteText(meta, (lines.take(3) + lines.drop(3).filterNot {
                it.startsWith("exports=") || it.startsWith("exportsigs=") || it.startsWith("exportmade=")
            }).joinToString("\n", postfix = "\n"))
            DigitalStorage.read(stage) ?: throw IOException("Digital restore verification failed")
        }
    }

    private fun transact(id: String, generation: String, hash: String, bytes: Long, operation: String,
                         build: (File) -> ScanDocument): ScanDocument {
        val stage = storage.child(DigitalStorage.STAGING, id)
        val intent = "scanwhirl-digital-intent-v1\n$id\n$generation\n$hash\n$operation\n"
        val replay = repo.withImportDiscardLock {
            val live = storage.child(DigitalStorage.LIVE, id)
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
                    return@withImportDiscardLock publishSealed(stage)
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
            return repo.withImportDiscardLock {
                repo.resolveNameBeforePublish(stage)
                val checked = DigitalStorage.read(stage) ?: throw IOException("Digital verification failed")
                Durable.syncDir(stage)
                OpFaults.at("digital:before_seal")
                Durable.atomicWriteText(File(stage, READY), seal(checked))
                sealed = true
                Durable.syncDir(stage)
                OpFaults.at("digital:sealed")
                publishSealed(stage)
            }
        } catch (e: Exception) {
            if (!sealed && stage.exists()) stage.deleteRecursively()
            throw e
        } finally {
            repo.withImportDiscardLock { active.remove(stage.path) }
        }
    }

    private fun seal(doc: ScanDocument): String =
        "scanwhirl-digital-ready-v1\n${doc.id}\n${doc.contentGeneration}\n${doc.contentSha256}\n" +
            Sha256.of(File(doc.pdfFile.parentFile, DigitalStorage.PAGES)) + "\n"

    private fun readMarker(file: File, limit: Long): String? =
        try { DigitalStorage.boundedLines(file, limit).joinToString("\n", postfix = "\n") }
        catch (_: Exception) { null }

    private fun verifiedSeal(stage: File): ScanDocument? {
        val doc = DigitalStorage.read(stage) ?: return null
        return doc.takeIf { readMarker(File(stage, READY), 512) == seal(it) }
    }

    private fun finishReceipt(dir: File) {
        // Remove intent first; if killed the ready seal still authorizes completion.
        for (name in listOf(INTENT, READY)) {
            val marker = File(dir, name)
            if (marker.exists() && !marker.delete()) throw IOException("Cannot finish digital receipt")
            Durable.syncDir(dir)
        }
    }

    private fun publishSealed(stage: File): ScanDocument {
        val doc = verifiedSeal(stage) ?: throw IOException("Digital transaction is not verified")
        val dest = storage.child(DigitalStorage.LIVE, stage.name)
        if (dest.exists() || repo.digitalIdReserved(stage.name, ignoreStage = true))
            throw IOException("Digital identity conflict")
        repo.resolveNameBeforePublish(stage)
        OpFaults.at("digital:before_rename")
        if (!stage.renameTo(dest)) throw IOException("Cannot publish digital document")
        OpFaults.at("digital:renamed")
        Durable.syncDir(dest.parentFile!!)
        Durable.syncDir(stage.parentFile!!)
        OpFaults.at("digital:parents_synced")
        finishReceipt(dest)
        OpFaults.at("digital:receipt_finished")
        return DigitalStorage.read(dest)?.takeIf { it.contentSha256 == doc.contentSha256 }
            ?: throw IOException("Digital document unavailable")
    }

    /** Never purges an unproven directory or overwrites a live authority. */
    fun recover(): Int = repo.withImportDiscardLock {
        var count = 0
        storage.dirs(DigitalStorage.STAGING).forEach { stage ->
            if (stage.path !in active && DigitalStorage.safeId(stage.name) && verifiedSeal(stage) != null) {
                try { publishSealed(stage); count++ } catch (_: IOException) { /* retain for repair */ }
            }
        }
        storage.liveDirs().forEach { dir ->
            if (File(dir, READY).exists() && verifiedSeal(dir) != null) {
                try { Durable.syncDir(dir.parentFile!!); finishReceipt(dir); count++ }
                catch (_: IOException) { /* leave verified authority intact */ }
            }
        }
        count
    }
}
