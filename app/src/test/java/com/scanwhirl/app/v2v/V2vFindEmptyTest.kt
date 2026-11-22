package com.scanwhirl.app.v2v

import android.app.Application
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.stamp.StampEditorActivity
import org.junit.Assert.assertEquals
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

/** Phone run: Redact -> Find private info was silent when nothing matched.
 *  The empty outcome must be named and persistent in the sheet, in EN and HI. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class V2vFindEmptyTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)

    @Test(timeout = 30_000)
    fun `an empty private-info find shows an honest named sheet`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Clean")
        val controller = Robolectric.buildActivity(StampEditorActivity::class.java,
            Intent(app, StampEditorActivity::class.java)
                .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val screen = controller.setup().get()
            screen.findScanner = { StampEditorActivity.FindOutcome(emptyList(), emptyList()) }
            screen.findViewById<View>(R.id.toolRedact).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            screen.findViewById<View>(R.id.findRedactions).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val find = requireNotNull(ShadowDialog.getLatestDialog()) { "Find sheet" }
            find.findViewById<View>(R.id.swSheetPrimary).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()

            val empty = ShadowDialog.getLatestDialog()
            assertNotNull("a named empty-outcome sheet must be shown", empty)
            assertEquals("the honest title",
                app.getString(R.string.cdf1_find_none_title),
                empty!!.findViewById<TextView>(R.id.swSheetTitle).text.toString())
            assertEquals("the caveat stays in the sheet",
                app.getString(R.string.cdf1_find_none_body),
                empty.findViewById<TextView>(R.id.swSheetMessage).text.toString())
        } finally {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `Hindi empty-outcome wording is lane-native`() {
        val hi = java.io.File("src/main/res/values-hi/strings_copydiet_fx1.xml").readText(Charsets.UTF_8)
        assertTrue("HI must name the empty outcome",
            hi.contains("<string name=\"cdf1_find_none_title\">कोई मिलान नहीं मिला</string>"))
    }
}
