package com.scanwhirl.app.ui.stamp

import android.app.Application
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.SignatureStore
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.util.PageStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** L5: a saved signature can be deleted from the signatures sheet (long-press, kit confirm). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SignatureDeleteTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `long press deletes a signature after a kit confirmation`() {
        val store = SignatureStore(context)
        store.save(
            "Signature 1",
            listOf(PageStamp.Stroke(listOf(PageStamp.Point(0f, 0f), PageStamp.Point(1f, 1f))))
        )
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

        StampPrompts.signature(host, store) { }

        val rows = requireNotNull(ShadowDialog.getLatestDialog())
            .findViewById<LinearLayout>(R.id.stampSignatureRows)!!
        assertTrue("the long-press is wired", rows.getChildAt(0).performLongClick())

        val confirm = requireNotNull(ShadowDialog.getLatestDialog())
        assertEquals(
            context.getString(R.string.stamp_signature_delete_title),
            confirm.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
        )
        confirm.findViewById<android.view.View>(R.id.swSheetPrimary)!!.performClick()

        assertTrue("the signature must be gone", store.list().isEmpty())
        assertNotNull("a sheet stays open (the empty pad)", ShadowDialog.getLatestDialog())
    }
}
