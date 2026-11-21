package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.Durable
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * U1b findings against the merged UX-007 escrow. A reboot resets elapsedRealtime but not the
 * wall clock, so an Undo window that outlived a restart must be decided from the persisted wall
 * deadline: expired -> purge, unexpired -> keep restorable. The same boot stays monotonic-only so
 * a wall-clock adjustment can never cut a promised Undo short or extend it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImportDiscardRebootTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private var elapsed = 1_000L
    private var boot = 7
    private var wall = 1_700_000_000_000L
    private var wallInjectable = true
    private fun digest(id: String) = PdfInvariants.dirDigest(File(repo.docsDir, id))

    /**
     * Reflection keeps the RED run compiling against the base four-argument constructor. On the
     * fixed code the injected wall clock makes the reboot tests deterministic; on base the one
     * expiry test falls back to a real 8-second wait so it still REDs on behaviour, not on a
     * missing constructor.
     */
    private fun store(): ImportDiscardStore {
        val injected = ImportDiscardStore::class.java.declaredConstructors
            .firstOrNull { it.parameterCount == 5 }
        return if (injected != null) {
            injected.isAccessible = true
            injected.newInstance(app.applicationContext, repo, { elapsed }, { boot }, { wall })
                as ImportDiscardStore
        } else {
            wallInjectable = false
            ImportDiscardStore(app, repo, { elapsed }, { boot })
        }
    }

    @After fun reset() {
        ImportDiscardStore.fault = {}
        Durable.resetForTests()
    }

    @Test fun `reboot after the wall deadline purges instead of resurrecting the import`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val token = store().discard(id, 8_000)
        if (wallInjectable) wall += 9_000 else Thread.sleep(8_100)
        boot++
        elapsed = 0L // a real reboot restarts the monotonic clock
        assertTrue("an expired escrow must never be restored by recovery", store().recover().isEmpty())
        assertFalse(File(app.filesDir, "import_discard/${token.key}").exists())
        assertNull("the discarded import must not reappear in the Library", repo.getDocument(id))
        assertNull(store().restore(token.key))
        assertTrue(repo.listTrash().isEmpty())
    }

    @Test fun `reboot before the wall deadline keeps a restorable token with the reduced window`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val before = digest(id)
        store().discard(id, 8_000)
        boot++
        elapsed = 0L
        if (wallInjectable) wall += 3_000
        val pending = store().recover()
        val token: ImportDiscardStore.Token = pending.singleOrNull()
            ?: throw AssertionError("a live escrow must survive reboot as a pending Undo")
        assertEquals("only the unexpired part of the original window remains", 5_000L,
            store().remainingMs(token))
        assertEquals(id, store().restore(token.key))
        assertEquals(before, digest(id))
    }

    @Test fun `same boot ignores wall clock jumps so Undo is neither cut short nor extended`() {
        val first = DocFixtures.saved(repo, app, pages = 1)
        val firstToken = store().discard(first, 8_000)
        wall -= 60_000L // clock moved backwards inside the same boot
        elapsed += 3_000L
        assertEquals(5_000L, store().remainingMs(firstToken))
        assertEquals(first, store().restore(firstToken.key))

        val second = DocFixtures.saved(repo, app, pages = 1)
        val secondToken = store().discard(second, 8_000)
        wall += 120_000L // clock moved forwards inside the same boot
        elapsed += 3_000L
        assertEquals(5_000L, store().remainingMs(secondToken))
        assertEquals(second, store().restore(secondToken.key))
    }

    @Test fun `new imports never reuse an id still waiting in escrow`() {
        val id = DocFixtures.saved(repo, app, pages = 1)
        store().discard(id, 8_000)
        val free = allocateId(repo, listOf(id, "abcdef12"))
        assertEquals("the escrowed id must be skipped", "abcdef12", free)
        assertFalse(File(repo.docsDir, free).exists())
    }

    @Test fun `a fault after the durable commit still returns the token and Undo works`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val before = digest(id)
        ImportDiscardStore.fault = {
            if (it == ImportDiscardStore.Stage.COMMITTED) throw SimulatedDeath()
        }
        val token = store().discard(id, 8_000) // PENDING is durable: this is a success receipt
        ImportDiscardStore.fault = {}
        assertEquals(listOf(token), store().pending())
        assertTrue(store().remainingMs(token) > 0)
        assertEquals(id, store().restore(token.key))
        assertEquals(before, digest(id))
    }

    /** Drives the id allocator deterministically; the seam is part of freeLibraryId itself. */
    private fun allocateId(repo: ScanRepository, candidates: List<String>): String {
        val method = try {
            ScanRepository::class.java.getDeclaredMethod("freeLibraryId", Function0::class.java)
        } catch (e: NoSuchMethodException) {
            throw AssertionError("freeLibraryId must consult ids waiting in import_discard")
        }
        method.isAccessible = true
        val iterator = candidates.iterator()
        return method.invoke(repo, { iterator.next() }) as String
    }

    private class SimulatedDeath : Error()
}
