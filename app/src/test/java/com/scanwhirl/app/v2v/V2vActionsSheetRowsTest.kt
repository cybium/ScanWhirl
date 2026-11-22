package com.scanwhirl.app.v2v

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
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

/** Phone run 2: Protect / Unlock and Move to Trash must be real rows in the
 *  document Actions sheet (not only reachable through Tools). Their state must
 *  follow the document: Protect for a normal copy, Unlock for a protected one,
 *  and a confirmed, reversible Trash row. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class V2vActionsSheetRowsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)

    private fun openSheet(docId: String): Pair<ActivityController<DocumentViewerActivity>, android.app.Dialog> {
        val controller = Robolectric.buildActivity(DocumentViewerActivity::class.java,
            DocumentViewerActivity.intent(app, docId))
        val activity = controller.setup().get()
        val deadline = System.currentTimeMillis() + 8000L
        while (activity.findViewById<TextView>(R.id.pageCount)?.text?.isNullOrEmpty() != false &&
            System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        activity.findViewById<View>(R.id.btnMore).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return controller to requireNotNull(ShadowDialog.getLatestDialog()) {
            "the Actions sheet must open"
        }
    }

    private fun row(sheet: android.app.Dialog, key: String): View {
        val found = sheet.window?.decorView?.findViewWithTag<View>("docActions:$key")
        assertNotNull("the Actions sheet has no '$key' row", found)
        return found!!
    }

    private fun labelOf(view: View, labelId: Int): String =
        view.findViewById<TextView>(labelId)?.text?.toString() ?: ""

    @Test(timeout = 30_000)
    fun `normal document offers Protect PDF in its Actions sheet`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Plain")
        val (controller, sheet) = openSheet(id)
        try {
            val lock = row(sheet, "lock")
            assertEquals(app.getString(R.string.menu_protect_pdf), labelOf(lock, R.id.labelLock))
            assertTrue("Protect row must be actionable", lock.isEnabled && lock.isClickable)
            assertEquals("Protect row must be a >=56dp target", true,
                lock.minimumHeight >= (56 * app.resources.displayMetrics.density).toInt() - 2)
        } finally {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `protected document offers Unlock PDF in its Actions sheet`() {
        val id = DocFixtures.locked(repo, app, pages = 1)
        val (controller, sheet) = openSheet(id)
        try {
            val lock = row(sheet, "lock")
            assertEquals(app.getString(R.string.btn_unlock_pdf), labelOf(lock, R.id.labelLock))
            assertTrue("Unlock row must be actionable", lock.isEnabled && lock.isClickable)
        } finally {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `Move to Trash row asks first and never deletes without confirmation`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Trash row")
        val (controller, sheet) = openSheet(id)
        try {
            val trash = row(sheet, "trash")
            assertEquals(app.getString(R.string.trash_move), labelOf(trash, R.id.labelMoreDelete))
            assertTrue("Trash row must be actionable", trash.isEnabled && trash.isClickable)
            trash.performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val confirm = ShadowDialog.getLatestDialog()
            assertNotNull("a confirm sheet must appear", confirm)
            assertTrue("the document must still exist before confirmation", repo.getDocument(id) != null)
            val labels = mutableListOf<String>()
            fun walk(v: View) {
                if (v is TextView) v.text?.toString()?.let { labels += it }
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            confirm.window?.decorView?.let { walk(it) }
            assertTrue("the confirm must name the document: $labels",
                labels.any { it.contains("Trash row") })
        } finally {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }
}
