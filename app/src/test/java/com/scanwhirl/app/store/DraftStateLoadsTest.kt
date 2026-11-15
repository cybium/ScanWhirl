package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * L5-2: the viewer needs the document, its pages and its draft. `draftState` reads and parses the
 * draft manifest once; the old `draftPages` + `draftOf` pair parsed it twice.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DraftStateLoadsTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
    }

    @After
    fun tearDown() {
        DraftStore.loadProbe = null
    }

    private fun draftDoc(): String {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "State doc")
        runBlocking { repo.draftRotate(id, 0, clockwise = true) }
        return id
    }

    @Test
    fun `draftState parses the manifest once and returns the draft pages`() {
        val id = draftDoc()
        var loads = 0
        DraftStore.loadProbe = { loads++ }

        val state = runBlocking { repo.draftState(id) }

        assertEquals("one manifest parse", 1, loads)
        assertNotNull("the document is included", state.document)
        assertEquals(3, state.pages.size)
        assertNotNull("the draft is included", state.draft)
        assertTrue(
            "an edited page is the replacement file: ${state.pages[0].name}",
            state.pages[0].name.startsWith("r_")
        )
        assertNull(state.damage)

        // The old pair did the same work with two parses (kept for other callers).
        loads = 0
        runBlocking {
            repo.draftPages(id)
            repo.draftOf(id)
        }
        assertEquals("draftPages + draftOf parsed twice", 2, loads)
    }

    @Test
    fun `a damaged draft yields the base pages and the failure, never a throw`() {
        val id = draftDoc()
        val manifest = File(File(app.filesDir, DraftStore.DIR), "$id/draft.txt")
        assertTrue("the harness can damage the manifest", manifest.isFile)
        manifest.writeText("scanwhirl-draft-v1\nbase=$id\ncreated=0\n")

        val state = runBlocking { repo.draftState(id) }

        assertNotNull("the damage is reported", state.damage)
        assertNull("no partial draft is invented", state.draft)
        assertEquals("the base pages are shown", 3, state.pages.size)
        assertTrue(state.pages.none { it.name.startsWith("r_") })
    }
}
