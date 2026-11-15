package com.scanwhirl.app.ui.protect

import android.app.Dialog
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
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

/**
 * v2 UI: the Protect PDF sheet must match mockup `05_protect_pdf` in behaviour — password typed
 * twice, four-segment meter, Protect disabled until it is safe to use, and the two exits.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProtectSheetTest {

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
    }

    private fun sheet(
        activity: AppCompatActivity,
        rememberAvailable: Boolean = false,
        result: MutableList<Triple<String, Boolean, Boolean>> = mutableListOf()
    ) = ProtectSheet.show(
        activity = activity,
        doc = ProtectSheet.Doc("Rent agreement", 6, 1_200_000L),
        rememberAvailable = rememberAvailable
    ) { password, remember, share -> result.add(Triple(password, remember, share)) }

    @Test
    fun `the sheet consumes the IME inset and the primary button stays reachable`() {
        val activity = host()
        val dialog = sheet(activity, rememberAvailable = true)
        val root = dialog.view<View>(R.id.protectSheet)
        assertTrue("the form lives in a scroll container", root is NestedScrollView)
        val primary = root.findViewById<View>(R.id.protectShare)
        assertNotNull("the primary button is inside that container", primary)

        val screen = activity.resources.displayMetrics.heightPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screen, View.MeasureSpec.AT_MOST)
        )
        root.layout(0, 0, 1080, root.measuredHeight)
        val basePadding = root.paddingBottom
        val ime = screen / 3
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
            .build()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(
            "the IME inset becomes bottom padding (${root.paddingBottom} vs $basePadding + $ime)",
            root.paddingBottom >= basePadding + ime
        )
        assertTrue(
            "the sheet is expanded, never collapsed",
            dialog.behavior.skipCollapsed &&
                dialog.behavior.state == com.google.android.material.bottomsheet
                    .BottomSheetBehavior.STATE_EXPANDED
        )
        // The content is taller than the viewport, so the owner can scroll the primary button
        // above the keyboard; at the bottom the button is inside the visible box.
        val scroll = root as NestedScrollView
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        assertTrue(
            "the primary button is reachable at the bottom",
            primary!!.bottom - scroll.scrollY <= scroll.height + 1
        )
    }

    @Test
    fun `the sheet draws the mockup fields and disables protect until the password is safe`() {
        val activity = host()
        val dialog = sheet(activity)
        val share = dialog.view<MaterialButton>(R.id.protectShare)
        val save = dialog.view<MaterialButton>(R.id.protectSave)
        val strength = dialog.view<TextView>(R.id.protectStrength)
        val confirmCheck = dialog.view<ImageView>(R.id.protectConfirmCheck)
        val rememberRow = dialog.view<View>(R.id.protectRememberRow)

        assertEquals("Rent agreement", dialog.view<TextView>(R.id.protectDocName).text.toString())
        assertEquals("6", dialog.view<TextView>(R.id.protectDocPages).text.toString())
        assertFalse("Protect is disabled with an empty password", share.isEnabled)
        assertFalse(save.isEnabled)
        assertEquals(View.GONE, strength.visibility)
        assertEquals(View.GONE, confirmCheck.visibility)
        assertEquals("no Vault API yet, so no row", View.GONE, rememberRow.visibility)
        dialog.dismiss()
    }

    @Test
    fun `a twelve character password fills three segments and the confirm check unlocks Protect`() {
        val activity = host()
        val dialog = sheet(activity)
        val password = dialog.view<EditText>(R.id.protectPassword)
        val confirm = dialog.view<EditText>(R.id.protectConfirm)
        val share = dialog.view<MaterialButton>(R.id.protectShare)
        val strength = dialog.view<TextView>(R.id.protectStrength)
        val check = dialog.view<ImageView>(R.id.protectConfirmCheck)

        password.setText("correcthorse") // 12 characters
        assertEquals(View.VISIBLE, strength.visibility)
        assertEquals("Strong · 12 characters", strength.text.toString())
        assertEquals(
            "three of four segments are green",
            ContextCompat.getColor(activity, R.color.protect_ok),
            dialog.view<View>(R.id.protectMeter1).backgroundTintList!!.defaultColor
        )
        assertEquals(
            "the last segment stays off",
            ContextCompat.getColor(activity, R.color.protect_meter_off),
            dialog.view<View>(R.id.protectMeter4).backgroundTintList!!.defaultColor
        )
        assertFalse("mismatched confirmation keeps Protect disabled", share.isEnabled)
        assertEquals(View.GONE, check.visibility)

        confirm.setText("correcthorse")
        assertEquals(View.VISIBLE, check.visibility)
        assertTrue("matching fields enable Protect", share.isEnabled)

        // One character short starts a warning and disables the button again.
        password.setText("short")
        assertEquals("Too short · at least 6 characters", strength.text.toString())
        assertFalse(share.isEnabled)
        dialog.dismiss()
    }

    @Test
    fun `protect and share and save report different intents with the typed password`() {
        val activity = host()
        val results = mutableListOf<Triple<String, Boolean, Boolean>>()
        val dialog = sheet(activity, result = results)
        dialog.view<EditText>(R.id.protectPassword).setText("hunter22")
        dialog.view<EditText>(R.id.protectConfirm).setText("hunter22")
        dialog.view<MaterialButton>(R.id.protectShare).performClick()
        assertEquals(listOf(Triple("hunter22", false, true)), results)

        val second = sheet(activity, result = results)
        second.view<EditText>(R.id.protectPassword).setText("hunter22")
        second.view<EditText>(R.id.protectConfirm).setText("hunter22")
        second.view<MaterialButton>(R.id.protectSave).performClick()
        assertEquals(Triple("hunter22", false, false), results.last())
    }

    @Test
    fun `the remember row appears only when a Vault store is wired in`() {
        val activity = host()
        val dialog = sheet(activity, rememberAvailable = true)
        assertEquals(View.VISIBLE, dialog.view<View>(R.id.protectRememberRow).visibility)
        dialog.dismiss()

        val results = mutableListOf<Triple<String, Boolean, Boolean>>()
        val wired = sheet(activity, rememberAvailable = true, result = results)
        wired.view<EditText>(R.id.protectPassword).setText("hunter22")
        wired.view<EditText>(R.id.protectConfirm).setText("hunter22")
        wired.view<MaterialButton>(R.id.protectShare).performClick()
        assertEquals("remember defaults on", true, results.single().second)
    }
}
