package com.scanwhirl.app.ui.protect

import android.app.Dialog
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * H3/H4 (found on device, 1.2.2): two different passwords made "Save protected copy"
 * do nothing at all, with no explanation, and the keyboard covered the action buttons.
 * A mismatch must say so under the confirm field, and the sheet must resize/pad for
 * the IME so the buttons stay reachable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProtectSheetFixTextTest {

    private var controller: ActivityController<ProtectHostActivity>? = null

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

    private fun sheet(
        activity: AppCompatActivity,
        result: MutableList<Triple<String, Boolean, Boolean>> = mutableListOf()
    ) = ProtectSheet.show(
        activity = activity,
        doc = ProtectSheet.Doc("Rent agreement", 6, 1_200_000L)
    ) { password, remember, share -> result.add(Triple(password, remember, share)) }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
    }

    @Test
    fun `different passwords show an inline error under the confirm field and submit nothing`() {
        val activity = host()
        val results = mutableListOf<Triple<String, Boolean, Boolean>>()
        val dialog = sheet(activity, results)
        val password = dialog.view<EditText>(R.id.protectPassword)
        val confirm = dialog.view<EditText>(R.id.protectConfirm)
        val share = dialog.view<MaterialButton>(R.id.protectShare)
        val save = dialog.view<MaterialButton>(R.id.protectSave)

        password.setText("hunter22")
        confirm.setText("hunter33")

        assertEquals(
            "the sheet says why it cannot continue",
            activity.getString(R.string.protect_passwords_mismatch),
            confirm.error?.toString()
        )
        assertFalse("nothing is submitted with a typo", share.isEnabled || save.isEnabled)

        // A tap on the (visibly disabled) action still explains itself and does nothing else.
        share.performClick()
        assertTrue("no password reaches the flow", results.isEmpty())
        assertEquals(
            activity.getString(R.string.protect_passwords_mismatch),
            confirm.error?.toString()
        )

        confirm.setText("hunter22")
        assertNull("the error goes away once the fields match", confirm.error)
        assertTrue("matching fields enable the action", share.isEnabled)
        share.performClick()
        assertEquals(listOf(Triple("hunter22", false, true)), results)
    }

    @Test
    fun `the sheet asks the window to resize for the keyboard and keeps padding the IME inset`() {
        val activity = host()
        val dialog = sheet(activity)
        val window = dialog.window ?: throw AssertionError("the sheet has no window")
        val adjust = window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        assertEquals(
            "the sheet window resizes for the IME (mode=${window.attributes.softInputMode})",
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            adjust
        )

        val root = dialog.view<View>(R.id.protectSheet)
        assertTrue("the form lives in a scroll container", root is NestedScrollView)
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
            "the IME inset still becomes bottom padding " +
                "(${root.paddingBottom} vs $basePadding + $ime)",
            root.paddingBottom >= basePadding + ime
        )
        val primary = dialog.view<View>(R.id.protectShare)
        val scroll = root as NestedScrollView
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        assertTrue(
            "the action button sits above the keyboard area",
            primary.bottom - scroll.scrollY <= scroll.height + 1
        )
    }

    @Test
    fun `the inline error is not a second blocking message`() {
        val activity = host()
        val dialog = sheet(activity)
        val confirm = dialog.view<EditText>(R.id.protectConfirm)
        dialog.view<EditText>(R.id.protectPassword).setText("hunter22")
        confirm.setText("hun")
        assertNotNull("a short, differing confirmation is explained", confirm.error)
        // No extra sheet is stacked on top of the form.
        assertEquals(activity.getString(R.string.protect_passwords_mismatch), confirm.error?.toString())
        assertFalse(dialog.view<TextView>(R.id.protectStrength).text.isNullOrBlank())
        dialog.dismiss()
    }
}
