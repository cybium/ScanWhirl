package com.scanwhirl.app.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TYPE12 item 3: the Document workspace's page-selection action bar must keep its five
 * labels at the 12sp readable floor, wrap them instead of clipping, and never split a
 * word at 200 % type on a 320 dp phone, in English and Hindi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w320dp-h640dp-xhdpi")
class SelectionActionBarFitTest {

    private val actionIds = listOf(
        R.id.btnSelRotate, R.id.btnSelDelete, R.id.btnSelExtract,
        R.id.btnSelShare, R.id.btnSelMore
    )

    @Test(timeout = 30_000)
    fun `selection labels are 12sp and wrap without clipping or split words at 200 percent`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Invoice 0231")
        val problems = mutableListOf<String>()
        for (locale in listOf("en-rUS", "hi-rIN")) {
            RuntimeEnvironment.setQualifiers("$locale-w320dp-h640dp-xhdpi")
            RuntimeEnvironment.setFontScale(2.0f)
            val intent = DocumentViewerActivity.intent(app, id)
            val controller = Robolectric.buildActivity(DocumentViewerActivity::class.java, intent).setup()
            try {
                shadowOf(Looper.getMainLooper()).idle()
                val activity = controller.get()
                activity.findViewById<View>(R.id.ux130_l4_select_pages).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                activity.toggleSelected(0)
                shadowOf(Looper.getMainLooper()).idle()
                val bar = activity.findViewById<LinearLayout>(R.id.selectionActionBar)
                assertTrue("$locale: selection action bar is visible", bar.visibility == View.VISIBLE)
                val root = activity.findViewById<View>(android.R.id.content)
                val dm = activity.resources.displayMetrics
                val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
                val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                repeat(2) {
                    root.measure(w, h)
                    root.layout(0, 0, dm.widthPixels, dm.heightPixels)
                }
                val sp = dm.scaledDensity
                for (actionId in actionIds) {
                    val label = activity.findViewById<TextView>(actionId)
                    val text = label.text?.toString().orEmpty()
                    val where = "$locale \"$text\""
                    if (label.textSize / sp < 11.99f) problems += "$where is under 12sp"
                    if (label.maxLines < 2) problems += "$where cannot wrap (maxLines=${label.maxLines})"
                    val layout = label.layout ?: run {
                        problems += "$where was not laid out"
                        continue
                    }
                    val available = label.width - label.totalPaddingLeft - label.totalPaddingRight
                    for (line in 0 until layout.lineCount) {
                        if (layout.getEllipsisCount(line) > 0) problems += "$where ellipsized on line $line"
                        if (layout.getLineMax(line) > available + 0.5f) {
                            problems += "$where line $line is ${layout.getLineMax(line).toInt()}px, only $available fit"
                        }
                        val start = layout.getLineStart(line)
                        if (line > 0 && start in 1 until text.length) {
                            val before = text[start - 1]
                            val after = text[start]
                            if (!before.isWhitespace() && !after.isWhitespace() && after != '\n') {
                                problems += "$where splits a word at line $line"
                            }
                        }
                    }
                    val needed = layout.height + label.totalPaddingTop + label.totalPaddingBottom
                    if (needed > label.height + 0.5f) {
                        problems += "$where needs ${needed}px but is ${label.height}px tall"
                    }
                }
            } finally {
                controller.pause().stop().destroy()
                RuntimeEnvironment.setFontScale(1.0f)
            }
        }
        assertTrue(
            "Selection action bar label problems:\n" + problems.joinToString("\n"),
            problems.isEmpty()
        )
    }
}
