package com.scanwhirl.app.ui.stamp

import android.app.Application
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.R
import com.scanwhirl.app.store.SignatureStore
import com.scanwhirl.app.ui.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Owner F14: while drawing a signature the sheet must not be dragged or closed by a downward
 * stroke. It closes only through its own X or its Done (Save) button.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SignatureSheetFixUiTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun openPad(): BottomSheetDialog {
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        StampPrompts.pad(host, SignatureStore(context), onSaved = { })
        return requireNotNull(ShadowDialog.getLatestDialog()) as BottomSheetDialog
    }

    @Test
    fun `the signature pad sheet cannot be dragged away`() {
        val dialog = openPad()

        assertFalse("a downward stroke must not drag the sheet", dialog.behavior.isDraggable)
        assertFalse("a downward stroke must not hide the sheet", dialog.behavior.isHideable)
        dialog.onBackPressed()
        assertTrue("back must not close the signature pad", dialog.isShowing)
        assertEquals(
            "no grab bar on a sheet that cannot be dragged",
            View.GONE,
            requireNotNull(dialog.findViewById<View>(R.id.swSheetGrab)).visibility
        )
    }

    @Test
    fun `the X still closes the pad sheet`() {
        val dialog = openPad()
        requireNotNull(dialog.findViewById<View>(R.id.swSheetClose)).performClick()
        assertFalse("the X must always close the sheet", dialog.isShowing)
    }

    @Test
    fun `Done saves the drawn signature and closes the sheet`() {
        val dialog = openPad()
        val pad = requireNotNull(dialog.findViewById<SignaturePadView>(R.id.stampSignaturePad))
        pad.layout(0, 0, 300, 240)
        val down = System.currentTimeMillis()
        pad.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 20f, 20f, 0))
        pad.dispatchTouchEvent(MotionEvent.obtain(down, down + 20, MotionEvent.ACTION_MOVE, 180f, 160f, 0))
        pad.dispatchTouchEvent(MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, 180f, 160f, 0))
        assertFalse("the stroke is on the pad", pad.isEmpty())

        requireNotNull(dialog.findViewById<View>(R.id.swSheetPrimary)).performClick()

        assertFalse("Done closes the sheet", dialog.isShowing)
        assertNotNull(SignatureStore(context).list().firstOrNull())
        assertTrue("the drawn signature was saved", SignatureStore(context).list().isNotEmpty())
    }
}
