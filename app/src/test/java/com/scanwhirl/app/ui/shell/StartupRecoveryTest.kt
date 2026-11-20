package com.scanwhirl.app.ui.shell

import android.os.Looper
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.MainActivity
import com.scanwhirl.app.util.Durable
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StartupRecoveryTest {
    private val app: ScanWhirlApp get() = ApplicationProvider.getApplicationContext()
    private val repo: ScanRepository get() = app.repository

    @After
    fun cleanUp() = Faults.resetAll()

    @Test
    fun shellColdStartRecoversReplaceAndExpiresOrdinaryTrashOffMain() {
        val (id, retainedPage) = interruptReplace()
        val expired = trash("Expired").apply {
            val marker = File(this, ScanRepository.TRASH_MARKER)
            val lines = marker.readLines().toMutableList()
            lines[2] = (System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000).toString()
            marker.writeText(lines.joinToString("\n", postfix = "\n"))
        }
        val undated = trash("Undated")
        val undatedMarker = File(undated, ScanRepository.TRASH_MARKER)
        assertTrue(undatedMarker.delete())
        val recoveryThread = AtomicReference<Thread>()
        Durable.beforeFileSync = {
            if (it.parentFile == undated && it.name == "${ScanRepository.TRASH_MARKER}.tmp") {
                recoveryThread.set(Thread.currentThread())
            }
        }

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            await("launcher never showed the recovered Library card") {
                var shown = false
                scenario.onActivity { shown = it.findViewById<ViewGroup>(R.id.swDocList)?.childCount == 1 }
                shown
            }
            val recovered = requireNotNull(repo.getDocument(id))
            assertArrayEquals(retainedPage, recovered.pageFiles.single().readBytes())
            val original = repo.listTrash().single { it.originalId == id }
            assertTrue(original.previousToken != null)
            assertEquals(2, original.document.pageCount)
            assertFalse("31-day-old marked entry still present", expired.exists())
            assertTrue("undated entry still has no recorded time", undatedMarker.isFile)
            assertNotSame(Looper.getMainLooper().thread, requireNotNull(recoveryThread.get()))

            // Recreating the launcher in the same process must not start another recovery pass.
            val late = trash("After startup").apply {
                assertTrue(File(this, ScanRepository.TRASH_MARKER).delete())
            }
            scenario.recreate()
            await("recreated launcher never loaded the Library") {
                var shown = false
                scenario.onActivity { shown = it.findViewById<ViewGroup>(R.id.swDocList)?.childCount == 1 }
                shown
            }
            // Don't call listTrash here: first sight there can also create the marker.
            assertFalse(File(late, ScanRepository.TRASH_MARKER).exists())
        }
    }

    @Test
    fun legacyHomeStillRollsForwardInterruptedReplace() {
        val (id, retainedPage) = interruptReplace()
        ActivityScenario.launch(MainActivity::class.java).use {
            await("legacy Home did not recover Replace") { repo.getDocument(id) != null }
            assertArrayEquals(retainedPage, requireNotNull(repo.getDocument(id)).pageFiles.single().readBytes())
        }
    }

    private fun interruptReplace(): Pair<String, ByteArray> {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Interrupted Replace")
        val retainedPage = requireNotNull(repo.getDocument(id)).pageFiles.last().readBytes()
        repo.draftDelete(id, 0)
        val death = Faults.dieAtOp("replace:base_trashed")
        runCatching { runBlocking { repo.replaceWithDraft(id) } }
        assertTrue("fixture did not reach the interrupted Replace window", death.fired)
        assertFalse(File(repo.docsDir, id).exists())
        Faults.resetAll()
        return id to retainedPage
    }

    private fun trash(name: String): File {
        val id = DocFixtures.saved(repo, app, pages = 1, name = name)
        return File(repo.trashDir, requireNotNull(repo.moveToTrash(id)))
    }

    private fun await(message: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (ready()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        throw AssertionError(message)
    }
}
