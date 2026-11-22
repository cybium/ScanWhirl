package com.scanwhirl.app.tools

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.V2tResultReceiptStore
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.PdfImport
import com.scanwhirl.app.util.ResetDialogShadowsRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream
import java.io.File

/**
 * B3 (OwnerGPT01 report): external PDF compression must never treat an unknown/negative SAF
 * source length as zero. Passing `rejectNotSmallerThan = 0` disables the repository's staged
 * no-gain check, so a grown output could be published as "Compressed".
 *
 * Fictional fixtures only: a content URI whose bytes are readable but whose length is unknown
 * (no declared size / no file descriptor), plus the shared flat PDF-renderer shadows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class B3UnknownSourceSizeTest {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @Before fun setUp() {
        PdfImport.pageCountSeam = { 1 } // one-page fictional source, no real PDF parser needed
    }

    @After fun tearDown() {
        PdfImport.resetForTests()
        CompressFlow.estimateForTest = null
    }

    @Implements(PdfRenderer::class)
    class FlatRenderer {
        @Implementation fun __constructor__(fd: ParcelFileDescriptor) = Unit
        @Implementation fun getPageCount(): Int = 1
        @Implementation fun openPage(index: Int): PdfRenderer.Page =
            Shadow.newInstanceOf(PdfRenderer.Page::class.java)
        @Implementation fun close() = Unit
    }

    @Implements(PdfRenderer.Page::class)
    class FlatPage {
        @Implementation fun getWidth(): Int = 80
        @Implementation fun getHeight(): Int = 100
        @Implementation fun render(bitmap: Bitmap, clip: Rect?, matrix: Matrix?, mode: Int) {
            bitmap.eraseColor(Color.WHITE)
        }
        @Implementation fun close() = Unit
    }

    /** A streamable, valid-looking fictional PDF whose length is deliberately unknown. */
    private fun source(padding: Int): ByteArray =
        "%PDF-1.4\n".toByteArray(Charsets.US_ASCII) + ByteArray(padding) { 0x20.toByte() }

    /** content:// URI: no registered ParcelFileDescriptor (stat size unknown), stream readable. */
    private fun unknownLengthUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://b3.unknown.test/${System.nanoTime()}.pdf")
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    // ---- 1. the byte-count decision itself --------------------------------

    @Test
    fun `known provider length is kept unchanged`() = runBlocking {
        val file = File(app.cacheDir, "b3-known.pdf").apply { writeBytes(source(64)) }
        assertEquals(
            "a positive provider size is trusted as before",
            file.length(),
            CompressFlow.measuredSourceBytes(app, Uri.fromFile(file))
        )
    }

    @Test
    fun `unknown provider length counts the bytes actually read`() = runBlocking {
        val bytes = source(64)
        assertEquals(
            "not zero: the real streamed byte count is the source size",
            bytes.size.toLong(),
            CompressFlow.measuredSourceBytes(app, unknownLengthUri(bytes))
        )
    }

    @Test
    fun `a source that cannot be measured returns null so the caller fails closed`() = runBlocking {
        val uri = Uri.parse("content://b3.unknown.test/unreadable-${System.nanoTime()}.pdf")
        assertNull("no honest byte count is available", CompressFlow.measuredSourceBytes(app, uri))
    }

    // ---- 2. the real external-file flow -----------------------------------

    private data class Outcome(
        val uri: Uri,
        val started: Intent?,
        val toast: String?,
        val docs: List<String>
    )

    /** Drives startFromFile -> page gate -> compress sheet -> primary, exactly as the picker does. */
    private fun drive(bytes: ByteArray, header: String = "sheet"): Outcome {
        val uri = unknownLengthUri(bytes)
        var outcome: Outcome? = null
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                CompressFlow(activity, repo, activity, {}, { repo.listDocuments() })
                    .startFromFile(uri)
                assertTrue(
                    "$header: the compress sheet opens for a one-page device PDF",
                    pollUntil {
                        ShadowDialog.getLatestDialog()?.window?.decorView
                            ?.findViewById<View>(R.id.editCompressTarget) != null
                    }
                )
                val decor = requireNotNull(ShadowDialog.getLatestDialog()).window!!.decorView
                idle()
                decor.findViewById<View>(R.id.swSheetPrimary).performClick()
                pollUntil(30_000) { shadowOf(activity).peekNextStartedActivity() != null }
                val started = shadowOf(activity).peekNextStartedActivity()
                outcome = Outcome(uri, started, ShadowToast.getTextOfLatestToast(), repo.listDocuments().map { it.id })
            }
        }
        return requireNotNull(outcome)
    }

    @Test
    @Config(shadows = [FlatRenderer::class, FlatPage::class])
    fun `unknown length with a larger output publishes nothing and stays honest`() {
        val idsBefore = repo.listDocuments().map { it.id }.toSet()
        val bytes = source(48) // deliberately tiny: any rendered scan.pdf is larger
        val result = drive(bytes, "no-gain")
        assertEquals("no new document was indexed", idsBefore, result.docs.toSet())
        assertNotNull("an honest result screen is shown", result.started)
        assertEquals(
            "the no-gain result is honest, not a claimed compression",
            "NOT_SMALLER",
            result.started!!.getStringExtra("rs_outcome_class")
        )
        val receipt = requireNotNull(V2tResultReceiptStore(app).load()) { "a durable receipt is saved" }
        assertEquals("NOT_SMALLER", receipt.outcomeClass)
        assertEquals("the measured source size is recorded", bytes.size.toLong(), receipt.beforeBytes)
        assertTrue("nothing was published by the receipt", receipt.docIds.isEmpty())
        assertArrayEquals(
            "the source is byte-for-byte intact",
            bytes,
            app.contentResolver.openInputStream(result.uri)!!.use { it.readBytes() }
        )
    }

    @Test
    @Config(shadows = [FlatRenderer::class, FlatPage::class])
    fun `unknown length with a genuinely smaller output commits normally`() {
        val idsBefore = repo.listDocuments().map { it.id }.toSet()
        val bytes = source(200_000) // source dwarfs the one-page output
        val result = drive(bytes, "smaller")
        assertNotNull("a result screen is shown", result.started)
        val ids = result.started!!.getStringArrayListExtra("doc_ids").orEmpty()
        assertEquals("exactly one new document is published", 1, ids.size)
        assertEquals("the library gained one document", idsBefore.size + 1, result.docs.size)
        assertNotNull("the published document is real", repo.getDocument(ids.single()))
        assertArrayEquals(
            "the source is byte-for-byte intact",
            bytes,
            app.contentResolver.openInputStream(result.uri)!!.use { it.readBytes() }
        )
    }

    @Test
    fun `a source the provider cannot serve at all fails closed without a document`() {
        val idsBefore = repo.listDocuments().map { it.id }.toSet()
        val uri = Uri.parse("content://b3.unknown.test/closed-${System.nanoTime()}.pdf")
        var toast: String? = null
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                CompressFlow(activity, repo, activity, {}, { repo.listDocuments() }).startFromFile(uri)
                repeat(60) { idle(); Thread.sleep(20) }
                toast = ShadowToast.getTextOfLatestToast()
            }
        }
        assertEquals("no new document was indexed", idsBefore, repo.listDocuments().map { it.id }.toSet())
        assertTrue(
            "an honest short failure message, not a compressed result: $toast",
            toast?.contains(app.getString(R.string.f8_reason_unreadable)) == true
        )
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }
}
