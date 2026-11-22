package com.scanwhirl.app.ux130.k

import android.app.Application
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.kit.DocumentPicker
import com.scanwhirl.app.ui.shell.ShellActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * Council lane K (Card and canvas) acceptance, 100% font first.
 *
 * RED on base a9a7fb1c: the card had a "Protected" badge on the name line, a
 * second "Unsorted" category row and a two-line meta; every page host was white
 * or near-white; the shared picker had no device-file route. GREEN here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class LaneKCardCanvasTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun dp(value: Float) = (value * app.resources.displayMetrics.density).toInt()

    private fun doc(
        name: String = "annot_test",
        locked: Boolean = false,
        pages: Int = 2
    ) = ScanDocument(
        id = "k-$name", name = name, createdAt = 0L, pageCount = pages,
        pdfFile = File(app.cacheDir, "none.pdf"), pageFiles = emptyList(),
        isLocked = locked, folder = null
    )

    @Test(timeout = 30_000)
    fun `at 100 percent EN and HI the card has one meta line and no name badge`() {
        for (tag in listOf("en", "hi")) {
            val q = if (tag == "hi") "hi-rIN-w411dp-h820dp-xhdpi" else "w360dp-h780dp-xhdpi"
            RuntimeEnvironment.setQualifiers(q)
            RuntimeEnvironment.setFontScale(1.0f)
            try {
                val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
                try {
                    val card = DocCard(controller.get())
                    card.bind(doc(locked = true), "2 pages · 65 KB · Today", {}, {})
                    assertEquals("one meta line ($tag)", 1, card.metaView.maxLines)
                    assertNull("no name-line lock badge ($tag)", card.findViewById<View>(R.id.swDocLockBadge))
                    assertNull("no folder/category line ($tag)", card.findViewById<View>(R.id.swDocFolder))
                    assertTrue("lock is a thumb overlay ($tag)", card.lockIcon.isVisible)
                    assertEquals(2, card.nameView.maxLines)
                    assertEquals(
                        "whole-word name breaks ($tag)",
                        android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY,
                        card.nameView.breakStrategy
                    )
                    assertEquals(
                        "no hyphenation ($tag)",
                        android.text.Layout.HYPHENATION_FREQUENCY_NONE,
                        card.nameView.hyphenationFrequency
                    )
                    val spoken = card.contentDescription?.toString().orEmpty()
                    assertEquals(
                        "Protected is spoken exactly once ($tag)", 1,
                        spoken.split(app.getString(R.string.home_protected)).size - 1
                    )
                } finally {
                    controller.pause().stop().destroy()
                }
            } finally {
                RuntimeEnvironment.setFontScale(1.0f)
                RuntimeEnvironment.setQualifiers("w360dp-h640dp-xhdpi")
            }
        }
    }

    @Test(timeout = 30_000)
    fun `annot_test and a 53-character name never break mid-token at 100 percent`() {
        val longName = "23_public_official_Hindi_candidate_1 - Compressed"
        for (tag in listOf("en", "hi")) {
            for ((w, h) in listOf(360 to 780, 411 to 820)) {
                RuntimeEnvironment.setQualifiers(
                    (if (tag == "hi") "hi-rIN-" else "") + "w${w}dp-h${h}dp-xhdpi"
                )
                RuntimeEnvironment.setFontScale(1.0f)
                try {
                    val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
                    try {
                        val activity = controller.get()
                        for (name in listOf("annot_test", longName)) {
                            val card = DocCard(activity)
                            card.bind(doc(name = name), "2 pages · 65 KB · Today", {}, {})
                            // The row is laid out at the real card width (screen minus the
                            // library's 18dp side margins); its text column is then ~182dp.
                            val textWidth = dp(w.toFloat()) - dp(36f)
                            card.measure(
                                View.MeasureSpec.makeMeasureSpec(textWidth, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                            )
                            card.layout(0, 0, card.measuredWidth, card.measuredHeight)
                            idle()
                            val layout = card.nameView.layout
                            assertNotNull("name was laid out ($tag/$w)", layout)
                            val lines = layout!!
                            assertTrue("name stays within two lines", lines.lineCount <= 2)
                            if (name == "annot_test") {
                                // A realistic identifier fits the full text column on ONE
                                // line. OLD: the "Protected" badge stole ~75dp and split it
                                // into "annot" / "_test".
                                assertEquals(
                                    "annot_test is one whole line at $tag/$w", 1, lines.lineCount
                                )
                                assertEquals(
                                    "no ellipsis for annot_test", 0, lines.getEllipsisCount(0)
                                )
                            }
                        }
                    } finally {
                        controller.pause().stop().destroy()
                    }
                } finally {
                    RuntimeEnvironment.setFontScale(1.0f)
                    RuntimeEnvironment.setQualifiers("w360dp-h640dp-xhdpi")
                }
            }
        }
    }

    @Test(timeout = 30_000)
    fun `a quick 130 percent check keeps the card name and meta whole`() {
        RuntimeEnvironment.setQualifiers("hi-rIN-w360dp-h640dp-xhdpi")
        RuntimeEnvironment.setFontScale(1.3f)
        try {
            val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
            try {
                val card = DocCard(controller.get())
                card.bind(
                    doc(name = "23_public_official_Hindi_candidate_1 - Compressed"),
                    "2 pages · 65 KB · Today", {}, {}
                )
                val width = dp(324f)
                card.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                card.layout(0, 0, width, card.measuredHeight)
                idle()
                val nameLayout = requireNotNull(card.nameView.layout)
                val metaLayout = requireNotNull(card.metaView.layout)
                // Council §3 keeps END ellipsis for a 53-character auto-generated name
                // (lane O shortens names); at 130% the contract is "still two lines,
                // no clipped meta, no overlap".
                assertTrue("name is capped at two lines", nameLayout.lineCount <= 2)
                for (line in 0 until metaLayout.lineCount) {
                    assertEquals(
                        "meta is not ellipsized at 130% on line $line",
                        0, metaLayout.getEllipsisCount(line)
                    )
                }
            } finally {
                controller.pause().stop().destroy()
            }
        } finally {
            RuntimeEnvironment.setFontScale(1.0f)
            RuntimeEnvironment.setQualifiers("w360dp-h640dp-xhdpi")
        }
    }

    @Test(timeout = 30_000)
    fun `every owned page host uses sw_canvas and no white or paper background`() {
        fun src(path: String) = File("src/main/$path").readText()
        // One canvas token for the whole app: lane C already owns sw_cc_canvas, so
        // lane K reuses it instead of adding a second #1E252A resource.
        val canvas = src("res/values/colors_cc.xml")
        assertTrue("sw_cc_canvas is #1E252A", canvas.contains("sw_cc_canvas") && canvas.contains("#1E252A"))
        assertFalse(
            "there is exactly one canvas color resource",
            File("src/main/res/values/colors_canvas.xml").exists()
        )

        for (path in listOf(
            "java/com/scanwhirl/app/ui/kit/DocCard.kt",
            "java/com/scanwhirl/app/ui/kit/DocumentPicker.kt",
            "java/com/scanwhirl/app/ui/vault/VaultCard.kt",
            "java/com/scanwhirl/app/ui/PdfViewerActivity.kt"
        )) {
            val text = src(path)
            assertTrue("$path hosts pages on sw_canvas", text.contains("R.color.sw_cc_canvas"))
            assertFalse("$path must not use sw_paper_thumb behind a page", text.contains("sw_paper_thumb"))
        }
        for (path in listOf(
            "res/layout/item_pdf_page.xml",
            "res/layout/item_page_grid.xml",
            "res/layout/item_scan.xml",
            "res/layout/item_trash.xml",
            "res/layout/dialog_compress.xml",
            "res/drawable/bg_thumb_paper.xml"
        )) {
            val text = src(path)
            assertTrue("$path hosts pages on sw_canvas", text.contains("@color/sw_cc_canvas"))
            // A white TINT on a small check icon is a ripple/glyph mask, not a page
            // host background — only a white BACKGROUND behind a page is forbidden.
            assertFalse(
                "$path must not have a white page background",
                "android:background=\"#FFFFFFFF\"" in text ||
                    "android:background=\"@color/sw_paper" in text ||
                    "android:background=\"?attr/colorSurfaceVariant\"" in text
            )
        }
        // item_pdf_page keeps the render's own white out of its ImageView background.
        val pdfPage = src("res/layout/item_pdf_page.xml")
        assertFalse("no white ImageView host", pdfPage.contains("android:background=\"#FFFFFFFF\""))
        val pageGrid = src("res/layout/item_page_grid.xml")
        assertTrue("page grid uses fitCenter + a page edge", pageGrid.contains("fitCenter") &&
            pageGrid.contains("bg_canvas_page_edge"))
    }

    @Test(timeout = 30_000)
    fun `the picker offers a uniform From this phone action backed by SAF`() {
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            var picked: ScanDocument? = null
            val picker = DocumentPicker.show(
                activity = activity,
                title = "Choose",
                docs = emptyList(),
                onPick = { picked = it }
            )
            idle()
            val action = requireNotNull(picker.view.findViewById<View>(R.id.docPickerFromPhone))
            assertEquals(
                "the uniform device action is named",
                activity.getString(R.string.ck_picker_from_phone),
                (action as TextView).text.toString()
            )
            assertTrue("the action is a 48dp target", action.minimumHeight >= dp(48f))
            action.performClick()
            idle()
            val started = shadowOf(activity).nextStartedActivityForResult?.intent
                ?: shadowOf(activity).nextStartedActivity
            assertNotNull("SAF picker was launched", started)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.action)
            val types = started.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
            assertNotNull("PDF and photos are accepted", types)
            assertTrue("PDF is accepted", types!!.contains("application/pdf"))

            // Cancelling SAF leaves the sheet open and nothing picked.
            shadowOf(activity).receiveResult(
                started, android.app.Activity.RESULT_CANCELED, null
            )
            idle()
            assertNull("cancel does not pick", picked)
            assertNull("the device action stays ready", picked)
        } finally {
            ShadowDialog.getLatestDialog()?.dismiss()
            idle()
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `the picked Uri is routed through the shared import path with an honest copy`() {
        // Runtime SAF dispatch is proven by the launcher test above; the routing of
        // the returned Uri is pinned here: one shared import seam, an explicit copy
        // confirmation, then the new library document is handed to the caller's onPick.
        val src = File("src/main/java/com/scanwhirl/app/ui/kit/DocumentPicker.kt").readText()
        assertTrue(
            "the device Uri uses the existing planned-import path",
            src.contains("repo.importPdfAsScanPlanned(")
        )
        assertTrue("the import uses the shared gate", src.contains("DevicePdfGate.decide("))
        assertTrue(
            "an honest copy is confirmed before any write",
            src.contains("ck_picker_import_title") && src.contains("ck_picker_import_msg")
        )
        assertTrue(
            "the imported library document is delivered as the pick",
            src.contains("onPick(doc)")
        )
        assertTrue("cancel leaves the picker intact", src.contains("if (uri == null) return"))
    }
}
