package com.scanwhirl.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * H3-3 (wrong output): a stamped/signed copy kept the unmarked `.orig` beside its marked page.
 * Choosing any page filter (even Original) then rebuilt the page from that unmarked file and the
 * signature silently disappeared from a copy named "Signed".
 *
 * Any burned-in mark now drops the page's kept original, exactly like redaction already did.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StampKeptOriginalTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val repo get() = ScanRepository(context)

    @After
    fun reset() = Durable.resetForTests()

    @Test
    fun `a stamped copy cannot be reverted to the unmarked original`() = runBlocking {
        val id = DocFixtures.saved(repo, context, pages = 1, name = "Receipt")
        // A filter creates the kept original that a later change must not resurrect.
        repo.setPageFilter(id, 0, ScanFilter.ENHANCE)
        assertTrue("fixture needs the source kept original", File(File(repo.docsDir, id), "page_000.orig").exists())

        val out = repo.stampDocument(
            id,
            listOf(
                PageStamp.PageEdits(
                    0,
                    listOf(
                        PageStamp.Mark.Text(
                            "SIGNED",
                            PageStamp.Box(0.10f, 0.10f, 0.90f, 0.30f),
                            bold = true
                        )
                    )
                )
            ),
            null
        )
        val dir = File(repo.docsDir, out.id)
        val page = File(dir, "page_000.jpg")
        val stamped = page.readBytes()

        assertFalse(
            "H3-3: a marked page must not keep an unmarked .orig",
            File(dir, "page_000.orig").exists()
        )

        // Original is a no-op when no kept original exists: the signature must stay.
        repo.setPageFilter(out.id, 0, ScanFilter.ORIGINAL)
        assertArrayEquals(
            "Original must not resurrect the unmarked pixels",
            stamped,
            page.readBytes()
        )

        // A later filter re-bases on the marked page, never on the pre-stamp content.
        repo.setPageFilter(out.id, 0, ScanFilter.BLACKWHITE)
        assertArrayEquals(
            "the kept original after a filter must be the marked page",
            stamped,
            File(dir, "page_000.orig").readBytes()
        )
    }

    @Test
    fun `redaction still drops the kept original and the recognised text`() = runBlocking {
        val id = DocFixtures.saved(repo, context, pages = 1, name = "Aadhaar")
        DocFixtures.withOcr(repo, id)
        repo.setPageFilter(id, 0, ScanFilter.ENHANCE)

        val out = repo.stampDocument(
            id,
            listOf(
                PageStamp.PageEdits(
                    0,
                    listOf(PageStamp.Mark.Redact(PageStamp.Box(0.1f, 0.1f, 0.5f, 0.3f)))
                )
            ),
            null
        )
        val dir = File(repo.docsDir, out.id)
        assertFalse("redacted pixels must not survive", File(dir, "page_000.orig").exists())
        assertFalse("redacted text must not survive", "page_000.jpg" in repo.ocrDoc(out.id))
    }
}
