package com.scanwhirl.app.ui.protect

import android.app.Dialog
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.scanwhirl.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.time.Duration

/**
 * K1 defects 3 and 4: the tries-left line must name the *real* next pause (which doubles), and
 * after a pause ends every action, including "Use password saved in Vault", must come back.
 *
 * TEST2: the sheet's check runs on Dispatchers.Default and its continuation is posted to the main
 * looper afterwards. The old "sentinel posted from inside verify" only proved that the worker had
 * started: the sheet's failure continuation could still be queued *after* the sentinel, so the
 * following assertion raced it on a loaded runner. There is no way for a test to observe when a
 * foreign coroutine's continuation is enqueued, so we wait for the actual, observable UI state
 * instead. The wait pumps the looper and has a generous bounded deadline; no assertion is relaxed,
 * no sleep is used as a synchroniser, and a timeout still fails the test with the expected state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UnlockSheetPauseTest {

    private var controller: ActivityController<ProtectHostActivity>? = null
    private val looper get() = Looper.getMainLooper()

    private fun host(): AppCompatActivity {
        val c = Robolectric.buildActivity(ProtectHostActivity::class.java).setup()
        controller = c
        return c.get()
    }

    private fun <T : View> Dialog.view(id: Int): T {
        val found = findViewById<View>(id) ?: throw AssertionError("the sheet has no view $id")
        @Suppress("UNCHECKED_CAST")
        return found as T
    }

    /** Runs every main-looper message queued right now; never advances time or sleeps. */
    private fun pump() = shadowOf(looper).idle()

    /**
     * Bounded, condition-driven wait for a state the production continuation must publish.
     * Tickling the looper is the only synchronisation; the deadline is a guard against a hang,
     * not a timing assumption.
     */
    private fun awaitUi(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            pump()
            if (condition()) return
            Thread.sleep(5)
        }
        pump()
        assertTrue(message, condition())
    }

    /** Advances the Handler scheduler by the same amount the test moved the injected clock. */
    private fun advanceScheduler(ms: Long) {
        shadowOf(looper).idleFor(Duration.ofMillis(ms))
        pump()
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        ShadowDialog.reset()
    }

    @Test(timeout = 30_000)
    fun `the tries line names the real next pause and the vault button returns after it`() {
        var nowRef = 1_000L
        val cooldown = TryCooldown(maxTries = 2, firstPauseMs = 1_000L, clock = { nowRef })
        val activity = host()
        val dialog = UnlockSheet.show(
            activity = activity,
            documentName = "Rent agreement",
            verify = { false },
            cooldown = cooldown,
            vaultAvailable = true,
            onUnlocked = { },
            onRemoveProtection = { },
            onUseVault = { }
        )
        val field = dialog.view<EditText>(R.id.unlockPassword)
        val open = dialog.view<MaterialButton>(R.id.unlockOpen)
        val remove = dialog.view<MaterialButton>(R.id.unlockRemove)
        val vault = dialog.view<MaterialButton>(R.id.unlockVault)
        val error = dialog.view<TextView>(R.id.unlockError)
        assertEquals(View.VISIBLE, vault.visibility)

        fun wrong(message: String, applied: () -> Boolean) {
            field.setText("bad")
            open.performClick()
            awaitUi(message, applied)
        }

        // First cycle: one wrong try, then the 1 s pause starts.
        wrong("the tries line is shown") {
            error.visibility == View.VISIBLE && error.text.contains("1 tries left")
        }
        assertTrue(
            "the tries line names the real first pause, not a fixed number",
            error.text.contains("1-second pause")
        )
        // setChecking(true) also disables Open while the KDF runs, so require the failure
        // continuation's paused error line as well: this proves the pause, not just "still checking".
        wrong("the pause disables Open") {
            error.visibility == View.VISIBLE && !open.isEnabled &&
                error.text.toString() == activity.getString(R.string.unlock_wrong_paused, 1)
        }
        assertFalse("the pause also disables Vault", vault.isEnabled)

        // End the pause: both the injected clock and the Handler's scheduler move forward together.
        nowRef += 1_001L
        advanceScheduler(1_001L)
        assertTrue("Open returns after the pause", open.isEnabled)
        assertTrue("Remove returns after the pause", remove.isEnabled)
        assertTrue("Vault returns after the pause too", vault.isEnabled)

        // Second cycle: the next pause doubled to 2 s, and the tries line must say so.
        wrong("the tries line names the doubled pause") { error.text.contains("2-second pause") }
        dialog.dismiss()
    }
}
