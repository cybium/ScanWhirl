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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * v2 UI: the Unlock sheet (mockup `09_unlock_sheet`) asks for the password, shows the tries-left
 * error, pauses Open after five wrong tries, and keeps Remove protection behind a second
 * confirmation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UnlockSheetTest {

    private var controller: ActivityController<ProtectHostActivity>? = null

    private fun host(): AppCompatActivity {
        val c = Robolectric.buildActivity(ProtectHostActivity::class.java).setup()
        controller = c
        return c.get()
    }

    /** Dialog.findViewById is nullable; a missing id here is a broken sheet, so fail loudly. */
    private fun <T : View> Dialog.view(id: Int): T {
        val found = findViewById<View>(id) ?: throw AssertionError("the sheet has no view $id")
        @Suppress("UNCHECKED_CAST")
        return found as T
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        ShadowDialog.reset()
    }

    private fun sheet(
        activity: AppCompatActivity,
        correct: String = "1234",
        cooldown: TryCooldown = TryCooldown(clock = { 1_000L }),
        unlocked: MutableList<String> = mutableListOf(),
        removed: MutableList<String> = mutableListOf()
    ) = UnlockSheet.show(
        activity = activity,
        documentName = "Rent agreement",
        verify = { it == correct },
        cooldown = cooldown,
        onUnlocked = { unlocked.add(it) },
        onRemoveProtection = { removed.add(it) }
    )

    @Test
    fun `the sheet names the document and hides the error until a wrong password`() {
        val activity = host()
        val unlocked = mutableListOf<String>()
        val dialog = sheet(activity, unlocked = unlocked)

        assertEquals(
            "Rent agreement is protected",
            dialog.view<TextView>(R.id.unlockTitle).text.toString()
        )
        assertEquals(View.GONE, dialog.view<View>(R.id.unlockError).visibility)
        assertEquals("Vault is not merged yet", View.GONE, dialog.view<View>(R.id.unlockVault).visibility)

        dialog.view<EditText>(R.id.unlockPassword).setText("0000")
        dialog.view<MaterialButton>(R.id.unlockOpen).performClick()

        val error = dialog.view<TextView>(R.id.unlockError)
        assertEquals(View.VISIBLE, error.visibility)
        assertTrue("the line says how many tries are left", error.text.contains("4 tries left"))
        assertTrue("nothing opened", unlocked.isEmpty())
        assertNotNull(ShadowDialog.getLatestDialog())
        dialog.dismiss()
    }

    @Test
    fun `the right password closes the sheet and hands the password over`() {
        val activity = host()
        val unlocked = mutableListOf<String>()
        val dialog = sheet(activity, unlocked = unlocked)
        dialog.view<EditText>(R.id.unlockPassword).setText("1234")
        dialog.view<MaterialButton>(R.id.unlockOpen).performClick()

        assertEquals(listOf("1234"), unlocked)
        assertFalse("the sheet is gone after a right password", dialog.isShowing)
    }

    @Test
    fun `five wrong passwords disable Open for thirty seconds`() {
        val activity = host()
        val unlocked = mutableListOf<String>()
        val cooldown = TryCooldown(clock = { 1_000L })
        val dialog = sheet(activity, cooldown = cooldown, unlocked = unlocked)
        val field = dialog.view<EditText>(R.id.unlockPassword)
        val open = dialog.view<MaterialButton>(R.id.unlockOpen)

        repeat(4) {
            field.setText("nope")
            open.performClick()
        }
        assertTrue("still open before the fifth try", open.isEnabled)
        field.setText("nope")
        open.performClick()

        assertFalse("Open is paused", open.isEnabled)
        val error = dialog.view<TextView>(R.id.unlockError)
        assertTrue("the line explains the pause", error.text.contains("paused for 30 seconds"))
        assertTrue(unlocked.isEmpty())
        dialog.dismiss()
    }

    @Test
    fun `remove protection asks for the password and then a second confirmation`() {
        val activity = host()
        val removed = mutableListOf<String>()
        val dialog = sheet(activity, removed = removed)
        dialog.view<EditText>(R.id.unlockPassword).setText("1234")
        dialog.view<MaterialButton>(R.id.unlockRemove).performClick()

        assertTrue("nothing removed on the first tap", removed.isEmpty())
        val confirm = ShadowDialog.getLatestDialog()
        assertNotNull("a second confirmation is shown", confirm)
        // The confirmation is a different dialog with its own message.
        val texts = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView && v.text != null) texts.add(v.text.toString())
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(confirm!!.window!!.decorView)
        assertTrue(
            "the second sheet explains the consequence",
            texts.any { it.contains("open without a password") }
        )
        assertTrue("the document is untouched until confirmed", removed.isEmpty())
    }
}
