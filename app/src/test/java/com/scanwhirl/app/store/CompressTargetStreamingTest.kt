package com.scanwhirl.app.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * H3-2 (bug hunt 2026-11-20), the regression guard that can be read off the source: the
 * document-screen Compress sheet must never go back to the all-at-once helpers.
 *
 * `SizeFit.compressPages`/`compressBytes` build a `List<ByteArray>` for every page — a 2000-page
 * book held the whole document (hundreds of MB) plus a second copy — which is exactly what
 * `ScanRepository.compressScanToTarget` was written to replace. Compress now runs through the
 * repository's streaming paths for both a saved scan and a picked PDF.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompressTargetStreamingTest {

    private val source: String
        get() = File("src/main/java/com/scanwhirl/app/tools/CompressFlow.kt")
            .readText(Charsets.UTF_8)

    @Test
    fun `the compress sheet does not build the whole document in memory`() {
        val flow = source
        assertFalse(
            "H3-2: the sheet calls the all-at-once helper again",
            flow.contains("SizeFit.compressPages")
        )
        assertFalse(
            "H3-2: the sheet calls the byte-list helper again",
            flow.contains("SizeFit.compressBytes")
        )
        assertTrue(
            "H3-2: a saved scan's target size goes through the streaming repository call",
            flow.contains("repo.compressScanToTargetForResult(")
        )
        assertTrue(
            "H3-2: a picked PDF's target size streams into staging through the repository",
            flow.contains("repo.importPdfToTarget(")
        )
    }
}
