package com.scanwhirl.app.ui.protect

import android.app.Dialog
import android.os.Handler
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * K1 defects 3 and 4: the tries-left line must name the *real* next pause (which doubles), and
 * after a pause ends every action, including "Use password saved in Vault", must come back.
 *
 * S2 item 6: deterministic. The only asynchrony in the sheet is its check on Dispatchers.Default;
 * the test publishes a sentinel on the main looper from inside `verify` and pumps the looper until
 * that sentinel has run, so the main-thread continuation (failure + pause scheduling) has been
 * applied before the controlled clock and the looper scheduler are advanced together. No real
 * sleeps and no wall-clock deadline.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UnlockSheetPauseTest {

    private var controller: ActivityController<ProtectHostActivity>? = null
    private val looper get() = Looper.getMainLooper()
    private val applyOnMain = AtomicBoolean(false)

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

    @Test
    fun `the tries line names the real next pause and the vault button returns after it`() {
        var nowRef = 1_000L
        val cooldown = TryCooldown(maxTries = 2, firstPauseMs = 1_000L, clock = { nowRef })
        val activity = host()
        val dialog = UnlockSheet.show(
            activity = activity,
            documentName = "Rent agreement",
            verify = {
                // The check runs on Dispatchers.Default. Post a sentinel to the main looper so the
                // test can pump until the sheet's main-thread continuation is queued behind it.
                Handler(Looper.getMainLooper()).post { applyOnMain.set(true) }
                false
            },
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

        fun wrong() {
            applyOnMain.set(false)
            field.setText("bad")
            open.performClick()
            // A bounded pump, not a sleep: when the sentinel has run, the continuation queued
            // behind it (registerFailure + tickPause) has also run in this idle() pass.
            repeat(100) {
                pump()
                if (applyOnMain.get()) return@repeat
                Thread.yield()
            }
            assertTrue("the background check must reach the main thread", applyOnMain.get())
            pump()
        }

        // First cycle: one wrong try, then the 1 s pause starts.
        wrong()
        assertTrue("the tries line is shown", error.visibility == View.VISIBLE && error.text.contains("1 tries left"))
        assertTrue(
            "the tries line names the real first pause, not a fixed number",
            error.text.contains("1-second pause")
        )
        wrong()
        assertTrue("the pause disables Open", !open.isEnabled)
        assertFalse("the pause also disables Vault", vault.isEnabled)

        // End the pause: both the injected clock and the Handler's scheduler move forward together.
        nowRef += 1_001L
        advanceScheduler(1_001L)
        assertTrue("Open returns after the pause", open.isEnabled)
        assertTrue("Remove returns after the pause", remove.isEnabled)
        assertTrue("Vault returns after the pause too", vault.isEnabled)

        // Second cycle: the next pause doubled to 2 s, and the tries line must say so.
        wrong()
        assertTrue(
            "the tries line names the doubled pause",
            error.text.contains("2-second pause")
        )
        dialog.dismiss()
    }
}
