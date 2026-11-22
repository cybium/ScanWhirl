package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LockEntryRegressionTest {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanWhirlApp.from(app).repository

    @After
    fun tearDown() = Faults.resetAll()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun MainActivity.prompt(doc: ScanDocument, opened: () -> Unit): Dialog {
        val method = MainActivity::class.java.getDeclaredMethod(
            "promptUnlockBeforeOpen", ScanDocument::class.java, Function0::class.java
        )
        method.isAccessible = true
        method.invoke(this, doc, opened)
        return requireNotNull(ShadowDialog.getLatestDialog())
    }

    private fun Dialog.getChoice(current: Int, legacy: Int): View =
        findViewById<View>(current) ?: requireNotNull(findViewById<View>(legacy))

    @Test
    fun `Open checks once off main and keeps protection`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val doc = requireNotNull(repo.getDocument(id))
        val main = AtomicInteger()
        val attempts = AtomicInteger()
        ScanRepository.lockDerivationProbe = {
            attempts.incrementAndGet()
            if (Looper.myLooper() == Looper.getMainLooper()) main.incrementAndGet()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var opened = 0
                val sheet = activity.prompt(doc) { opened++ }
                (sheet.getChoice(R.id.unlockPassword, R.id.swSheetInput) as EditText).setText("1234")
                sheet.getChoice(R.id.unlockOpen, R.id.swSheetPrimary).performClick()
                assertTrue("Open callback was not invoked", await { opened == 1 })
                assertEquals("no password derivation on the UI thread", 0, main.get())
                assertEquals(1, attempts.get())
                assertTrue(requireNotNull(repo.getDocument(id)).isLocked)
            }
        }
    }

    @Test
    fun `permanent unlock waits for confirmation and applies the checked grant once`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val doc = requireNotNull(repo.getDocument(id))
        val main = AtomicInteger()
        val attempts = AtomicInteger()
        ScanRepository.lockDerivationProbe = {
            attempts.incrementAndGet()
            if (Looper.myLooper() == Looper.getMainLooper()) main.incrementAndGet()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var opened = 0
                val sheet = activity.prompt(doc) { opened++ }
                (sheet.getChoice(R.id.unlockPassword, R.id.swSheetInput) as EditText).setText("1234")
                sheet.getChoice(R.id.unlockRemove, R.id.swSheetSecondary).performClick()
                assertEquals("no password derivation on the UI thread", 0, main.get())
                assertTrue("Remove protection confirmation opened", await {
                    ShadowDialog.getLatestDialog()?.let { it !== sheet && it.isShowing } == true
                })
                assertTrue("checking alone must keep the document locked", requireNotNull(repo.getDocument(id)).isLocked)
                assertEquals(0, opened)
                val confirmation = ShadowDialog.getLatestDialog()
                val targets = ArrayList<View>()
                confirmation.window!!.decorView.findViewsWithText(
                    targets, app.getString(R.string.unlock_remove_confirm), View.FIND_VIEWS_WITH_TEXT
                )
                val confirm = targets.filterIsInstance<TextView>().single { it.isClickable }
                confirm.performClick()
                assertTrue("removal opens the unlocked document", await { opened == 1 })
                assertFalse(requireNotNull(repo.getDocument(id)).isLocked)
                assertEquals("unlock must not derive a second time", 1, attempts.get())
                assertEquals(0, main.get())
            }
        }
    }

    @Test
    fun `dismissing an in-flight check never opens or changes the file`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val doc = requireNotNull(repo.getDocument(id))
        val bytes = doc.pdfFile.readBytes()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val main = AtomicInteger()
        val leftProbe = CountDownLatch(1)
        ScanRepository.lockDerivationProbe = {
            attempts.incrementAndGet()
            val onMain = Looper.myLooper() == Looper.getMainLooper()
            if (onMain) main.incrementAndGet()
            entered.countDown()
            if (!onMain) release.await(20, TimeUnit.SECONDS)
            leftProbe.countDown()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var opened = 0
                val sheet = activity.prompt(doc) { opened++ }
                (sheet.getChoice(R.id.unlockPassword, R.id.swSheetInput) as EditText).setText("1234")
                val open = sheet.getChoice(R.id.unlockOpen, R.id.swSheetPrimary)
                try {
                    open.performClick()
                    assertTrue(await { entered.count == 0L })
                    assertEquals("check must not run on main", 0, main.get())
                    assertFalse("Open is disabled during the check", open.isEnabled)
                    val progress = ArrayList<View>()
                    sheet.window!!.decorView.findViewsWithText(
                        progress, app.getString(R.string.unlock_checking), View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION
                    )
                    assertTrue("the running check has labelled progress", progress.any { it is ProgressBar && it.isShown })
                    open.performClick()
                    sheet.dismiss()
                    idle()
                } finally {
                    release.countDown()
                }
                assertTrue(leftProbe.await(5, TimeUnit.SECONDS))
                idle()
                assertEquals(0, opened)
                assertEquals(1, attempts.get())
                assertTrue(requireNotNull(repo.getDocument(id)).isLocked)
                assertTrue(bytes.contentEquals(doc.pdfFile.readBytes()))
            }
        }
    }
}
