package com.scanwhirl.app.ui.tools.linkindex

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.ui.pagegrid.PageThumbLoader
import com.scanwhirl.app.util.LinkIndexTrial
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * R07 NEW-1: the contents-page strip used to inflate one thumbnail card for every page of a
 * document (10,000 pages are legal). The first test fails on the old LinearLayout strip with 500
 * attached children; the second is the positive control for the normal short-document path.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LinkIndexPageStripFixTest {

    private lateinit var app: Application
    private var controller: ActivityController<LinkIndexActivity>? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, true)
        LinkIndexTrial.autoAcceptForTests = true
        LinkIndexTrial.resetForTests(app)
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        PageThumbLoader.keyStatProbe = null
        LinkIndexTrial.resetForTests(app)
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(app.mainLooper).idle()

    private fun jpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.LTGRAY)
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out))
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun document(name: String, count: Int): ScanDocument {
        val dir = File(app.cacheDir, "lx_fix_$name")
        if (dir.exists()) dir.deleteRecursively()
        assertTrue(dir.mkdirs())
        val bytes = jpegBytes()
        val pages = (0 until count).map { i ->
            File(dir, String.format(Locale.ROOT, "page_%03d.jpg", i)).apply { writeBytes(bytes) }
        }
        val pdf = File(dir, "scan.pdf").apply { writeBytes("%PDF-1.4\n".toByteArray()) }
        return ScanDocument(
            id = "lx_fix_$name", name = name, createdAt = 0L, pageCount = count,
            pdfFile = pdf, pageFiles = pages
        )
    }

    /** Enters step 2 with a fixture. Reflection keeps the test compilable against the old code. */
    private fun openDocument(doc: ScanDocument): LinkIndexActivity {
        controller = Robolectric.buildActivity(LinkIndexActivity::class.java).setup()
        val activity = controller!!.get()
        idle()
        val field = LinkIndexActivity::class.java.getDeclaredField("source")
        field.isAccessible = true
        field.set(activity, doc)
        activity.showSetForTests()
        idle()
        return activity
    }

    private fun forceLayout(view: ViewGroup) {
        val dm = app.resources.displayMetrics
        view.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        idle()
    }

    private fun pageView(activity: LinkIndexActivity, strip: ViewGroup, index: Int): View? {
        if (strip is RecyclerView) {
            strip.scrollToPosition(index)
            forceLayout(strip)
            return strip.findViewHolderForAdapterPosition(index)?.itemView
        }
        // Pre-fix LinearLayout: every page was inflated up front.
        val label = activity.getString(R.string.lx_page_number, index + 1)
        return (0 until strip.childCount).map { strip.getChildAt(it) }
            .firstOrNull { it.contentDescription == label }
    }

    @Test(timeout = 120_000)
    fun `a 500-page document binds only visible cards and page 480 is still selectable`() {
        val activity = openDocument(document("big", 500))
        val strip = activity.findViewById<ViewGroup>(R.id.lxPageStrip)

        forceLayout(strip)
        assertTrue("only a handful of page views are attached, got ${strip.childCount}", strip.childCount <= 12)

        val target = pageView(activity, strip, 480)
        assertNotNull("page 480 can be bound without inflating 480 earlier cards", target)
        assertEquals(
            "the accessibility label is unchanged",
            activity.getString(R.string.lx_page_number, 481),
            target!!.contentDescription.toString()
        )
        target.findViewById<View>(R.id.lxPageRoot).performClick()
        idle()
        assertTrue(
            "the checkbox follows the selection",
            target.findViewById<CheckBox>(R.id.lxPageCheck).isChecked
        )
        assertEquals(
            activity.resources.getQuantityString(R.plurals.lx_pages_selected, 1, 1),
            activity.findViewById<TextView>(R.id.lxPagesSelected).text.toString()
        )
    }

    @Test(timeout = 60_000)
    fun `a short document still binds, labels and selects pages through PageThumbLoader`() {
        var keyComputations = 0
        PageThumbLoader.keyStatProbe = { keyComputations++ }
        val activity = openDocument(document("small", 6))
        val strip = activity.findViewById<ViewGroup>(R.id.lxPageStrip)

        forceLayout(strip)
        assertTrue("a few cards are attached, got ${strip.childCount}", strip.childCount in 1..6)
        val target = pageView(activity, strip, 2)
        assertNotNull("a nearby page is bound", target)
        assertEquals(
            activity.getString(R.string.lx_page_number, 3),
            target!!.contentDescription.toString()
        )
        target.findViewById<View>(R.id.lxPageRoot).performClick()
        idle()
        assertTrue(target.findViewById<CheckBox>(R.id.lxPageCheck).isChecked)
        assertTrue("thumbs are requested through PageThumbLoader", keyComputations > 0)
    }
}
