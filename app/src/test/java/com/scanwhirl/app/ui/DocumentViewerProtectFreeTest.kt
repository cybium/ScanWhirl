package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * 05_protect_pdf — Protect PDF is a Free tool (`ToolRegistry` secure group). The document
 * viewer's More sheet must not sell it with a PRO pill or send a Free user to the old
 * "Pro is not available yet" panel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DocumentViewerProtectFreeTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false) // Free: the state the audit describes
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
    }

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
    fun `Free - More Protect PDF is not badged Pro and opens the free Protect sheet`() {
        val id = DocFixtures.saved(repo, app)
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
                assertFalse("really Free", ProGate.isPro(activity))
                activity.findViewById<View>(R.id.btnMore).performClick()
                val more = ShadowDialog.getLatestDialog()
                assertNotNull("the More sheet opens", more)
                assertFalse(
                    "Protect PDF is free, so a Free user must not see a PRO pill",
                    more!!.findViewById<View>(R.id.pillLock).isVisible
                )

                more.findViewById<View>(R.id.rowMoreLock).performClick()
                idle()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("Protect must open the free Protect sheet", sheet)
                assertNotNull(
                    "the shared Protect flow asks for a password",
                    sheet!!.findViewById<EditText>(R.id.protectPassword)
                )
                assertNull(
                    "the old 'not available yet' Pro panel must not be reachable",
                    sheet.findViewById<View>(R.id.proDossierStatus)
                )
                sheet.dismiss()
            }
        }
    }
}
