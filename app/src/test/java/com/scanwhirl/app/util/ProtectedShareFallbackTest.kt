package com.scanwhirl.app.util

import android.app.Activity
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.ScanDocument
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * S2 (1.3.1). Harm prevented: a password-protected or Vault document whose PDF is missing
 * would be shared as its plaintext page JPEGs by the whole-document Share fallback.
 * The unprotected control proves the fallback still works for ordinary documents, so the
 * protected assertion cannot pass merely because sharing is broken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtectedShareFallbackTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun resetFileProviderCache() {
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    private fun docWithoutPdf(locked: Boolean, vaulted: Boolean): ScanDocument {
        val dir = File(app.filesDir, "documents/fallback-" + System.nanoTime()).apply { mkdirs() }
        val page = File(dir, "page_001.jpg").apply { writeBytes(ImportFixtures.image()) }
        return ScanDocument(
            id = dir.name, name = "Fictional form", createdAt = 0L, pageCount = 1,
            pdfFile = File(dir, "scan.pdf"), pageFiles = listOf(page),
            isLocked = locked, isVaulted = vaulted
        )
    }

    private fun startedAfterShare(doc: ScanDocument): Intent? {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            ShareUtils.share(controller.get(), doc)
            // R09 F3: the page-fallback chooser is posted after the IO copies; one idle() races
            // it. Wait a bounded time, then return null just as before for the protected cases.
            val deadline = System.currentTimeMillis() + 5_000L
            var started: Intent? = null
            while (System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                started = shadowOf(controller.get()).nextStartedActivity
                if (started != null) break
                Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
            if (started == null) started = shadowOf(controller.get()).nextStartedActivity
            return started
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun unprotectedDocumentWithoutPdfStillSharesPages() {
        assertNotNull("control: ordinary documents keep the page fallback", startedAfterShare(docWithoutPdf(false, false)))
    }

    @Test fun lockedDocumentWithoutPdfNeverSharesPlaintextPages() {
        assertNull("a locked document must not leave as page JPEGs", startedAfterShare(docWithoutPdf(true, false)))
    }

    @Test fun vaultDocumentWithoutPdfNeverSharesPlaintextPages() {
        assertNull("a Vault document must not leave as page JPEGs", startedAfterShare(docWithoutPdf(false, true)))
    }
}
