package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * v2 UI: 05_protect_pdf — the reader's More → Protect PDF opens the same Protect sheet as
 * Tools → Protect (password typed twice), not the old one-field RC4 dialog.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ViewerProtectSheetTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setTestOverride(app, true)
    }

    @After fun tearDown() = Faults.resetAll()

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        idle()
        return condition()
    }

    @Test
    fun `viewer More Protect opens the Protect sheet with the password twice`() {
        val id = DocFixtures.saved(repo, app)
        assertFalse("fixture starts unlocked", repo.getDocument(id)!!.isLocked)

        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            val loaded = pollUntil {
                var ready = false
                scenario.onActivity { a ->
                    ready = a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                }
                ready
            }
            assertTrue("the document must load before More works", loaded)

            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnMore).performClick()
                val more = ShadowDialog.getLatestDialog()
                assertNotNull("the More sheet opens", more)
                more!!.findViewById<View>(R.id.rowMoreLock).performClick()
                idle()

                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("Protect opens a sheet", sheet)
                assertNotNull(
                    "the v2 Protect sheet has the first password field",
                    sheet!!.findViewById<EditText>(R.id.protectPassword)
                )
                assertNotNull(
                    "the v2 Protect sheet asks for the password twice (05_protect_pdf)",
                    sheet.findViewById<EditText>(R.id.protectConfirm)
                )
                assertEquals(
                    "Protect PDF",
                    sheet.findViewById<TextView>(R.id.protectTitle)?.text?.toString()
                )
                assertFalse(
                    "nothing is protected until the sheet submits",
                    repo.getDocument(id)!!.isLocked
                )
                sheet.dismiss()
            }
        }
    }
}
