package com.scanwhirl.app.ui.tools.resultauthority

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.tools.ToolResultActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController

/** Task04: the actual ID-card Discard handler, not just a repository helper. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResultDiscardAuthorityTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private fun discard(screen: ToolResultActivity) {
        val method = ToolResultActivity::class.java.getDeclaredMethod("discardIdCard")
        method.isAccessible = true
        method.invoke(screen)
    }

    private fun waitUntil(done: () -> Boolean): Boolean {
        val stop = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < stop) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun isChecking(screen: ToolResultActivity): Boolean {
        val field = ToolResultActivity::class.java.getDeclaredField("undoChecking")
        field.isAccessible = true
        return field.getBoolean(screen)
    }

    @Test(timeout = 30_000)
    fun changedOutputDoesNotDisappearOnDiscardOrSecondTap() {
        val source = DocFixtures.saved(repo, app, pages = 1, name = "Source")
        val output = DocFixtures.saved(repo, app, pages = 1, name = "ID card")
        val identity = requireNotNull(
            ToolResultActivity.captureOutputIdentityBlocking(app, listOf(output), emptyList())
        )
        val file = requireNotNull(repo.getDocument(output)).pdfFile
        file.appendBytes(byteArrayOf(0x31))
        val changed = file.readBytes()
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(
                ToolResultActivity::class.java,
                ToolResultActivity.intent(app, "id_card", docIds = listOf(output),
                    outputIdentity = identity, receiptSourceId = source)
            ).setup()
            val screen = controller.get()
            discard(screen)
            assertTrue("the request actually completed", waitUntil { !isChecking(screen) })
            discard(screen)
            assertTrue("second refusal also completed", waitUntil { !isChecking(screen) })
            assertArrayEquals(changed, file.readBytes())
            assertNotNull("the source must survive", repo.getDocument(source))
            assertNotNull("the altered ID card must survive", repo.getDocument(output))
        } finally {
            controller?.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun unchangedCardDiscardsAndCannotBeDeletedAgain() {
        val source = DocFixtures.saved(repo, app, pages = 1, name = "Source")
        val output = DocFixtures.saved(repo, app, pages = 1, name = "ID card")
        val identity = requireNotNull(
            ToolResultActivity.captureOutputIdentityBlocking(app, listOf(output), emptyList())
        )
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(
                ToolResultActivity::class.java,
                ToolResultActivity.intent(app, "id_card", docIds = listOf(output),
                    outputIdentity = identity, receiptSourceId = source)
            ).setup()
            val screen = controller.get()
            discard(screen)
            assertTrue("the ID output should be removed", waitUntil { repo.getDocument(output) == null })
            discard(screen)
            assertNotNull(repo.getDocument(source))
            assertNull(repo.getDocument(output))
        } finally {
            controller?.destroy()
        }
    }
}
