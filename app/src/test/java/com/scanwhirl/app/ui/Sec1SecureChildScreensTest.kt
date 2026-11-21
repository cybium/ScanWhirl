package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.SecureView
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * SECURITY_JUDGE 1.3.0 finding 1 (HIGH): FLAG_SECURE applies to the window, not
 * the task, so a page tap or a protected-copy preview opened a fresh unsecured
 * window over the same plaintext. Both child screens must resolve the document
 * protection from the id and apply the same policy as the parent viewer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Sec1SecureChildScreensTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)

    @Before
    fun setUp() {
        Prefs.setBlockDocScreenshots(app, false)
    }

    @After
    fun tearDown() {
        Prefs.setBlockDocScreenshots(app, false)
    }

    private fun lockedDoc(): String = DocFixtures.locked(repo, app, pages = 1)
    private fun normalDoc(): String = DocFixtures.saved(repo, app, pages = 1)

    private fun pageIntent(id: String): Intent = PageViewerActivity.intent(app, id, 0)

    private fun exportIntent(id: String): Intent {
        val preview = repo.getDocument(id)!!.pageFiles.first().absolutePath
        return ExportResultActivity.intent(
            app,
            outputPath = preview,
            previewPath = preview,
            mime = "application/pdf",
            displayName = "Doc",
            beforeBytes = 0L,
            afterBytes = 1L,
            width = 0,
            height = 0,
            title = null,
            warning = null,
            docId = id,
            summary = null
        )
    }

    @Test(timeout = 30_000)
    fun `page viewer secures the pages of a protected document`() {
        val id = lockedDoc()
        val c: ActivityController<PageViewerActivity> =
            Robolectric.buildActivity(PageViewerActivity::class.java, pageIntent(id))
        try {
            c.setup()
            assertTrue(
                "a page tap from a protected document must not be screenshot-able",
                SecureView.isSecure(c.get().window)
            )
        } finally {
            c.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `page viewer leaves a normal document screenshot-friendly when the setting is off`() {
        val id = normalDoc()
        val c: ActivityController<PageViewerActivity> =
            Robolectric.buildActivity(PageViewerActivity::class.java, pageIntent(id))
        try {
            c.setup()
            assertFalse(
                "an ordinary document stays screenshot-friendly with the switch off",
                SecureView.isSecure(c.get().window)
            )
        } finally {
            c.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `page viewer honours the screenshot setting for a normal document`() {
        Prefs.setBlockDocScreenshots(app, true)
        val id = normalDoc()
        val c: ActivityController<PageViewerActivity> =
            Robolectric.buildActivity(PageViewerActivity::class.java, pageIntent(id))
        try {
            c.setup()
            assertTrue(
                "the switch must follow the document into the page viewer",
                SecureView.isSecure(c.get().window)
            )
        } finally {
            c.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `page viewer stays secure across recreation`() {
        val id = lockedDoc()
        val c: ActivityController<PageViewerActivity> =
            Robolectric.buildActivity(PageViewerActivity::class.java, pageIntent(id))
        try {
            c.setup()
            assertTrue(SecureView.isSecure(c.get().window))
            c.recreate()
            assertTrue(
                "recreation must not drop the secure flag",
                SecureView.isSecure(c.get().window)
            )
        } finally {
            c.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `export result secures the protected-copy preview`() {
        val id = lockedDoc()
        val c: ActivityController<ExportResultActivity> =
            Robolectric.buildActivity(ExportResultActivity::class.java, exportIntent(id))
        try {
            c.setup()
            assertTrue(
                "a protected copy's preview must not be screenshot-able",
                SecureView.isSecure(c.get().window)
            )
        } finally {
            c.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `export result leaves a normal document screenshot-friendly when the setting is off`() {
        val id = normalDoc()
        val c: ActivityController<ExportResultActivity> =
            Robolectric.buildActivity(ExportResultActivity::class.java, exportIntent(id))
        try {
            c.setup()
            assertFalse(
                "an ordinary export stays screenshot-friendly with the switch off",
                SecureView.isSecure(c.get().window)
            )
        } finally {
            c.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `export result honours the screenshot setting for a normal document`() {
        Prefs.setBlockDocScreenshots(app, true)
        val id = normalDoc()
        val c: ActivityController<ExportResultActivity> =
            Robolectric.buildActivity(ExportResultActivity::class.java, exportIntent(id))
        try {
            c.setup()
            assertTrue(
                "the switch must follow the document into the export result",
                SecureView.isSecure(c.get().window)
            )
        } finally {
            c.destroy()
        }
    }
}
