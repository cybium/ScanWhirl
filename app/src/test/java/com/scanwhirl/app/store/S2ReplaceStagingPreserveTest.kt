package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

/**
 * S2 item 8: a Replace dies between its two renames; a transient failure in the next startup's
 * roll-forward step must NOT let the staging sweep delete the sealed `.publish` replacement (the
 * user's only copy of the new version). It is preserved, reported incomplete, and published on the
 * next start.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class S2ReplaceStagingPreserveTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After
    fun tearDown() = Faults.resetAll()

    private fun interruptReplace(): Pair<String, ByteArray> {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Interrupted Replace")
        val retainedPage = requireNotNull(repo.getDocument(id)).pageFiles.last().readBytes()
        runBlocking { repo.draftDelete(id, 0) }
        val death = Faults.dieAtOp("replace:base_trashed")
        runCatching { runBlocking { repo.replaceWithDraft(id) } }
        assertTrue("the Replace must have been interrupted after the base went to Trash", death.fired)
        Faults.resetAll()
        assertFalse(File(repo.docsDir, id).exists())
        return id to retainedPage
    }

    private fun onlyStagingDir(): File =
        File(app.filesDir, OpStaging.DIR).listFiles { it -> it.isDirectory }.orEmpty().single()

    @Test
    fun `a failed roll forward keeps the sealed replacement and publishes it on the next start`() {
        val (id, retainedPage) = interruptReplace()
        val staged = onlyStagingDir()
        assertTrue("the sealed replacement is staged", File(staged, OpStaging.SEAL).isFile)

        // First startup: a transient failure in the replace step (hash/read failure equivalent).
        ScanRepository.recoveryStepFault = { step ->
            if (step == "replace") throw IOException("transient hash failure")
        }
        ScanRepository.recoveryIncomplete = false
        repo.recoverOperations()

        assertTrue("the failed step must be exposed as incomplete", ScanRepository.recoveryIncomplete)
        assertTrue(
            "the sealed replacement must survive a failed roll-forward, not be swept",
            staged.exists()
        )
        assertFalse("the replacement is not published yet", File(repo.docsDir, id).exists())

        // Second startup: the step runs cleanly and rolls the preserved payload forward.
        ScanRepository.recoveryStepFault = null
        repo.recoverOperations()

        val restored = requireNotNull(repo.getDocument(id)) { "the document is back in the Library" }
        assertArrayEquals("the replacement's retained page is intact", retainedPage, restored.pageFiles.single().readBytes())
        assertTrue("op_staging is empty after a successful publish", File(app.filesDir, OpStaging.DIR).listFiles().isNullOrEmpty())
    }
}
