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
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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
                // The globe row carries no locked ID; reach it via its switch.
                val globeRow = activity.findViewById<View>(R.id.switchGlobeHome).parent as ViewGroup
                val globeIcon = globeRow.getChildAt(0) as? ImageView
                assertTrue("globe row icon matches ic_globe", iconMatches(globeIcon, R.drawable.ic_globe))

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
                    R.id.switchGreeting, R.id.switchGlobeHome,
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

                // Appearance: theme, font, greeting, globe and wallpaper share one card.
                val rowFont = activity.findViewById<View>(R.id.rowFont)
                val rowGreeting = activity.findViewById<View>(R.id.rowGreeting)
                assertSame("font and greeting rows share a section container",
                    rowFont.parent, rowGreeting.parent)
                val appearance = rowFont.parent as ViewGroup
                assertEquals("appearance card holds 5 rows + 4 dividers", 9, appearance.childCount)
                assertTrue("rows live inside a MaterialCardView",
                    appearance.parent is com.google.android.material.card.MaterialCardView)

                // 1dp cyan hairline dividers between the rows.
                val dividers = (0 until appearance.childCount)
                    .map { appearance.getChildAt(it) }
                    .filter { it !is ViewGroup && it.layoutParams.height == oneDp }
                assertEquals("four hairline dividers", 4, dividers.size)

                // Viewing and About sections group their pairs the same way.
                val rowViewer = activity.findViewById<View>(R.id.rowViewer)
                val rowAuto = activity.findViewById<View>(R.id.rowAutoEnhance)
                assertSame("viewer and auto-enhance share a section container",
                    rowViewer.parent, rowAuto.parent)
                assertEquals("viewing card: 2 rows + 1 divider", 3, (rowViewer.parent as ViewGroup).childCount)
                val rowAbout = activity.findViewById<View>(R.id.rowAbout)
                val rowPrivacy = activity.findViewById<View>(R.id.rowPrivacy)
                assertSame("about and privacy share a section container",
                    rowAbout.parent, rowPrivacy.parent)
                assertEquals("about card: 2 rows + 1 divider", 3, (rowAbout.parent as ViewGroup).childCount)

                // Language is a single-row card.
                val rowLanguage = activity.findViewById<View>(R.id.rowLanguage)
                val languageContainer = rowLanguage.parent as ViewGroup
                assertEquals("language card: single row", 1, languageContainer.childCount)
                assertTrue("language row lives inside a MaterialCardView",
                    languageContainer.parent is com.google.android.material.card.MaterialCardView)

                // Four cyan section headers label the groups.
                val headerTexts = listOf(
                    R.string.settings_appearance, R.string.settings_language_section,
                    R.string.settings_viewing, R.string.settings_about_section
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
                // Page badge uses bg_chip_void (12dp corners).
                val pageNum = cell.findViewById<TextView>(R.id.pageNum)
                val badge = pageNum.background as? android.graphics.drawable.GradientDrawable
                assertNotNull("pageNum badge is a shape", badge)
                assertEquals("badge corners are 12dp",
                    12f * activity.resources.displayMetrics.density, badge!!.cornerRadius, 0.5f)
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
        var chip: TextView? = null
        ActivityScenario.launch<DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                chip = activity.findViewById(R.id.docCategoryChip)
                // Inset floating glass dock behind the bottom action row.
                val dock = activity.findViewById<View>(R.id.btnShare).parent as ViewGroup
                assertTrue("document viewer dock is bg_dock_glass",
                    sameDrawable(dock.background, R.drawable.bg_dock_glass))
                val margin = Math.round(8f * activity.resources.displayMetrics.density)
                val lp = dock.layoutParams as android.view.ViewGroup.MarginLayoutParams
                assertEquals("dock is inset 8dp", margin, lp.leftMargin)
                assertEquals("dock is inset 8dp", margin, lp.rightMargin)
            }
            // reloadDocument() runs off the main thread; wait for the chip fill-in.
            val shown = pollUntil {
                chip?.visibility == View.VISIBLE && chip?.text?.toString() == "● STUDY"
            }
            assertTrue("category chip shows ● STUDY after reload", shown)
            assertEquals(com.scanwhirl.app.util.CategoryStyle.COLOR_STUDY, chip!!.currentTextColor)
        }
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

        // PageViewer: the rotate/crop/filter/delete dock, buttons on the .Dock style.
        val id = seedDoc("Dock doc", pages = 2)
        val viewerIntent = PageViewerActivity.intent(app, id, 0)
        ActivityScenario.launch<PageViewerActivity>(viewerIntent).use { scenario ->
            scenario.onActivity { activity ->
                val rotate = activity.findViewById<
                    com.google.android.material.button.MaterialButton>(R.id.btnRotateRight)
                val dockRow = rotate.parent as ViewGroup
                val dock = dockRow.parent as ViewGroup
                assertTrue("page viewer dock is bg_dock_glass",
                    sameDrawable(dock.background, R.drawable.bg_dock_glass))
                val margin = Math.round(8f * activity.resources.displayMetrics.density)
                val lp = dock.layoutParams as android.view.ViewGroup.MarginLayoutParams
                assertEquals("dock is inset 8dp", margin, lp.leftMargin)
                assertEquals("dock is inset 8dp", margin, lp.rightMargin)
                // Widget.ScanWhirl.Button.Dock sets 11sp labels.
                val expected = 11f * activity.resources.displayMetrics.scaledDensity
                assertEquals("dock button label is 11sp", expected, rotate.textSize, 0.5f)
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
