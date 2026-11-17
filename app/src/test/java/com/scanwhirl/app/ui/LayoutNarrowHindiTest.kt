package com.scanwhirl.app.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.StaticLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.stamp.StampEditorActivity
import com.scanwhirl.app.util.CategoryRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import java.io.File

/**
 * Hindi at the narrowest realistic phone (320dp) and 130 % system text: the
 * geometry half of the 2026-11-16 layout audit (items 1-3, 5-12).
 *
 * Hindi runs ~2.4x the width of English, so a label that fits at w411dp/en/1.0
 * is cut here. Every assertion is geometry on the laid-out text — line widths,
 * ellipsis counts, the height budget, zero-width children and right edges
 * inside the parent — never "the view exists". [expectEllipsis] is used only
 * where the design deliberately keeps one line (a fixed-height dock bar): the
 * text may be shortened, but only *honestly* (with "…"), never hard-cut.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w320dp-h640dp-xhdpi")
class LayoutNarrowHindiTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var defaultPagesFactory: (ParcelFileDescriptor) -> PdfViewerActivity.PdfPages

    private class FakePages(override val pageCount: Int) : PdfViewerActivity.PdfPages {
        override fun render(index: Int, maxDim: Int): Bitmap? = null
        override fun close() = Unit
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
        defaultPagesFactory = PdfViewerActivity.pagesFactory
        RuntimeEnvironment.setFontScale(1.3f)
    }

    @After
    fun tearDown() {
        PdfViewerActivity.pagesFactory = defaultPagesFactory
        // Never leak a 1.3 font scale into other test classes.
        RuntimeEnvironment.setFontScale(1.0f)
    }

    // ---- fixtures ---------------------------------------------------------

    private fun jpegBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(310, 444, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://layout_narrow_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    /** One page; [category] null lets the name rules decide. */
    private fun seed(name: String, category: String? = null): String = runBlocking {
        repo.saveScan(listOf(contentUri(jpegBytes())), null, name, category = category).id
    }

    // ---- layout rig -------------------------------------------------------

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

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

    /** Lays the window out at the 320dp phone's real pixel size. */
    private fun layout(activity: Activity): ViewGroup {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) as? ViewGroup ?: content
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(3) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        idle()
        return root
    }

    private fun density(): Float = app.resources.displayMetrics.density

    /**
     * The text is fully readable: a real width, no line wider than the box, no
     * ellipsis and the lines fit the view's height.
     */
    private fun assertTextFits(tv: TextView, what: String) {
        val layout = tv.layout
        assertNotNull("$what has text but no Layout (never measured): \"${tv.text}\"", layout)
        layout!!
        assertTrue("$what has zero width: \"${tv.text}\"", tv.width > 0)
        val avail = tv.width - tv.totalPaddingLeft - tv.totalPaddingRight
        for (line in 0 until layout.lineCount) {
            assertTrue(
                "$what line $line is ${layout.getLineMax(line).toInt()}px but only $avail px fit " +
                    "(w=${tv.width}, lines=${layout.lineCount}) \"${tv.text}\"",
                layout.getLineMax(line) <= avail + 0.5f
            )
            assertEquals(
                "$what line $line is ellipsized (silently cut) \"${tv.text}\"",
                0, layout.getEllipsisCount(line)
            )
        }
        val top = maxOf(tv.totalPaddingTop, tv.compoundPaddingTop)
        val bottom = maxOf(tv.totalPaddingBottom, tv.compoundPaddingBottom)
        val need = layout.height + top + bottom
        assertTrue(
            "$what needs ${need}px but is ${tv.height}px tall \"${tv.text}\"",
            need <= tv.height + 0.5f
        )
    }

    /**
     * A fixed-height bar may shorten a label, but never silently: when the full
     * text needs more lines than [TextView.getMaxLines], the last line must carry
     * an ellipsis, and no line may overflow the box.
     */
    private fun assertHonestlyCut(tv: TextView, what: String) {
        val layout = tv.layout
        assertNotNull("$what has text but no Layout: \"${tv.text}\"", layout)
        layout!!
        assertTrue("$what has zero width: \"${tv.text}\"", tv.width > 0)
        val avail = tv.width - tv.totalPaddingLeft - tv.totalPaddingRight
        for (line in 0 until layout.lineCount) {
            assertTrue(
                "$what line $line is ${layout.getLineMax(line).toInt()}px but only $avail px fit",
                layout.getLineMax(line) <= avail + 0.5f
            )
        }
        val need = layout.height + maxOf(tv.totalPaddingTop, tv.compoundPaddingTop) +
            maxOf(tv.totalPaddingBottom, tv.compoundPaddingBottom)
        assertTrue("$what needs ${need}px but is ${tv.height}px tall", need <= tv.height + 0.5f)

        val text = tv.text ?: return
        val paint = tv.paint
        val full = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, avail.coerceAtLeast(1))
            .setAlignment(layout.getParagraphAlignment(0))
            .build()
        if (full.lineCount > layout.lineCount) {
            assertTrue(
                "$what cut $full.lineCount lines down to ${layout.lineCount} with no ellipsis " +
                    "(text silently vanishes) \"$text\"",
                layout.getEllipsisCount(layout.lineCount - 1) > 0
            )
        }
    }

    private fun assertChildInsideParent(child: View, what: String) {
        val parent = child.parent as View
        assertTrue(
            "$what right edge ${child.right}px exceeds its parent's ${parent.width}px",
            child.right <= parent.width
        )
    }

    private fun label(cell: ViewGroup): TextView = (0 until cell.childCount)
        .map { cell.getChildAt(it) }
        .filterIsInstance<TextView>()
        .first()

    // ---- 5. Home category chips (highest risk: they vanish) ----------------

    @Test
    fun `home shows every category chip in a horizontal ribbon at 320dp hindi`() {
        CategoryRules.ALL.forEachIndexed { i, cat -> seed("Layout chip doc $i", cat) }
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val row = activity.findViewById<ViewGroup>(R.id.swChipRow)
                assertNotNull("the Home category chip row exists", row)
                assertTrue(
                    "the chips render for five categories",
                    pollUntil { row.childCount == CategoryRules.ALL.size + 1 }
                )
                layout(activity)

                val scroll = row!!.parent as? HorizontalScrollView
                assertNotNull(
                    "chips live in a horizontal scroller, not a clipped LinearLayout " +
                        "(parent is ${row.parent.javaClass.simpleName})",
                    scroll
                )
                assertEquals("the chip scroller id", R.id.swChipScroll, scroll!!.id)
                assertTrue("the chip scroller is on screen", scroll.isShown && scroll.width > 0)

                for (i in 0 until row.childCount) {
                    val chip = row.getChildAt(i) as TextView
                    assertTrue(
                        "chip $i \"${chip.text}\" has zero width (six chips cannot fit 320dp)",
                        chip.width > 0
                    )
                    assertTextFits(chip, "home chip $i")
                    assertTrue(
                        "chip $i \"${chip.text}\" spills out of the row",
                        chip.right <= row.width
                    )
                }
                assertTrue(
                    "six chips are wider than the 320dp phone (row ${row.width}px, " +
                        "scroller ${scroll.width}px)",
                    row.width > scroll.width
                )
                assertTrue(
                    "the trailing chips are reachable by scrolling",
                    scroll.canScrollHorizontally(1)
                )
                scroll.scrollTo(row.width, 0) // clamped to the ribbon's end
                assertTrue("the ribbon really scrolls", scroll.scrollX > 0)
                val last = row.getChildAt(row.childCount - 1) as TextView
                assertTrue(
                    "the last chip \"${last.text}\" lies inside the scrolled viewport",
                    last.left + last.width <= scroll.scrollX + scroll.width + 1
                )
            }
        }
    }

    // ---- 1 + 10. OCR action chips and footnote ----------------------------

    @Test
    fun `ocr action chips wrap their hindi labels instead of cutting them`() {
        val docId = seed("OCR layout doc")
        runBlocking {
            repo.updateOcrPages(docId, mapOf("page_000.jpg" to "पढ़ा गया पाठ। दूसरी पंक्ति।"))
        }
        val intent = Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_DOC_ID, docId)
        ActivityScenario.launch<OcrEditorActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                layout(activity)
                for (id in intArrayOf(R.id.btnTidyText, R.id.btnCopy, R.id.btnShare)) {
                    val chip = activity.findViewById<com.scanwhirl.app.ui.kit.SwChip>(id)
                    assertNotNull("OCR chip $id is on the action row", chip)
                    assertTrue("OCR chip $id has a width", chip!!.width > 0)
                    val text = chip.labelView
                    assertTrue("OCR chip $id label has a width: \"${text.text}\"", text.width > 0)
                    assertTextFits(text, "ocr chip $id")
                    assertTrue(
                        "OCR chip $id wraps onto at most two lines (${text.lineCount})",
                        text.lineCount in 1..2
                    )
                }
                val foot = activity.findViewById<TextView>(R.id.ocrFootNote)
                assertTrue("the OCR footnote is shown", foot.isShown)
                assertTextFits(foot, "ocr footnote")
            }
        }
    }

    // ---- 2. Document dock buttons ----------------------------------------

    @Test
    fun `document dock buttons show their whole hindi labels`() {
        val id = seed("Dock layout doc")
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id))
            .use { scenario ->
                scenario.onActivity { activity ->
                    pollUntil {
                        activity.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                    }
                    layout(activity)
                    for (dockId in intArrayOf(R.id.btnShare, R.id.btnEdit, R.id.btnText, R.id.btnMore)) {
                        val button = activity.findViewById<TextView>(dockId)
                        assertNotNull("dock button $dockId exists", button)
                        assertTrue("dock button $dockId is shown", button!!.isShown)
                        assertTextFits(button, "dock $dockId")
                        assertChildInsideParent(button, "dock $dockId")
                    }
                }
            }
    }

    // ---- 3. Trash actions stack when the words need it -------------------

    @Test
    fun `trash actions fit or stack honestly at normal and large hindi text`() {
        repeat(2) { i -> repo.moveToTrash(seed("Trash layout doc $i")) }
        for (scale in floatArrayOf(1.0f, 1.3f)) {
            RuntimeEnvironment.setFontScale(scale)
            ActivityScenario.launch<TrashActivity>(TrashActivity.intent(app)).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(
                        "trash rows load @ $scale",
                        pollUntil { activity.findViewById<ViewGroup>(R.id.trashList).childCount > 0 }
                    )
                    layout(activity)
                    val list = activity.findViewById<ViewGroup>(R.id.trashList)
                    val row = list.getChildAt(0)
                    val actions = row.findViewById<LinearLayout>(R.id.trashActions)
                    val delete = row.findViewById<TextView>(R.id.btnTrashDelete)
                    val restore = row.findViewById<TextView>(R.id.btnTrashRestore)
                    assertTrue("delete has a width @ $scale", delete.width > 0)
                    assertTrue("restore has a width @ $scale", restore.width > 0)
                    assertTextFits(delete, "trash delete @ $scale")
                    assertTextFits(restore, "trash restore @ $scale")
                    assertTrue(
                        "the two actions stay inside the row @ $scale " +
                            "(${delete.width}+${restore.width} > ${actions.width})",
                        delete.width + restore.width + (8 * density()).toInt() <= actions.width + 0.5f ||
                            actions.orientation == LinearLayout.VERTICAL
                    )
                    if (actions.orientation == LinearLayout.VERTICAL) {
                        assertTrue(
                            "stacked actions use the row width @ $scale",
                            restore.width >= actions.width - 1 && delete.width >= actions.width - 1
                        )
                    }
                }
            }
        }
    }

    // ---- 6. Export result dock -------------------------------------------

    @Test
    fun `export result dock keeps both actions readable`() {
        val img = File(app.cacheDir, "layout_export_${System.nanoTime()}.jpg").apply { writeBytes(jpegBytes()) }
        val intent = ExportResultActivity.intent(
            app, img.path, img.path, "image/jpeg", "हिंदी निर्यात",
            4096, 2048, 620, 880, null, null
        )
        ActivityScenario.launch<ExportResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                layout(activity)
                val buttons = intArrayOf(R.id.btnShareResult, R.id.btnSaveResult)
                    .map { activity.findViewById<TextView>(it) }
                for (button in buttons) {
                    assertTrue("export dock button is shown", button.isShown)
                    assertTextFits(button, "export dock button")
                    assertChildInsideParent(button, "export dock button")
                }
                val row = buttons[1].parent as ViewGroup
                assertTrue(
                    "both dock buttons share the row without overflowing",
                    buttons[0].right <= row.width && buttons[1].right <= row.width
                )
            }
        }
    }

    // ---- 7. Saved snackbar message ---------------------------------------

    @Test
    fun `a long saved-file snackbar message is shortened honestly`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host = FrameLayout(activity)
                val view = LayoutInflater.from(activity)
                    .inflate(R.layout.snackbar_saved, host, false)
                val message = view.findViewById<TextView>(R.id.snackMessage)
                // The real worst case: a Hindi file name inside the saved message.
                message.text = activity.getString(
                    R.string.export_saved_new_copy,
                    "कर कागज़ात दो हज़ार छब्बीस की पूरी नकल अंतिम संस्करण.pdf"
                )
                val open = view.findViewById<View>(R.id.snackOpen)
                val undo = view.findViewById<View>(R.id.snackUndo)
                val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                open.measure(unspecified, unspecified)
                undo.measure(unspecified, unspecified)
                // The room the snackbar row actually gives the message: the phone
                // width minus the layout's 16dp/8dp side paddings and the actions.
                val screen = activity.resources.displayMetrics.widthPixels
                val chrome = ((16 + 8) * density()).toInt()
                val avail = screen - chrome - open.measuredWidth - undo.measuredWidth
                assertTrue("the message column has room ($avail px)", avail > 0)
                message.measure(
                    View.MeasureSpec.makeMeasureSpec(avail, View.MeasureSpec.EXACTLY), unspecified
                )
                message.layout(0, 0, message.measuredWidth, message.measuredHeight)
                assertHonestlyCut(message, "snackbar message")
            }
        }
    }

    // ---- 8. Stamp editor hint gets its own line ---------------------------

    @Test
    fun `stamp hint gets its own line and stays readable`() {
        val id = seed("Stamp layout doc")
        val intent = Intent(app, StampEditorActivity::class.java)
            .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val screen = Robolectric.buildActivity(StampEditorActivity::class.java, intent).setup().get()
        layout(screen)
        val hint = screen.findViewById<TextView>(R.id.selectionHint)
        assertTrue("the selection hint is shown", hint.isShown)
        assertTextFits(hint, "stamp hint")
        val row = hint.parent as ViewGroup
        val inner = row.width - row.paddingLeft - row.paddingRight
        assertTrue(
            "the hint spans its row instead of the leftover beside the buttons " +
                "(hint ${hint.width}px of ${inner}px)",
            hint.width >= inner - 0.5f
        )
        screen.finish()
    }

    // ---- 9. Vault banner --------------------------------------------------

    @Test
    fun `the vault banner title and body wrap inside the banner`() {
        seed("Vault banner doc")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setVaultMode(true)
                layout(activity)
                val banner = activity.findViewById<ViewGroup>(R.id.vaultBanner)
                assertTrue("the vault banner is shown", banner.isShown)
                val wanted = listOf(
                    activity.getString(R.string.vault_banner_title),
                    activity.getString(R.string.vault_banner_sub)
                )
                val found = mutableListOf<TextView>()
                fun walk(v: View) {
                    if (v is TextView && v.text.toString() in wanted) found += v
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(banner)
                assertEquals("both banner lines are laid out", 2, found.size)
                for (tv in found) {
                    assertTextFits(tv, "vault banner \"${tv.text}\"")
                    assertChildInsideParent(tv, "vault banner \"${tv.text}\"")
                }
            }
        }
    }

    // ---- 11. Reader dock labels ------------------------------------------

    @Test
    fun `reader dock labels fit their fixed 66dp bar`() {
        val id = seed("Reader dock layout doc")
        PdfViewerActivity.pagesFactory = { FakePages(2) }
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, id, "Reader dock"))
            .use { scenario ->
                scenario.onActivity { activity ->
                    idle()
                    layout(activity)
                    val dock = activity.findViewById<ViewGroup>(R.id.readerDock)
                    assertTrue("the reader dock is shown", dock.isShown)
                    for (cellId in intArrayOf(
                        R.id.readerDockShare, R.id.readerDockPages, R.id.readerDockText,
                        R.id.readerDockSign, R.id.readerDockMore
                    )) {
                        val cell = activity.findViewById<ViewGroup>(cellId)
                        val text = label(cell)
                        assertTextFits(text, "reader dock label $cellId")
                        assertTrue(
                            "reader dock cell $cellId is a tappable column",
                            cell.width > 0 && cell.height > 0
                        )
                    }
                }
            }
    }

    // ---- 12. Selection header "Select all" -------------------------------

    @Test
    fun `select all stays on one line in the selection header`() {
        seed("Select layout doc")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnSelect).performClick()
                layout(activity)
                val all = activity.findViewById<TextView>(R.id.btnSelSelectAll)
                assertTrue("Select all is shown in selection mode", all.isShown)
                assertTextFits(all, "select all")
                assertEquals("Select all stays on one line", 1, all.lineCount)
                assertChildInsideParent(all, "select all")
            }
        }
    }
}
