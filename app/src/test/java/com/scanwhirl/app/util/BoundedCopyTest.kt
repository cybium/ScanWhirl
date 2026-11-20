package com.scanwhirl.app.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoundedCopyTest {
    @get:Rule val folder = TemporaryFolder()
    private fun target() = java.io.File(folder.root, "scratch.pdf")
    private val plenty: (java.io.File) -> Long = { Long.MAX_VALUE }

    @Test fun `endless stream stops just past cap with no partial file`() = runBlocking {
        val file = target()
        var read = 0
        val endless = object : InputStream() {
            override fun read() = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                read += len
                return len
            }
        }
        assertEquals(BoundedCopy.Result.TOO_LARGE, BoundedCopy.copy(file, 100_000, plenty) { endless })
        assertEquals(100_001, read)
        assertFalse(file.exists())
    }

    @Test fun `low space refuses before opening provider`() = runBlocking {
        val file = target()
        assertEquals(BoundedCopy.Result.NO_SPACE,
            BoundedCopy.copy(file, 100, { BoundedCopy.HEADROOM }) { error("must not open") })
        assertFalse(file.exists())
    }

    @Test fun `space running low during copy removes partial file`() = runBlocking {
        val file = target()
        var probes = 0
        assertEquals(BoundedCopy.Result.NO_SPACE,
            BoundedCopy.copy(file, 200_000, {
                if (++probes <= 2) Long.MAX_VALUE else 0L
            }) { ByteArrayInputStream(ByteArray(180_000)) })
        assertTrue(probes >= 3)
        assertFalse(file.exists())
    }

    @Test fun `cancel during blocking copy closes stream and removes partial`() = runBlocking {
        val file = target()
        val secondRead = CompletableDeferred<Unit>()
        var closed = false
        var reads = 0
        val input = object : InputStream() {
            override fun read() = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (++reads > 1) {
                    secondRead.complete(Unit)
                    CountDownLatch(1).await(10, TimeUnit.SECONDS)
                }
                return len
            }
            override fun close() { closed = true }
        }
        val job = async(Dispatchers.Default) { BoundedCopy.copy(file, 1_000_000, plenty) { input } }
        secondRead.await()
        job.cancelAndJoin()
        assertTrue(closed)
        assertFalse(file.exists())
    }

    @Test fun `read failure and empty input leave no file`() = runBlocking {
        val file = target()
        assertEquals(BoundedCopy.Result.UNREADABLE,
            BoundedCopy.copy(file, 100, plenty) { object : InputStream() {
                override fun read(): Int = throw IOException("fictional provider failed")
            } })
        assertFalse(file.exists())
        assertEquals(BoundedCopy.Result.UNREADABLE,
            BoundedCopy.copy(file, 100, plenty) { ByteArrayInputStream(byteArrayOf()) })
        assertFalse(file.exists())
    }

    @Test fun `exact cap succeeds and retains only until released`() = runBlocking {
        val file = target()
        assertEquals(BoundedCopy.Result.OK,
            BoundedCopy.copy(file, 100, plenty) { ByteArrayInputStream(ByteArray(100) { 3 }) })
        assertEquals(100L, file.length())
        assertTrue(BoundedCopy.isOwned(file))
        BoundedCopy.release(file)
        assertFalse(file.exists())
        assertFalse(BoundedCopy.isOwned(file))
    }
}
