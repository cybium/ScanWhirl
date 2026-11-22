package com.scanwhirl.app.ui.tools.linkindex

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.LinkIndexTrial
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * LX: the Link index pick and proposal screens must not clip or ellipsize any
 * label at 100 % system font on a 360dp and a 411dp phone, in EN and HI.
 */
abstract class LinkIndexFontFitBase {

    private lateinit var app: Application
    private var controller: ActivityController<LinkIndexActivity>? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false)
        LinkIndexTrial.autoAcceptForTests = true
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        RuntimeEnvironment.setFontScale(1.0f)
        LinkIndexTrial.resetForTests(app)
        ProGate.setTestOverride(app, null)
    }

    private fun collect(view: View, out: MutableList<TextView>) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView && view.text.isNotBlank()) out += view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collect(view.getChildAt(i), out)
        }
    }

    @Test(timeout = 30_000)
    fun `every visible label fits at 100 percent font`() {
        controller = Robolectric.buildActivity(LinkIndexActivity::class.java).setup()
        val activity = controller!!.get()
        shadowOf(Looper.getMainLooper()).idle()
        activity.showSetForTests()
        shadowOf(Looper.getMainLooper()).idle()

        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(2) { root.measure(w, h); root.layout(0, 0, dm.widthPixels, dm.heightPixels) }

        val labels = mutableListOf<TextView>()
        collect(root, labels)
        assertTrue("the screen has labels", labels.size >= 5)
        val problems = mutableListOf<String>()
        for (label in labels) {
            val text = label.text.toString()
            val layout = label.layout ?: run {
                problems += "$text: never laid out"
                continue
            }
            val available = label.width - label.totalPaddingLeft - label.totalPaddingRight
            for (line in 0 until layout.lineCount) {
                if (layout.getEllipsisCount(line) != 0) problems += "$text: ellipsized on line $line"
                if (layout.getLineMax(line) > available + 1f) {
                    problems += "$text: line $line exceeds its width"
                }
            }
            if (layout.height + label.totalPaddingTop + label.totalPaddingBottom > label.height + 1) {
                problems += "$text: clipped vertically"
            }
        }
        assertTrue("100% font, ${dm.widthPixels}px: ${problems.joinToString(" | ")}", problems.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class LinkIndexFontFitEn360Test : LinkIndexFontFitBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h780dp-xhdpi")
class LinkIndexFontFitHi360Test : LinkIndexFontFitBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h780dp-xhdpi")
class LinkIndexFontFitEn411Test : LinkIndexFontFitBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w411dp-h780dp-xhdpi")
class LinkIndexFontFitHi411Test : LinkIndexFontFitBase()
