package com.scanwhirl.app.ui.protect

import android.app.Dialog
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

/**
 * K1 defects 3 and 4: the tries-left line must name the *real* next pause (which doubles), and
 * after a pause ends every action, including "Use password saved in Vault", must come back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UnlockSheetPauseTest {

    private var controller: ActivityController<ProtectHostActivity>? = null
    private val looper get() = android.os.Looper.getMainLooper()

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

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(looper).idle()
            if (condition()) return true
            Thread.sleep(10)
        }
        shadowOf(looper).idle()
        return condition()
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        ShadowDialog.reset()
    }

    @Test
    fun `the tries line names the real next pause and the vault button returns after it`() {
        // A one-second first pause keeps this test fast; the production default is 30 s.
        var now = 1_000L
        val cooldown = TryCooldown(maxTries = 2, firstPauseMs = 1_000L, clock = { now })
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

        fun wrong() {
            field.setText("bad")
            open.performClick()
        }

        // First cycle: one wrong try, then the 1 s pause starts.
        wrong()
        assertTrue(await { error.visibility == View.VISIBLE && error.text.contains("1 tries left") })
        assertTrue(
            "the tries line names the real first pause, not a fixed number",
            error.text.contains("1-second pause")
        )
        wrong()
        assertTrue("the pause disables Open", await { !open.isEnabled })
        assertFalse("the pause also disables Vault", vault.isEnabled)

        // End the pause: both the injected clock and the Handler's scheduler move forward.
        now += 1_001L
        shadowOf(looper).idleFor(java.time.Duration.ofMillis(1_001L))
        assertTrue("Open returns after the pause", await { open.isEnabled })
        assertTrue("Remove returns after the pause", remove.isEnabled)
        assertTrue("Vault returns after the pause too", vault.isEnabled)

        // Second cycle: the next pause doubled to 2 s, and the tries line must say so.
        wrong()
        assertTrue(
            "the tries line names the doubled pause",
            await { error.text.contains("2-second pause") }
        )
        dialog.dismiss()
    }
}
