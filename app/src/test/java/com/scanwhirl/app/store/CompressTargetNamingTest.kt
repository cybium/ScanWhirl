package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.ImportFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * H3-6 (naming): Compress-to-target names its copy "<name> (compressed)" without asking for a
 * unique name, so two runs produced two indistinguishable documents. The repository now applies
 * the same " (2)" rule every other tool output uses, whatever name the flow passes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CompressTargetNamingTest {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a second target copy is uniquely named`() = runBlocking {
        val repo = ScanRepository(app)
        val page = listOf(ImportFixtures.image(width = 200, height = 300))

        val first = repo.createDocument("Rent receipt (compressed)", page)
        val second = repo.createDocument("Rent receipt (compressed)", page)

        assertEquals("Rent receipt (compressed)", first.name)
        assertNotEquals("two copies must be distinguishable", first.name, second.name)
        assertEquals("Rent receipt (compressed) (2)", second.name)
        assertEquals(
            "the library has two different names",
            2,
            repo.listDocuments().map { it.name }.distinct().size
        )
    }

    @Test
    fun `the flow names a target copy through the shared output rule`() = runBlocking {
        val repo = ScanRepository(app)
        val page = listOf(ImportFixtures.image(width = 200, height = 300))

        // What CompressFlow passes after the fix: the shared unique name, never a raw suffix.
        val first = repo.createDocument(repo.outputName("Rent receipt", "Compressed"), page)
        val second = repo.createDocument(repo.outputName("Rent receipt", "Compressed"), page)

        assertNotEquals(first.name, second.name)
        assertEquals("Rent receipt - Compressed", first.name)
        assertEquals("Rent receipt - Compressed (2)", second.name)
    }
}
