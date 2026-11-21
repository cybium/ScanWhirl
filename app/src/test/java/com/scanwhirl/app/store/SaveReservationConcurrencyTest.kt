package com.scanwhirl.app.store

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.ImportFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * G1 independent review P1: startup recovery runs asynchronously while the owner can already save.
 * The orphan-reservation cleanup must take the SAME SAVE_LOCK a save holds from reservation to
 * publication, and must re-check emptiness under that lock, or it can delete a live save's folder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SaveReservationConcurrencyTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() = Faults.resetAll()

    private fun pageUri(): Uri {
        val file = File(app.cacheDir, "g1_live_${System.nanoTime()}.jpg")
        file.writeBytes(ImportFixtures.image(width = 200, height = 300))
        return Uri.fromFile(file)
    }

    /** Starts a real save that pauses at [step] while still holding SAVE_LOCK. */
    private fun pauseSaveAt(step: String): PausedSave {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<Result<ScanDocument>?>(null)
        val thread = Thread({
            SaveFaults.hook = { s ->
                if (s == step) {
                    reached.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
            result.set(runBlocking { runCatching { ScanRepository(app).saveScan(listOf(pageUri()), null, "Live") } })
        }, "g1-save-$step")
        thread.isDaemon = true
        thread.start()
        check(reached.await(10, TimeUnit.SECONDS)) {
            "save never reached $step; failure=${result.get()?.exceptionOrNull()}"
        }
        return PausedSave(thread, release, result)
    }

    /** Starts recovery on another thread; returns whether it finished within [waitMs]. */
    private fun startRecovery(waitMs: Long): Pair<Thread, Boolean> {
        val done = CountDownLatch(1)
        val thread = Thread({ ScanRepository(app).recoverOperations(); done.countDown() }, "g1-recovery")
        thread.isDaemon = true
        thread.start()
        return thread to done.await(waitMs, TimeUnit.MILLISECONDS)
    }

    private fun onlyDocDir(): File {
        val dirs = File(app.filesDir, "documents").listFiles { it -> it.isDirectory }!!
        assertEquals("exactly one reserved document dir", 1, dirs.size)
        return dirs.single()
    }

    private class PausedSave(
        val thread: Thread,
        val release: CountDownLatch,
        val result: AtomicReference<Result<ScanDocument>?>
    )

    /** Crash window 1: the reservation exists but no marker has been written yet. */
    @Test
    fun `recovery cannot delete a live save paused at reservation`() {
        val save = pauseSaveAt("reserved")
        val reserved = onlyDocDir()
        val (recovery, finishedEarly) = startRecovery(1_500)
        assertFalse("cleanup must wait for SAVE_LOCK", finishedEarly)
        assertTrue("the live reservation is untouched", reserved.exists())
        save.release.countDown()
        save.thread.join(20_000)
        recovery.join(20_000)
        assertTrue("the save published: ${save.result.get()?.exceptionOrNull()}", save.result.get()?.isSuccess == true)
        assertTrue("the published document survives", reserved.exists())
    }

    /** Crash window 2: the marker exists but the save has not written its pages yet. */
    @Test
    fun `recovery cannot delete a live save paused at marker creation`() {
        val save = pauseSaveAt("marked")
        val reserved = onlyDocDir()
        assertTrue("the marker is on disk", SaveFormat.isIncomplete(reserved))
        val (recovery, finishedEarly) = startRecovery(1_500)
        assertFalse("cleanup must wait for SAVE_LOCK", finishedEarly)
        assertTrue("the live reservation is untouched", reserved.exists())
        save.release.countDown()
        save.thread.join(20_000)
        recovery.join(20_000)
        assertTrue("the save published: ${save.result.get()?.exceptionOrNull()}", save.result.get()?.isSuccess == true)
        assertTrue("the published document survives", reserved.exists())
    }

    /** Crash window 3: fully prepared, marker still present, right before publication. */
    @Test
    fun `recovery cannot delete a prepared save paused before publication`() {
        val save = pauseSaveAt("before_publish")
        val reserved = onlyDocDir()
        val before = reserved.walkTopDown().filter { it.isFile }.associate { it.name to it.readBytes().toList() }
        assertTrue("a prepared save has a PDF", before.containsKey("scan.pdf"))
        val (recovery, finishedEarly) = startRecovery(1_500)
        assertFalse("cleanup must wait for SAVE_LOCK", finishedEarly)
        assertEquals("prepared bytes are untouched", before,
            reserved.walkTopDown().filter { it.isFile }.associate { it.name to it.readBytes().toList() })
        save.release.countDown()
        save.thread.join(20_000)
        recovery.join(20_000)
        assertTrue("the save published: ${save.result.get()?.exceptionOrNull()}", save.result.get()?.isSuccess == true)
        assertTrue("the published document survives", reserved.exists())
    }
}
