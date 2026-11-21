package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** I1 defect 3: page files keep numeric order past 999, where %03d becomes four digits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScanRepoPageOrderTest {

    @Test
    fun `one thousand and one pages order 999 before 1000`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = ScanRepository(app)
        val dir = File(app.cacheDir, "i1_order_${System.nanoTime()}").apply { mkdirs() }
        try {
            (0..1000).forEach { i ->
                File(dir, String.format(java.util.Locale.ROOT, "page_%03d.jpg", i)).writeBytes(byteArrayOf(1))
            }
            val names = repo.pageFilesIn(dir).map { it.name }
            assertEquals(1001, names.size)
            assertEquals("page_000.jpg", names.first())
            assertEquals("page_1000.jpg", names.last())
            assertTrue("page_999 must come before page_1000", names.indexOf("page_999.jpg") < names.indexOf("page_1000.jpg"))
            assertEquals((0..1000).map { String.format(java.util.Locale.ROOT, "page_%03d.jpg", it) }, names)
        } finally {
            dir.deleteRecursively()
        }
    }
}
