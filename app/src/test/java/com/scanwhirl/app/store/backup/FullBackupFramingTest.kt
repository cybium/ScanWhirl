package com.scanwhirl.app.store.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** F4: a file whose size changes while it is written must fail the backup, not corrupt it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FullBackupFramingTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun lyingFile(real: File, reported: Long) = object : File(real.path) {
        override fun length(): Long = reported
    }

    private fun writeEntry(file: File): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { FullBackup(app).writeFileEntryForTest(it, "docs/x/page_000.jpg", file) }
        return bytes.toByteArray()
    }

    @Test
    fun `an unchanged file is framed with exactly its bytes`() {
        val f = File(app.cacheDir, "frame_ok.bin").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        val out = writeEntry(f)
        val input = DataInputStream(out.inputStream())
        input.readByte()
        assertEquals("docs/x/page_000.jpg", input.readUTF())
        val n = input.readLong()
        assertEquals(f.length(), n)
        val body = ByteArray(n.toInt()).also { input.readFully(it) }
        assertArrayEquals(f.readBytes(), body)
        assertEquals("nothing after the declared bytes", -1, input.read())
    }

    @Test
    fun `a file that grew after its size was read fails instead of corrupting the archive`() {
        val f = File(app.cacheDir, "frame_grew.bin").apply { writeBytes(ByteArray(10_000) { 7 }) }
        val failed = runCatching { writeEntry(lyingFile(f, 4_000)) }.exceptionOrNull()
        assertTrue("expected IOException, got $failed", failed is IOException)
    }

    @Test
    fun `a file that shrank after its size was read fails instead of corrupting the archive`() {
        val f = File(app.cacheDir, "frame_shrank.bin").apply { writeBytes(ByteArray(3_000) { 7 }) }
        val failed = runCatching { writeEntry(lyingFile(f, 9_000)) }.exceptionOrNull()
        assertTrue("expected IOException, got $failed", failed is IOException)
    }
}
