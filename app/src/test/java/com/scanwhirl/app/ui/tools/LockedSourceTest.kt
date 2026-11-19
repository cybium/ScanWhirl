package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * v2 UI: 03_tools_hub — a locked document in a tool chooser is not a dead end: the row shows a
 * lock, tapping it opens L3's Unlock sheet, and the tool continues with that document selected.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LockedSourceTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun rowFor(activity: ToolSetupActivity, docId: String): LinearLayout {
        val source = activity.findViewById<LinearLayout>(R.id.toolSetupSource)
        for (i in 0 until source.childCount) {
            val row = source.getChildAt(i) as? LinearLayout ?: continue
            if (row.tag == docId) return row
        }
        throw AssertionError("no row for $docId")
    }

    @Test
    fun `a locked row opens the unlock sheet and continues after the right password`() {
        val id = DocFixtures.locked(repo, app, password = "1234", pages = 2)
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "split")).use { scenario ->
            scenario.onActivity { activity ->
                val row = rowFor(activity, id)
                // The row carries a lock icon and the document is still locked.
                assertTrue(
                    "locked rows need a lock icon",
                    (0 until row.childCount).any {
                        val child = row.getChildAt(it)
                        child is ImageView &&
                            child.contentDescription == activity.getString(R.string.tools_setup_locked)
                    }
                )
                row.performClick()
                idle()
                val sheet = requireNotNull(ShadowDialog.getLatestDialog()) { "no unlock sheet" }
                assertNotNull(sheet.findViewById<View>(R.id.unlockPassword))
                sheet.findViewById<EditText>(R.id.unlockPassword).setText("1234")
                sheet.findViewById<View>(R.id.unlockOpen).performClick()
                idle()
                // SAFE-UNLOCK-CONSENT: the password only proves the file opens. Taking the
                // protection off the library copy needs the explicit yes on the next sheet.
                val consent = requireNotNull(ShadowDialog.getLatestDialog()) { "no consent sheet" }
                assertEquals(
                    "the file stays locked until the user agrees",
                    true,
                    repo.getDocument(id)?.isLocked
                )
                consent.findViewById<View>(R.id.swSheetPrimary).performClick()
            }
            assertTrue(
                "the document is unlocked",
                waitUntil { repo.getDocument(id)?.isLocked == false }
            )
            scenario.onActivity { activity ->
                idle()
                val row = rowFor(activity, id)
                // The first child is the selection tick; it is visible for the chosen document.
                assertEquals(View.VISIBLE, row.getChildAt(0).visibility)
            }
        }
    }

    @Test
    fun `a wrong password keeps the document locked and shows the tries line`() {
        val id = DocFixtures.locked(repo, app, password = "1234", pages = 1)
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "split")).use { scenario ->
            scenario.onActivity { activity ->
                rowFor(activity, id).performClick()
                idle()
                val sheet = requireNotNull(ShadowDialog.getLatestDialog())
                sheet.findViewById<EditText>(R.id.unlockPassword).setText("9999")
                sheet.findViewById<View>(R.id.unlockOpen).performClick()
                idle()
                assertTrue(
                    "the wrong-password line is shown",
                    sheet.findViewById<View>(R.id.unlockError).visibility == View.VISIBLE
                )
            }
        }
        assertEquals("a wrong password must not unlock", true, repo.getDocument(id)?.isLocked)
    }
}
