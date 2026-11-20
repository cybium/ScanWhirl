package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * LEAD ADD-ON (W5): at 100/130/200 % type on 320dp and 360dp, in English and Hindi,
 * the Text screen may wrap a label only between words. A word split across two
 * lines ("Sh / are text", "Save .tx / t"), an ellipsis, clipped text or a control
 * under 48dp is a defect.
 *
 * The helper walks every laid-out TextView label and every criterion is checked on
 * the real Layout, not on estimates.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
class OcrLabelFitTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repo = ScanRepository(app)
    private var docId = ""

    private val actionIds = listOf(
        R.id.btnTidyText, R.id.btnCopy, R.id.btnShare, R.id.btnSave, R.id.btnToWord,
        R.id.btnCopyAll, R.id.btnSaveEdits, R.id.btnEditText, R.id.ocrLangChip,
        R.id.btnUndo, R.id.btnRedo
    )

    @Before
    fun setUp() {
        docId = DocFixtures.saved(repo, app, pages = 2, name = "Invoice 0231")
        repo.updateOcrPages(
            docId,
            mapOf(
                "page_000.jpg" to "पहला पन्ना\nfirst page words",
                "page_001.jpg" to "दूसरा पन्ना\nsecond page words"
            )
        )
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h640dp-xhdpi")
    }

    @Test
    fun `labels never break a word, clip or ellipsize at any size or language`() {
        for (width in listOf(320, 360)) {
            for (scale in listOf(1.0f, 1.3f, 2.0f)) {
                for (language in listOf("en-rUS", "hi-rIN")) {
                    val label = "$language ${width}dp @${scale}x"
                    RuntimeEnvironment.setQualifiers("$language-w${width}dp-h640dp-xhdpi")
                    RuntimeEnvironment.setFontScale(scale)
                    checkScreen(label)
                }
            }
        }
    }

    private fun checkScreen(label: String) {
        val intent = Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_DOC_ID, docId)
            .putExtra(OcrEditorActivity.EXTRA_TITLE, "Invoice 0231")
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent).setup().use { controller ->
            shadowOf(Looper.getMainLooper()).idle()
            val activity = controller.get()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val root = content.getChildAt(0) ?: content
            val dm = activity.resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            root.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, w, h)
            shadowOf(Looper.getMainLooper()).idle()

            val problems = mutableListOf<String>()
            walk(root, label, root, problems, insideScroller = false)

            val density = dm.density
            for (id in actionIds) {
                val view = activity.findViewById<View>(id) ?: continue
                if (view.visibility != View.VISIBLE || !view.isShown) continue
                val min = (48 * density).toInt()
                if (view.height < min || view.width < min) {
                    problems += "$label: ${name(activity, id)} is ${view.width}x${view.height}px, " +
                        "under 48dp (${min}px)"
                }
            }
            assertTrue(
                "Text screen label problems:\n" + problems.joinToString("\n"),
                problems.isEmpty()
            )
        }
    }

    private fun walk(
        view: View,
        label: String,
        root: View,
        problems: MutableList<String>,
        insideScroller: Boolean
    ) {
        if (view.visibility != View.VISIBLE) return
        val scroller = insideScroller || view is HorizontalScrollView
        if (view is TextView && view !is EditText) {
            checkLabel(view, label, problems)
            if (!scroller) checkEdges(view, label, root, problems)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walk(view.getChildAt(i), label, root, problems, scroller)
            }
        }
    }

    private fun checkLabel(view: TextView, label: String, problems: MutableList<String>) {
        val text = view.text?.toString().orEmpty()
        if (text.isBlank()) return
        val layout = view.layout ?: return
        val available = view.width - view.totalPaddingLeft - view.totalPaddingRight
        for (line in 0 until layout.lineCount) {
            if (layout.getEllipsisCount(line) > 0) {
                problems += "$label: \"$text\" is ellipsized on line $line"
            }
            if (layout.getLineMax(line) > available + 0.5f) {
                problems += "$label: \"$text\" line $line is " +
                    "${layout.getLineMax(line).toInt()}px wide but $available px fit"
            }
            val start = layout.getLineStart(line)
            if (line > 0 && start in 1 until text.length) {
                val before = text[start - 1]
                val after = text[start]
                if (!before.isWhitespace() && !after.isWhitespace() && after != '\n') {
                    problems += "$label: \"$text\" breaks the word at \"${text.substring(
                        maxOf(0, start - 8), minOf(text.length, start + 8)
                    )}\" (line $line)"
                }
            }
        }
        val needed = layout.height + view.totalPaddingTop + view.totalPaddingBottom
        if (needed > view.height + 0.5f) {
            problems += "$label: \"$text\" needs ${needed}px but is ${view.height}px tall"
        }
    }

    private fun checkEdges(
        view: View,
        label: String,
        root: View,
        problems: MutableList<String>
    ) {
        if (view.right > root.width + 0.5f) {
            problems += "$label: \"${(view as TextView).text}\" right edge ${view.right} " +
                "exceeds screen ${root.width}"
        }
        if (view.bottom > root.height + 0.5f) {
            problems += "$label: \"${(view as TextView).text}\" bottom edge ${view.bottom} " +
                "exceeds screen ${root.height}"
        }
    }

    private fun name(activity: OcrEditorActivity, id: Int): String =
        activity.resources.getResourceEntryName(id)
}
