package com.scanwhirl.app.ui.protect

import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * S4B (independent re-review, MAJOR 1): the Unlock sheet's check is suspending and runs on a
 * background dispatcher with a progress state. One tap derives once, a double tap is ignored, and
 * a sheet dismissed mid-derivation applies nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UnlockSheetOffMainTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private var controller: ActivityController<ProtectHostActivity>? = null

    private fun host(): AppCompatActivity {
        val c = Robolectric.buildActivity(ProtectHostActivity::class.java).setup()
        controller = c
        return c.get()
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        Faults.resetAll()
        ShadowDialog.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(10)
        }
        idle()
        return condition()
    }

    private fun <T : View> Dialog.view(id: Int): T {
        val found = findViewById<View>(id) ?: throw AssertionError("the sheet has no view $id")
        @Suppress("UNCHECKED_CAST")
        return found as T
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun progressOf(dialog: Dialog): ProgressBar? =
        allViews(dialog.window!!.decorView).filterIsInstance<ProgressBar>().firstOrNull()

    /**
     * End to end with the real repository: the 1 300 000-iteration PBKDF2 of the lock check must
     * run on a background thread, and the right password unlocks through the lock-record-bound grant.
     */
    @Test
    fun `the sheet checks the password off the main thread and unlocks once`() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        val id = DocFixtures.locked(repo, app, password = "1234")
        var onMain = 0
        var offMain = 0
        ScanRepository.lockDerivationProbe = {
            if (Looper.myLooper() == Looper.getMainLooper()) onMain++ else offMain++
        }
        val unlocked = mutableListOf<String>()
        val dialog = UnlockSheet.show(
            activity = host(),
            documentName = "Rent agreement",
            verify = { candidate -> repo.checkPdfPassword(id, candidate) != null },
            onUnlocked = { unlocked.add(it) }
        )

        dialog.view<EditText>(R.id.unlockPassword).setText("1234")
        dialog.view<MaterialButton>(R.id.unlockOpen).performClick()
        assertTrue("the sheet opens the document", await { unlocked.isNotEmpty() })

        assertEquals("no PBKDF2 on the main thread", 0, onMain)
        assertTrue("the check derived off the main thread", offMain >= 1)
        assertEquals(listOf("1234"), unlocked)
        assertFalse("the sheet is gone after a right password", dialog.isShowing)
    }

    @Test
    fun `a double tap runs one check and shows progress while it runs`() {
        val checks = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = CountDownLatch(1)
        val unlocked = mutableListOf<String>()
        val dialog = UnlockSheet.show(
            activity = host(),
            documentName = "Rent agreement",
            verify = {
                checks.incrementAndGet()
                gate.await(10, TimeUnit.SECONDS)
                true
            },
            onUnlocked = { unlocked.add(it) }
        )
        dialog.view<EditText>(R.id.unlockPassword).setText("1234")
        val open = dialog.view<MaterialButton>(R.id.unlockOpen)

        open.performClick()
        open.performClick()
        open.performClick()

        assertTrue("the first check started", await(5_000) { checks.get() == 1 })
        val progress = progressOf(dialog)
        assertNotNull("the sheet shows a progress state while checking", progress)
        assertEquals(View.VISIBLE, progress!!.visibility)
        assertFalse("Open is disabled while checking", open.isEnabled)

        gate.countDown()
        assertTrue("the check finished", await { unlocked.isNotEmpty() })
        assertEquals("three taps must submit once", 1, checks.get())
        assertEquals(listOf("1234"), unlocked)
    }

    @Test
    fun `dismissing the sheet during the check applies nothing`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val unlocked = mutableListOf<String>()
        val dialog = UnlockSheet.show(
            activity = host(),
            documentName = "Rent agreement",
            verify = {
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                true
            },
            onUnlocked = { unlocked.add(it) }
        )
        dialog.view<EditText>(R.id.unlockPassword).setText("1234")
        dialog.view<MaterialButton>(R.id.unlockOpen).performClick()
        assertTrue("the derivation started", started.await(10, TimeUnit.SECONDS))

        dialog.dismiss()
        release.countDown()
        repeat(60) {
            idle()
            Thread.sleep(5)
        }

        assertTrue("a dismissed sheet never opens the document", unlocked.isEmpty())
        assertFalse(dialog.isShowing)
    }
}
