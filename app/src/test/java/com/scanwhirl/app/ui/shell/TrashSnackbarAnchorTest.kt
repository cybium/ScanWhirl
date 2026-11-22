package com.scanwhirl.app.ui.shell
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.TrashActions
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
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
import java.time.Duration

/**
 * T40-FIX-SNACK-ANCHOR (phone build 4): after a multi-select "Move N documents to
 * Trash", the Undo bar was drawn OVER the shell's bottom nav (phone: bar y
 * 2172-2316, nav 2112-2304). Undo is the only in-app safety net for a reversible
 * data action, so a bar that overlaps the nav can lose the tap to a nav cell
 * (the owner's tap opened Me).
 *
 * [com.scanwhirl.app.ui.TrashActions.showMoved] only anchors when it is given a
 * view, and the library's multi-select passed null while the single-row path
 * passed the shell's nav host. These tests measure the real laid-out bounds of
 * the bar and the nav after both moves:
 *
 *  - the bar's rectangle must sit entirely above the nav host (no overlap),
 *  - the Undo action must lie inside the bar and be the view a tap at its centre
 *    reaches (not a nav cell), and
 *  - Undo must really restore every document - both paths.
 *
 * The second test is the regression guard for the single-row path that already
 * passed the anchor.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class TrashSnackbarAnchorTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        TrashActions.takePending() // no notice left over from another test
    }

    @Test
    fun `multi-select Move to Trash keeps Undo above the nav and Undo restores both`() {
        val one = DocFixtures.saved(repo, app, pages = 3, name = "Anchor one")
        val two = DocFixtures.saved(repo, app, pages = 2, name = "Anchor two")

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertTrue("both documents are listed", poll { cards(activity).size == 2 })
                layoutDecor(activity)

                cards(activity).first { it.doc?.id == one }.performLongClick()
                idle()
                cards(activity).first { it.doc?.id == two }.performClick()
                idle()
                activity.findViewById<View>(R.id.swSelDelete).performClick()
                idle()

                val confirm = latestDialog("the multi-select trash confirmation")
                confirm.findViewById<View>(R.id.swSheetPrimary).performClick()
                assertTrue(
                    "both leave the library",
                    poll { repo.getDocument(one) == null && repo.getDocument(two) == null }
                )

                // The bug: this bar was drawn under the nav, so Undo was unreachable.
                assertUndoAboveNav(activity, "multi-select")

                val action = undoAction(activity)
                assertNotNull("the Moved bar offers Undo", action)
                action!!.performClick()
                assertTrue(
                    "Undo restores both documents",
                    poll { repo.getDocument(one) != null && repo.getDocument(two) != null }
                )
                assertEquals(3, repo.getDocument(one)!!.pageCount)
                assertEquals(2, repo.getDocument(two)!!.pageCount)
                assertTrue("Trash is empty again", repo.listTrash().isEmpty())
            }
        }
    }

    @Test
    fun `single row Move to Trash also keeps Undo above the nav`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Anchor row")

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertTrue("the row is listed", poll { cards(activity).any { it.doc?.id == id } })
                layoutDecor(activity)

                cards(activity).first { it.doc?.id == id }.moreButton.performClick()
                idle()
                // UI-B5: the row's ⋮ is the shared Actions sheet; its Trash row still
                // ends in the shell's anchored Undo notice.
                val actions = latestDialog("the row's Actions sheet")
                val delete = actions.findViewById<View>(R.id.rowMoreDelete)
                assertNotNull("the sheet offers Move to Trash", delete)
                delete!!.performClick()
                idle()
                latestDialog("the trash confirmation")
                    .findViewById<View>(R.id.swSheetPrimary).performClick()
                assertTrue("the document leaves the library", poll { repo.getDocument(id) == null })

                // Regression guard: this path already passed the nav host as its anchor.
                assertUndoAboveNav(activity, "single row")

                val action = undoAction(activity)
                assertNotNull("the Moved bar offers Undo", action)
                action!!.performClick()
                assertTrue("Undo restores the document", poll { repo.getDocument(id) != null })
                assertTrue("Trash is empty again", repo.listTrash().isEmpty())
            }
        }
    }

    // ---- geometry ----------------------------------------------------------

    /**
     * The "Moved to Trash" bar must be laid out entirely above the shell's nav
     * host, and its Undo action must be the view a tap at the action's centre
     * reaches. Fails with both rectangles (and their screen size) so the phone
     * numbers can be compared directly.
     */
    private fun assertUndoAboveNav(activity: Activity, path: String) {
        assertTrue("$path: the Moved bar is up", poll { snackbarBar(activity) != null })
        settle(activity)

        val decor = activity.window!!.decorView as ViewGroup
        val bar = requireNotNull(snackbarBar(activity)) { "$path: the Moved bar is up" }
        val nav = navHost(activity)
        val barRect = layoutRectIn(decor, bar)
        val navRect = layoutRectIn(decor, nav)

        assertTrue(
            "$path: the bar and the nav are laid out (bar=$barRect nav=$navRect)",
            bar.height > 0 && nav.height > 0 && bar.width > 0
        )
        assertEquals(
            "$path: the bar has finished sliding in",
            0f,
            bar.translationY,
            0.01f
        )
        assertTrue(
            "$path: the Moved-to-Trash bar must sit ABOVE the bottom nav " +
                "(bar=$barRect nav=$navRect, ${decor.width}x${decor.height})",
            barRect.bottom <= navRect.top
        )

        val action = undoAction(activity)
        assertNotNull("$path: Undo is present", action)
        val actionRect = layoutRectIn(decor, action!!)
        assertTrue(
            "$path: Undo lies inside its own bar (action=$actionRect bar=$barRect)",
            barRect.contains(actionRect)
        )
        assertEquals(
            "$path: a tap at Undo's centre must reach Undo, not the nav " +
                "(action=$actionRect bar=$barRect nav=$navRect)",
            action,
            topmostAt(decor, actionRect.centerX(), actionRect.centerY())
        )
    }

    /** The bar's layout position settles: measure, animate in, lay out again. */
    private fun settle(activity: Activity) {
        layoutDecor(activity)
        advance(600)
        layoutDecor(activity)
        idle()
    }

    private fun layoutDecor(activity: Activity) {
        val decor = activity.window.decorView
        val dm = activity.resources.displayMetrics
        repeat(3) {
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
            )
            decor.layout(0, 0, dm.widthPixels, dm.heightPixels)
            idle()
        }
    }

    private fun navHost(activity: Activity): View =
        requireNotNull(activity.findViewById<View>(R.id.swShellNav)?.parent as? View) {
            "the shell's nav host is attached"
        }

    /**
     * [view]'s laid-out rectangle in [ancestor]'s coordinates. Layout only: a
     * slide animation's translation is not part of where a settled bar lives.
     */
    private fun layoutRectIn(ancestor: View, view: View): Rect {
        val rect = Rect(0, 0, view.width, view.height)
        var current: View = view
        while (current !== ancestor) {
            val parent = current.parent as? View ?: break
            rect.offset(current.left - parent.scrollX, current.top - parent.scrollY)
            current = parent
        }
        return rect
    }

    /**
     * The deepest visible view a tap at (x, y) lands on, in draw order: the
     * last-drawn (top-most) child that contains the point wins, like dispatch.
     * Uses laid-out rectangles, so a bar shifted by its slide animation is not
     * counted as reachable.
     */
    private fun topmostAt(root: ViewGroup, x: Int, y: Int): View? {
        var hit: View? = null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val rect = layoutRectIn(root, child)
            if (!rect.contains(x, y)) continue
            if (child is ViewGroup) {
                hit = topmostAt(child, x - rect.left, y - rect.top) ?: child
            } else {
                hit = child
            }
        }
        return hit
    }

    // ---- helpers ----------------------------------------------------------

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun advance(ms: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun poll(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun cards(activity: ShellActivity): List<DocCard> {
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        return (0 until list.childCount).map { list.getChildAt(it) as DocCard }
    }

    private fun findById(root: View, id: Int): View? {
        if (root.id == id) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findById(root.getChildAt(i), id)?.let { return it }
            }
        }
        return null
    }

    /** The newest showing titled SwSheet; fails with [what] when none is up. */
    private fun latestDialog(what: String): android.app.Dialog {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            val dialog = ShadowDialog.getLatestDialog()
            if (dialog != null && dialog.isShowing &&
                dialog.findViewById<View>(R.id.swSheetTitle) != null
            ) {
                return dialog
            }
            Thread.sleep(25)
        }
        throw AssertionError("$what never appeared")
    }

    private fun undoAction(activity: Activity): TextView? =
        findById(
            activity.window!!.decorView,
            com.google.android.material.R.id.snackbar_action
        ) as? TextView

    /** The snackbar's own layout (the direct child of the window content frame). */
    private fun snackbarBar(activity: Activity): View? {
        val decor = activity.window!!.decorView
        val action = findById(decor, com.google.android.material.R.id.snackbar_action) ?: return null
        var current: View = action
        while (true) {
            val parent = current.parent as? View ?: return null
            if (parent.id == android.R.id.content || parent === decor) return current
            current = parent
        }
    }
}
