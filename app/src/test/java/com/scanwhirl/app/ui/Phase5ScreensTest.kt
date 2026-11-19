package com.scanwhirl.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * test25 Phase 5 — remaining screens: Settings grouping/icons/switch tints (D1),
 * page-grid glass cells, DocumentViewer category chip, and the inset glass docks
 * on ScanReview / PageViewer / DocumentViewer.
 *
 * New tests live in this new file only; existing test files are untouched.
 * The helper functions below are local copies of the UiSmokeTest patterns
 * (contentUri / jpegBytes / seedDoc / pollUntil / renderActivityPng).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Phase5ScreensTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        // Fresh Pro state per test (same hygiene as UiSmokeTest).
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
    }

    // --- helpers (local copies; UiSmokeTest must not be modified) ------------

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://phase5/smoke_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private fun jpegBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(310, 444, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun seedDoc(name: String, pages: Int = 1): String = runBlocking {
        val uris = (0 until pages).map { contentUri(jpegBytes()) }
        repo.saveScan(uris, null, name).id
    }

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return condition()
    }

    /** Taps the T4.2 picker row whose tag is [category] (local copy of the Home helper). */
    private fun pickPickerRow(category: String) {
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("the picker must be open", dialog)
        val rows = dialog!!.findViewById<android.widget.LinearLayout>(R.id.pickerRows)
        assertNotNull("the picker must have rows", rows)
        val row = (0 until rows!!.childCount).map { rows.getChildAt(it) }
            .firstOrNull { it.tag == category }
        assertNotNull("row for $category must exist", row)
        row!!.performClick()
    }

    private fun renderActivityPng(activity: android.app.Activity, outPath: String) {
        try {
            val root = activity.window.decorView
            val wSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY)
            root.measure(wSpec, hSpec)
            root.layout(0, 0, 1080, 2200)
            val bmp = Bitmap.createBitmap(1080, 2200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            root.draw(canvas)
            val outFile = File(outPath)
            outFile.parentFile?.mkdirs()
            java.io.FileOutputStream(outFile).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bmp.recycle()
        } catch (_: Exception) {
        }
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    /**
     * Drawable identity by rendered pixels — Drawable.ConstantState instances are
     * per-inflation, so constantState equality is not reliable across two loads.
     */
    private fun sameDrawable(actual: android.graphics.drawable.Drawable?, resId: Int): Boolean {
        val expected = ContextCompat.getDrawable(app, resId) ?: return false
        if (actual == null || actual.javaClass != expected.javaClass) return false
        val w = 160
        val h = 64
        fun render(d: android.graphics.drawable.Drawable): Bitmap {
            d.setBounds(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            d.draw(Canvas(bmp))
            return bmp
        }
        return render(actual).sameAs(render(expected))
    }

    /**
     * Row-icon identity by rendered pixels, normalised with the cyan_primary tint
     * production applies at the ImageView level — the comparison then holds whether
     * or not the tint was baked into the drawable at inflate time (tinting is
     * idempotent: SrcIn with the same colour over an already-tinted copy).
     */
    private fun iconMatches(iv: ImageView?, resId: Int): Boolean {
        val actual = iv?.drawable ?: return false
        val expected = ContextCompat.getDrawable(app, resId) ?: return false
        if (actual.intrinsicWidth != expected.intrinsicWidth ||
            actual.intrinsicHeight != expected.intrinsicHeight
        ) return false
        val w = actual.intrinsicWidth.coerceAtLeast(1)
        val h = actual.intrinsicHeight.coerceAtLeast(1)
        val tint = ContextCompat.getColor(app, R.color.cyan_primary)
        DrawableCompat.setTint(actual.mutate(), tint)
        DrawableCompat.setTint(expected.mutate(), tint)
        fun render(d: android.graphics.drawable.Drawable): Bitmap {
            d.setBounds(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            d.draw(Canvas(bmp))
            return bmp
        }
        return render(actual).sameAs(render(expected))
    }

    // --- 5A Settings --------------------------------------------------------

    @Test
    fun `settings hides the theme row and pins orbital dark`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // D1: row stays in the XML (locked ID) but is hidden at runtime.
                val rowTheme = activity.findViewById<View>(R.id.rowTheme)
                assertNotNull("rowTheme must remain in the tree", rowTheme)
                assertEquals(View.GONE, rowTheme.visibility)
                // …and the summary is pinned to the one theme we ship.
                val subTheme = activity.findViewById<TextView>(R.id.subTheme)
                assertEquals("Orbital Dark", subTheme.text.toString())
            }
        }
    }

    @Test
    fun `settings shows its rows - hiding About and Trash never blanks the page`() {
        // Regression (lead phone check 11-15): hideSection(rowTrash) hid the whole page.
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for (rowId in listOf(R.id.rowFont, R.id.rowLanguage, R.id.rowViewer, R.id.rowAutoEnhance)) {
                    val row = activity.findViewById<View>(rowId)
                    assertTrue(
                        "row ${activity.resources.getResourceEntryName(rowId)} is shown",
                        row.isShown
                    )
                }
                // The moved sections are hidden (they live in Me now) …
                assertFalse("About lives in Me", activity.findViewById<View>(R.id.rowAbout).isShown)
                assertFalse("Trash lives in Me", activity.findViewById<View>(R.id.rowTrash).isShown)
                // … but rows added at runtime into the old About card must stay visible
                // (lead phone check 11-15: they vanished with the hidden About card).
                assertTrue(
                    "Block screenshots switch is shown",
                    activity.findViewById<View>(R.id.rowBlockScreenshots).isShown
                )
                if (com.scanwhirl.app.BuildConfig.TEST_BUILD) {
                    assertTrue(
                        "test-build Free/Pro switch is shown",
                        activity.findViewById<View>(R.id.rowTestFree).isShown
                    )
                }
            }
        }
    }

    @Test
    fun `settings rows carry cyan icons and no text chevrons`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                val rows = mapOf(
                    R.id.rowTheme to R.drawable.ic_palette,
                    R.id.rowFont to R.drawable.ic_font,
                    R.id.rowGreeting to R.drawable.ic_greeting,
                    R.id.rowWallpaper to R.drawable.ic_wallpaper,
                    R.id.rowLanguage to R.drawable.ic_language,
                    R.id.rowViewer to R.drawable.ic_viewer,
                    R.id.rowAutoEnhance to R.drawable.ic_auto,
                    R.id.rowAbout to R.drawable.ic_info,
                    R.id.rowPrivacy to R.drawable.ic_privacy
                )
                for ((rowId, iconId) in rows) {
                    val row = activity.findViewById<ViewGroup>(rowId)
                    val icon = row.getChildAt(0) as? ImageView
                    assertNotNull("row $rowId leads with an ImageView", icon)
                    assertTrue("row $rowId icon matches ${app.resources.getResourceName(iconId)}",
                        iconMatches(icon, iconId))
                }

                // Chevron rows end in a 20dp ic_chevron_right ImageView…
                val chevronRows = intArrayOf(
                    R.id.rowTheme, R.id.rowFont, R.id.rowLanguage,
                    R.id.rowViewer, R.id.rowAbout, R.id.rowPrivacy
                )
                for (rowId in chevronRows) {
                    val row = activity.findViewById<ViewGroup>(rowId)
                    val last = row.getChildAt(row.childCount - 1)
                    assertTrue("row $rowId ends with an ImageView", last is ImageView)
                    val chevron = last as ImageView
                    assertEquals("row $rowId chevron is 20dp",
                        Math.round(20f * density), chevron.layoutParams.width)
                    assertTrue("row $rowId chevron is ic_chevron_right",
                        iconMatches(chevron, R.drawable.ic_chevron_right))
                }
                // …and no literal "›" TextViews remain anywhere.
                val chevrons = allViews(activity.window.decorView)
                    .filterIsInstance<TextView>()
                    .filter { it.text?.toString() == "›" }
                assertTrue("no literal › chevrons remain", chevrons.isEmpty())
            }
        }
    }

    @Test
    fun `settings switches carry explicit sel_switch tints`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val expectedThumb = ContextCompat
                    .getColorStateList(activity, R.color.sel_switch_thumb)!!.defaultColor
                val expectedTrack = ContextCompat
                    .getColorStateList(activity, R.color.sel_switch_track)!!.defaultColor
                val switches = intArrayOf(
                    R.id.switchGreeting,
                    R.id.switchWallpaper, R.id.switchAutoEnhance
                )
                for (id in switches) {
                    val sw = activity.findViewById<SwitchCompat>(id)
                    assertNotNull("switch $id present", sw)
                    val thumb = sw.thumbTintList
                    val track = sw.trackTintList
                    assertNotNull("switch $id has a thumb tint", thumb)
                    assertNotNull("switch $id has a track tint", track)
                    assertEquals("switch $id thumb tint", expectedThumb, thumb!!.defaultColor)
                    assertEquals("switch $id track tint", expectedTrack, track!!.defaultColor)
                }
            }
        }
    }

    @Test
    fun `settings groups rows into glass section cards with hairline dividers`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                val oneDp = Math.round(1f * density)

                // Appearance: theme, font, greeting and wallpaper share one card.
                val rowFont = activity.findViewById<View>(R.id.rowFont)
                val rowGreeting = activity.findViewById<View>(R.id.rowGreeting)
                assertSame("font and greeting rows share a section container",
                    rowFont.parent, rowGreeting.parent)
                val appearance = rowFont.parent as ViewGroup
                assertEquals("appearance card holds 5 rows + 3 dividers", 8, appearance.childCount)
                assertTrue("rows live inside a MaterialCardView",
                    appearance.parent is com.google.android.material.card.MaterialCardView)

                // 1dp cyan hairline dividers between the rows.
                val dividers = (0 until appearance.childCount)
                    .map { appearance.getChildAt(it) }
                    .filter { it !is ViewGroup && it.layoutParams.height == oneDp }
                assertEquals("three hairline dividers", 3, dividers.size)

                // Viewing and About sections group their pairs the same way.
                val rowViewer = activity.findViewById<View>(R.id.rowViewer)
                val rowAuto = activity.findViewById<View>(R.id.rowAutoEnhance)
                assertSame("viewer and auto-enhance share a section container",
                    rowViewer.parent, rowAuto.parent)
                assertEquals("viewing card: 4 rows + 3 dividers", 7, (rowViewer.parent as ViewGroup).childCount)
                val rowAbout = activity.findViewById<View>(R.id.rowAbout)
                val rowPrivacy = activity.findViewById<View>(R.id.rowPrivacy)
                assertSame("about and privacy share a section container",
                    rowAbout.parent, rowPrivacy.parent)
                assertEquals("about card: 4 rows + 2 dividers", 6, (rowAbout.parent as ViewGroup).childCount)

                // Language is a card with the app language row and the OCR text-language row.
                val rowLanguage = activity.findViewById<View>(R.id.rowLanguage)
                val languageContainer = rowLanguage.parent as ViewGroup
                // v2 UI: 08_text_ocr - the owner asked for "Text language" beside the app language.
                assertEquals("language card: app language + text language", 2, languageContainer.childCount)
                assertEquals(
                    "the text-language row follows the app language row",
                    1,
                    languageContainer.indexOfChild(activity.findViewById<View>(R.id.rowOcrLanguage))
                )
                assertTrue("language row lives inside a MaterialCardView",
                    languageContainer.parent is com.google.android.material.card.MaterialCardView)

                // Four cyan section headers label the groups.
                val headerTexts = listOf(
                    R.string.settings_appearance, R.string.settings_language_section,
                    // The old About card is now "Privacy & security" (About itself lives in Me).
                    R.string.settings_viewing, R.string.settings_privacy_section
                ).map { activity.getString(it) }
                val headers = allViews(activity.window.decorView)
                    .filterIsInstance<TextView>()
                    .map { it.text?.toString() }
                    .filter { it in headerTexts }
                assertEquals("four section headers", 4, headers.size)
            }
        }
    }

    // --- 5B page grid -------------------------------------------------------

    @Test
    fun `page grid cells are glass ripple cards with chip badges and selection state`() {
        val id = seedDoc("Grid glass", pages = 2)
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val grid = activity.findViewById<
                    com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageList)
                // Host selection state on, then force a full measure/layout pass so
                // every visible cell binds through PageGridView.VH.bind.
                grid.checkedAt = { true }
                grid.refresh()
                val wSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val hSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                grid.measure(wSpec, hSpec)
                grid.layout(0, 0, 1080, 1920)

                val cell = grid.findViewHolderForAdapterPosition(0)!!.itemView
                // Code-set glass face (XML background would be dropped by the card).
                assertTrue("cell background is the glass ripple",
                    cell.background is android.graphics.drawable.RippleDrawable)
                // v2 UI: 07_reader_dock — the page number is a label under the paper, not a
                // chip floating over the page image.
                val pageNum = cell.findViewById<TextView>(R.id.pageNum)
                assertEquals("the number has no chip over the paper", null, pageNum.background)
                assertEquals(
                    "the number is mid-grey metadata",
                    activity.getColor(R.color.text_hologram_mid),
                    pageNum.currentTextColor
                )
                // Selection flows through the drawable's state_selected branch.
                assertTrue("selected cell reports isSelected", cell.isSelected)
            }
        }
    }

    // --- 5C document viewer -------------------------------------------------

    @Test
    fun `document viewer shows the category chip and glass dock`() {
        val id = seedDoc("Chip doc")
        // The doc is recategorised straight after seeding (setCategory is not suspend).
        repo.setCategory(id, com.scanwhirl.app.util.CategoryRules.STUDY)
        val intent = DocumentViewerActivity.intent(app, id)
        var label: TextView? = null
        ActivityScenario.launch<DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                label = activity.findViewById(R.id.docCategoryLabel)
                // Inset floating glass dock behind the bottom action row.
                val dock = activity.findViewById<View>(R.id.btnShare).parent as ViewGroup
                assertTrue("document viewer dock is bg_dock_glass",
                    sameDrawable(dock.background, R.drawable.bg_dock_glass))
                // v2 UI: 07_reader_dock — a 4 dp inset leaves all four equal items enough
                // width for their 12 sp labels in EN and HI.
                val margin = Math.round(4f * activity.resources.displayMetrics.density)
                val lp = dock.layoutParams as android.view.ViewGroup.MarginLayoutParams
                assertEquals("dock is inset 4dp", margin, lp.leftMargin)
                assertEquals("dock is inset 4dp", margin, lp.rightMargin)

                // v2 UI: 07_reader_dock, B2 — one row of four equal DockItem buttons in the
                // fixed order Edit · Read text · Share · Actions (the named sheet is last).
                assertEquals("dock is one row of four", 4, dock.childCount)
                assertEquals(
                    "dock order",
                    listOf(R.id.btnEdit, R.id.btnText, R.id.btnShare, R.id.btnMore),
                    (0 until dock.childCount).map { dock.getChildAt(it).id }
                )
                val expectedLabels = listOf(
                    R.string.edit, R.string.dock_read_text, R.string.share_pdf, R.string.doc_actions
                )
                for (i in 0 until dock.childCount) {
                    val btn = dock.getChildAt(i)
                        as com.google.android.material.button.MaterialButton
                    assertEquals("button $i has no fixed height (wrap_content)",
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, btn.layoutParams.height)
                    assertEquals("button $i minHeight is 56dp (DockItem default)",
                        Math.round(56f * activity.resources.displayMetrics.density),
                        btn.minimumHeight)
                    assertEquals("button $i is label ${activity.getString(expectedLabels[i])}",
                        activity.getString(expectedLabels[i]), btn.text.toString())
                    val w = (btn.layoutParams as android.widget.LinearLayout.LayoutParams).weight
                    assertEquals("button $i weight is equal", 1f, w, 0f)
                }
            }
            // reloadDocument() runs off the main thread; wait for the chip fill-in.
            val shown = pollUntil {
                label?.visibility == View.VISIBLE && label?.text?.toString() == "Study"
            }
            // v2 UI: 07_reader_dock — the compact pill is metadata: sentence case, neutral ink.
            assertTrue("category chip shows Study after reload", shown)
            assertEquals(
                "the pill label is neutral ink",
                app.getColor(R.color.text_hologram_mid),
                label!!.currentTextColor
            )
            // v2 UI: 07_reader_dock — 13 sp inside a 32 dp pill.
            val scaled = app.resources.displayMetrics.scaledDensity
            assertEquals("category chip text is 13sp", 13f * scaled, label!!.textSize, 0.5f)
        }
    }

    // --- T4.6: the header chip opens the picker -----------------------------
    // Written before the fix against existing symbols only (R.id.docCategoryChip,
    // R.id.pickerRows), so both cases run red first.

    @Test
    fun `document category chip opens the picker`() {
        val id = seedDoc("Chip doc")
        repo.setCategory(id, com.scanwhirl.app.util.CategoryRules.STUDY)
        val intent = DocumentViewerActivity.intent(app, id)
        var chip: View? = null
        ActivityScenario.launch<DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                chip = activity.findViewById(R.id.docCategoryTouch)
            }
            assertTrue(
                "chip fills in after reload",
                pollUntil { chip?.visibility == View.VISIBLE }
            )

            scenario.onActivity {
                chip!!.performClick()
                val dialog = ShadowDialog.getLatestDialog()
                assertNotNull(
                    "T4.6: the chip must open the category picker",
                    dialog
                )
                assertNotNull(
                    "the picker sheet must carry its rows (T4.2)",
                    dialog!!.findViewById<View>(R.id.pickerRows)
                )
            }
        }
    }

    @Test
    fun `picker choice recategorises the document and repaints the chip`() {
        val id = seedDoc("Chip doc")
        repo.setCategory(id, com.scanwhirl.app.util.CategoryRules.STUDY)
        val intent = DocumentViewerActivity.intent(app, id)
        var chip: View? = null
        ActivityScenario.launch<DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                chip = activity.findViewById(R.id.docCategoryTouch)
            }
            assertTrue(
                "chip fills in after reload",
                pollUntil { chip?.visibility == View.VISIBLE }
            )

            scenario.onActivity {
                chip!!.performClick()
                pickPickerRow(com.scanwhirl.app.util.CategoryRules.RECEIPTS)
            }

            assertTrue(
                "the pick must persist on the document",
                pollUntil {
                    repo.getDocument(id)?.category ==
                        com.scanwhirl.app.util.CategoryRules.RECEIPTS
                }
            )
            assertTrue(
                "the chip repaints with the new category",
                // v2 UI: 07_reader_dock — sentence case, not ALL CAPS.
                pollUntil {
                    (chip?.findViewById<TextView>(R.id.docCategoryLabel))?.text?.toString() ==
                        "Receipts"
                }
            )
        }
    }

    // --- T5.2 (§4.5): toolbar, More sheet, Select pages ---------------------

    @Test
    fun `document toolbar carries exactly one save action and no overflow`() {
        val id = seedDoc("Toolbar doc")
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val toolbar =
                    activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
                // One always-shown action: the Save-a-copy download icon (N10/T4.7).
                assertEquals("exactly one toolbar action, no overflow (⋮)",
                    1, toolbar.menu.size())
                assertEquals("the action is the Save-a-copy icon",
                    R.id.action_save_pdf, toolbar.menu.getItem(0).itemId)

                // The old viewer menu and its drawer are deleted outright, not
                // hidden: neither the actions nor the crown/tray buttons exist.
                assertNull("the dock Delete button is gone",
                    activity.findViewById(R.id.btnDelete))
                for (view in listOf(
                    "btnProTools", "proToolsPanel", "btnProtect", "btnUnlockPdf",
                    "btnOcr", "btnLongImage", "btnVaultDoc"
                )) {
                    assertEquals("view $view must be gone from the layout", 0,
                        activity.resources.getIdentifier(view, "id", activity.packageName))
                }
                for (name in listOf(
                    "action_rename", "action_categorize", "action_save_device",
                    "action_ocr_text", "action_protect_pdf", "action_long_image",
                    "action_vault_doc", "action_save_page"
                )) {
                    assertEquals("$name must be gone with menu_viewer.xml", 0,
                        activity.resources.getIdentifier(name, "id", activity.packageName))
                }
            }
        }
    }

    @Test
    fun `document more sheet is the single home for secondary actions`() {
        val id = seedDoc("More sheet", pages = 1)
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<DocumentViewerActivity>(intent).use { scenario ->
            assertTrue("document must load before the sheet opens",
                pollUntil { pageCountReady(scenario) })

            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnMore).performClick()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("the More sheet must open", sheet)

                // B2 (UI critique #3): the sheet is bound to this document and starts with the
                // document-linked tools — Compress, Sign, Read text, Share — before file
                // management, and puts the destructive row last.
                for (row in listOf(
                    R.id.rowMoreRename, R.id.rowMoreMove, R.id.rowMoreDuplicate,
                    R.id.rowMoreCompress, R.id.rowMoreLock,
                    R.id.rowMoreVault, R.id.rowMoreDelete
                )) assertNotNull("row $row must exist", sheet!!.findViewById<View>(row))
                for (hdr in listOf(R.id.hdrOrganize, R.id.hdrTools, R.id.hdrDanger)) {
                    assertNotNull("header $hdr must exist", sheet!!.findViewById<View>(hdr))
                }
                val deco = sheet!!.window!!.decorView
                for (key in listOf("sign", "read_text", "share")) {
                    assertNotNull(
                        "the document-linked '$key' row must exist",
                        deco.findViewWithTag<View>("docActions:$key")
                    )
                }
                val rows = requireNotNull(deco.findViewWithTag<android.view.ViewGroup>("docActions:rows"))
                val order = (0 until rows.childCount)
                    .mapNotNull { rows.getChildAt(it).tag as? String }
                    .filter { it.startsWith("docActions:") }
                    .map { it.removePrefix("docActions:") }
                    .filter { it != "header" && !it.startsWith("hdr:") }
                assertEquals(
                    "B2 order: tools first, file management next, destructive last",
                    listOf(
                        "compress", "sign", "read_text", "share",
                        "rename", "move", "duplicate", "lock", "vault", "trash"
                    ),
                    order
                )

                // One home per action: no save row — the toolbar's download icon is the only
                // export left (§4.5).
                val labels = allViews(sheet.window!!.decorView)
                    .filterIsInstance<TextView>()
                    .map { it.text?.toString() }
                assertFalse("no save row in the sheet (§4.5)",
                    labels.contains(activity.getString(R.string.save_pdf_device)))
                assertEquals("no OCR button in the sheet (D-E)", 0,
                    activity.resources.getIdentifier("btnOcr", "id", activity.packageName))

                // Pro (test-build default): the locked rows carry no pill.
                assertFalse("Pro sees no PRO pill",
                    sheet.findViewById<View>(R.id.pillLock).isVisible)
                assertFalse("Pro sees no PRO pill",
                    sheet.findViewById<View>(R.id.pillVault).isVisible)
                // Labels follow the document state (unlocked, unvaulted here).
                assertEquals(activity.getString(R.string.menu_protect_pdf),
                    sheet.findViewById<TextView>(R.id.labelLock).text.toString())
                assertEquals(activity.getString(R.string.btn_vault_hide),
                    sheet.findViewById<TextView>(R.id.labelVault).text.toString())
                sheet.dismiss()
            }
        }
    }

    @Test
    fun `free users see no pro pills and Protect opens the free sheet`() {
        com.scanwhirl.app.util.ProGate.setTestOverride(app, false)
        val id = seedDoc("More sheet free", pages = 1)
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<DocumentViewerActivity>(intent).use { scenario ->
            assertTrue("document must load before the sheet opens",
                pollUntil { pageCountReady(scenario) })

            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnMore).performClick()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("the More sheet must open", sheet)
                // Protect/Unlock PDF and the Vault row are Free (WP-22-5): no row sells anything.
                for (pill in listOf(R.id.pillLock, R.id.pillVault)) {
                    assertFalse("Free sees no PRO pill on $pill",
                        sheet!!.findViewById<View>(pill).isVisible)
                }
                // WP-22: the row is the real, Free Protect tool, not the old Pro dossier.
                sheet!!.findViewById<View>(R.id.rowMoreLock).performClick()
                val protect = ShadowDialog.getLatestDialog()
                assertNotNull("the Protect sheet must open", protect)
                assertNotNull("…and it asks for the real password",
                    protect!!.findViewById<android.widget.EditText>(R.id.protectPassword))
                assertNull("…never the old Pro dossier panel",
                    protect.findViewById<View>(R.id.proDossierStatus))
                protect.dismiss()
            }
        }
    }

    @Test
    fun `long press selects a page and shows the selection bars`() {
        // v2 UI: Organize pages grid (owner revision) - no toggle row, no checkbox outside selection.
        val id = seedDoc("Select pages", pages = 3)
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<DocumentViewerActivity>(intent).use { scenario ->
            assertTrue("document must load first",
                pollUntil { pageCountReady(scenario) })

            scenario.onActivity { activity ->
                val grid = activity.findViewById<
                    com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageList)
                val top = activity.findViewById<View>(R.id.selectionTopBar)
                val actions = activity.findViewById<View>(R.id.selectionActionBar)
                val count = activity.findViewById<TextView>(R.id.selCount)

                // Normal state: clean tiles, no checks, no bars.
                assertFalse("checks hidden until a long-press", grid.checkboxesVisible)
                assertEquals(View.GONE, top.visibility)
                assertEquals(View.GONE, actions.visibility)
                assertFalse("cell checks hidden", cellCheckboxShown(grid))

                // Long-press drag start: selection mode with that page selected (no movement).
                grid.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                )
                grid.layout(0, 0, 1080, 1920)
                assertTrue(grid.startDragAt(0))
                grid.moveItem(0, 0)
                assertTrue("long-press turns the checks on", grid.checkboxesVisible)
                assertEquals(View.VISIBLE, top.visibility)
                assertEquals(View.VISIBLE, actions.visibility)
                assertEquals(
                    activity.resources.getQuantityString(R.plurals.selected_count, 1, 1),
                    count.text.toString()
                )
                assertTrue("cell check shown in selection mode", cellCheckboxShown(grid))

                // Select all, then close.
                activity.findViewById<View>(R.id.btnSelAll).performClick()
                assertEquals(
                    activity.resources.getQuantityString(R.plurals.selected_count, 3, 3),
                    count.text.toString()
                )
                activity.findViewById<View>(R.id.btnSelClose).performClick()
                assertEquals(View.GONE, top.visibility)
                assertEquals(View.GONE, actions.visibility)
                assertFalse(grid.checkboxesVisible)
                assertFalse("cell checks hidden again", cellCheckboxShown(grid))
            }
        }
    }

    /** Forces a measure/layout pass and reports cell 0's checkbox visibility. */
    private fun cellCheckboxShown(
        grid: com.scanwhirl.app.ui.pagegrid.PageGridView
    ): Boolean {
        val wSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        grid.measure(wSpec, hSpec)
        grid.layout(0, 0, 1080, 1920)
        val vh = grid.findViewHolderForAdapterPosition(0) ?: return false
        return vh.itemView.findViewById<View>(R.id.checkSelect).isVisible
    }

    /** True once reloadDocument() has pushed the page count into the header. */
    private fun pageCountReady(
        scenario: ActivityScenario<DocumentViewerActivity>
    ): Boolean {
        var ready = false
        scenario.onActivity { a ->
            ready = a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
        }
        return ready
    }

    // --- 5B/5D docks --------------------------------------------------------

    @Test
    fun `review and page viewer use inset glass docks with dock-styled buttons`() {
        // ScanReview: the Save PDF dock.
        val staging = File(app.cacheDir, "phase5_dock_${System.nanoTime()}")
            .apply { mkdirs() }
        File(staging, "page_001.jpg").writeBytes(jpegBytes())
        val reviewIntent = ScanReviewActivity.intent(app, staging.path, null, "Dock test")
        ActivityScenario.launch<ScanReviewActivity>(reviewIntent).use { scenario ->
            scenario.onActivity { activity ->
                val savePdf = activity.findViewById<View>(R.id.btnSavePdf)
                val dock = savePdf.parent as ViewGroup
                assertTrue("scan review dock is bg_dock_glass",
                    sameDrawable(dock.background, R.drawable.bg_dock_glass))
                val margin = Math.round(8f * activity.resources.displayMetrics.density)
                val lp = dock.layoutParams as android.view.ViewGroup.MarginLayoutParams
                assertEquals("dock is inset 8dp", margin, lp.leftMargin)
                assertEquals("dock is inset 8dp", margin, lp.rightMargin)
            }
        }

        // PageViewer: T5.1 — one row of four DockItem buttons (§4.6/§5.4).
        val id = seedDoc("Dock doc", pages = 2)
        val viewerIntent = PageViewerActivity.intent(app, id, 0)
        ActivityScenario.launch<PageViewerActivity>(viewerIntent).use { scenario ->
            scenario.onActivity { activity ->
                val rotate = activity.findViewById<
                    com.google.android.material.button.MaterialButton>(R.id.btnRotateRight)
                val dock = rotate.parent as ViewGroup
                assertTrue("page viewer dock is bg_dock_glass",
                    sameDrawable(dock.background, R.drawable.bg_dock_glass))
                val margin = Math.round(8f * activity.resources.displayMetrics.density)
                val lp = dock.layoutParams as android.view.ViewGroup.MarginLayoutParams
                assertEquals("dock is inset 8dp", margin, lp.leftMargin)
                assertEquals("dock is inset 8dp", margin, lp.rightMargin)
                // Single row: the dock itself holds all four buttons (not nested rows).
                assertEquals("dock is one row of four", 4, dock.childCount)
                for (i in 0 until dock.childCount) {
                    val btn = dock.getChildAt(i)
                        as com.google.android.material.button.MaterialButton
                    assertEquals("button $i has no fixed height (wrap_content)",
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, btn.layoutParams.height)
                    assertEquals("button $i minHeight is 64dp (§4.6, scale 1.3 floor)",
                        Math.round(64f * activity.resources.displayMetrics.density),
                        btn.minimumHeight)
                }
                // Widget.ScanWhirl.Button.DockItem sets 12sp labels.
                val expected = 12f * activity.resources.displayMetrics.scaledDensity
                assertEquals("dock button label is 12sp", expected, rotate.textSize, 0.5f)
            }
        }
    }

    // --- renders ------------------------------------------------------------

    @Test
    fun `renders settings and review screens to png`() {
        val settingsPng = File("/tmp/opencode/ui_settings.png")
        val reviewPng = File("/tmp/opencode/ui_review.png")
        settingsPng.delete()
        reviewPng.delete()

        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { renderActivityPng(it, settingsPng.path) }
        }

        val staging = File(app.cacheDir, "phase5_render_${System.nanoTime()}")
            .apply { mkdirs() }
        File(staging, "page_001.jpg").writeBytes(jpegBytes())
        val intent = ScanReviewActivity.intent(app, staging.path, null, "Render test")
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            scenario.onActivity { renderActivityPng(it, reviewPng.path) }
        }

        assertTrue("ui_settings.png rendered", settingsPng.length() > 0)
        assertTrue("ui_review.png rendered", reviewPng.length() > 0)
    }
}
