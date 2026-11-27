package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SelectionExtractProtectionTest {
    @Test
    fun selectionExtractRejectsVaultedSourceAndDraft() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app)
        repo.draftRotate(id, 0)
        repo.setVaulted(id, true)
        val before = repo.listDocuments(true).map { it.id }.toSet()
        assertTrue(runCatching { repo.extractPages(id, listOf(0)) }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { repo.saveDraftAsNew(id, "Extract", listOf(0)) }.exceptionOrNull() is java.io.IOException)
        assertEquals(before, repo.listDocuments(true).map { it.id }.toSet())
        assertNotNull(repo.draftOf(id))
        repo.setVaulted(id, false)
        val copy = repo.extractPages(id, listOf(0))
        assertEquals(1, copy.pageFiles.size)
    }
}
