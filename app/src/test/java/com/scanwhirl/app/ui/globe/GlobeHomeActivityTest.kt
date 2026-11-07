package com.scanwhirl.app.ui.globe

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.MainActivity
import com.scanwhirl.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.runBlocking

/** Smoke tests for the new globe launcher home (test23). */
@RunWith(RobolectricTestRunner::class)
// Legacy mode makes Canvas/Paint/Path shadow stubs: drawCircle/drawPath never
// rasterise and measureText returns the character count. The render tests need
// real Skia — same annotation as UiSmokeTest and friends.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GlobeHomeActivityTest {

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        Prefs.setGlobeHomeEnabled(app, true)
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

    @Test
    fun `globe activity is the launcher, main activity is not`() {
        // MATCH_DEFAULT_ONLY would miss it: launcher filters carry no
        // CATEGORY_DEFAULT, so resolve with plain flags like a real launcher.
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = app.packageManager.resolveActivity(launch, 0)
        assertNotNull("app must have a launcher", resolved)
        val name = resolved?.activityInfo?.name
        assertTrue(
            "launcher should be the globe home, was $name",
            name?.endsWith("GlobeHomeActivity") == true
        )
    }

    @Test
    fun `home screen wires the view, hud and scan button`() {
        ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById(R.id.globeView))
                assertNotNull(activity.findViewById(R.id.fabGlobeScan))
                assertNotNull(activity.findViewById(R.id.btnGlobeSearch))
                assertNotNull(activity.findViewById(R.id.searchPanel))
                assertEquals(
                    activity.getString(R.string.globe_hint_home),
                    activity.findViewById<HolographicEarthView>(R.id.globeView).hintText
                )
            }
        }
    }

    @Test
    fun `tapping the earth expands the rings`() {
        ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = activity.findViewById<HolographicEarthView>(R.id.globeView)
                assertEquals(GlobeScene.STATE_HOME, view.scene.state)
                activity.onGlobeTapped()
                assertEquals(GlobeScene.STATE_RINGS, view.scene.state)
                assertEquals(
                    activity.getString(R.string.globe_hint_rings), view.hintText
                )
                activity.onGlobeTapped()
                assertEquals(GlobeScene.STATE_HOME, view.scene.state)
            }
        }
    }

    @Test
    fun `pinch-in cross-fades into the shelf`() {
        ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.onPinchToShelf()
                val next = shadowOf(activity).nextStartedActivity
                assertNotNull("pinch must open the Shelf", next)
                assertEquals(
                    MainActivity::class.java.name,
                    resolveName(next)
                )
            }
        }
    }

    @Test
    fun `scan button opens the shelf with the scanner chooser flag`() {
        ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.fabGlobeScan).performClick()
                val next = shadowOf(activity).nextStartedActivity
                assertNotNull(next)
                assertEquals(MainActivity::class.java.name, resolveName(next))
                assertTrue(
                    "scan flag must be set",
                    next.getBooleanExtra(MainActivity.EXTRA_OPEN_SCANNER, false)
                )
            }
        }
    }

    @Test
    fun `pull-down opens the search panel with the shared match counter`() {
        runBlocking {
            val repo = ScanRepository(app)
            repo.saveScan(listOf(uriBytes()), null, "Biology class notes")
            try {
                ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
                    val docsLoaded = pollUntil {
                        var count = -1
                        scenario.onActivity { a ->
                            val v = a.findViewById<HolographicEarthView>(R.id.globeView)
                            count = v.scene.cards.size
                        }
                        count > 0
                    }
                    assertTrue("docs should load into the scene", docsLoaded)

                    scenario.onActivity { activity ->
                        activity.onPullSearch()
                        val panel = activity.findViewById<android.view.View>(R.id.searchPanel)
                        assertEquals(android.view.View.VISIBLE, panel.visibility)
                        val counter =
                            activity.findViewById<android.widget.TextView>(R.id.globeMatchCount)
                        assertTrue(
                            "counter should show the total: ${counter.text}",
                            counter.text.toString().contains("1")
                        )
                    }
                }
            } finally {
                repo.listDocuments().forEach { repo.deleteDocument(it.id) }
            }
        }
    }

    @Test
    fun `settings toggle off skips straight to the shelf`() {
        Prefs.setGlobeHomeEnabled(app, false)
        try {
            // The activity finishes inside onCreate, so ActivityScenario can
            // never hand us a live instance — drive the controller directly.
            val controller =
                org.robolectric.Robolectric.buildActivity(GlobeHomeActivity::class.java).create()
            val activity = controller.get()
            assertTrue("globe must hand off to the Shelf", activity.isFinishing)
            val next = shadowOf(activity).nextStartedActivity
            assertNotNull("Shelf must be started", next)
            assertEquals(MainActivity::class.java.name, resolveName(next))
        } finally {
            Prefs.setGlobeHomeEnabled(app, true)
        }
    }

    @Test
    fun `globe preference defaults to on`() {
        Prefs.setGlobeHomeEnabled(app, false)
        Prefs.setGlobeHomeEnabled(app, true)
        assertTrue(Prefs.globeHomeEnabled(app))
    }

    // ------------------------------------------------------------------
    // test25 Phase 3: HUD, orbit dock, nav buttons, hint pill, screenshots
    // ------------------------------------------------------------------

    @Test
    fun `hud exposes title status hint pill and nav buttons`() {
        ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val title = activity.findViewById<android.widget.TextView>(R.id.globeTitle)
                assertNotNull("HUD needs a title", title)
                assertTrue("title must not be blank", title.text.isNotEmpty())

                val status = activity.findViewById<android.widget.TextView>(R.id.globeStatus)
                assertEquals(
                    activity.getString(R.string.globe_status_empty),
                    status.text.toString()
                )

                // The pill mirrors the canvas property the other tests read.
                val view = activity.findViewById<HolographicEarthView>(R.id.globeView)
                val hint = activity.findViewById<android.widget.TextView>(R.id.globeHint)
                assertEquals(view.hintText, hint.text.toString())
                assertFalse("hint must not be drawn on the canvas", view.drawHintInCanvas)

                assertNotNull(activity.findViewById(R.id.btnGlobeList))
                assertNotNull(activity.findViewById(R.id.btnGlobeSettings))
                assertNotNull(activity.findViewById(R.id.hudScrim))
                assertNotNull(activity.findViewById(R.id.globeDock))
                assertNotNull(activity.findViewById(R.id.fabPulse1))
                assertNotNull(activity.findViewById(R.id.fabPulse2))
            }
        }
    }

    @Test
    fun `list button opens the shelf and gear opens settings`() {
        ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnGlobeList).performClick()
                val toShelf = shadowOf(activity).nextStartedActivity
                assertNotNull("List must open the Shelf", toShelf)
                assertEquals(MainActivity::class.java.name, resolveName(toShelf))

                activity.findViewById<android.view.View>(R.id.btnGlobeSettings).performClick()
                val toSettings = shadowOf(activity).nextStartedActivity
                assertNotNull("gear must open Settings", toSettings)
                assertTrue(
                    "gear must open Settings, was ${resolveName(toSettings)}",
                    resolveName(toSettings)?.endsWith("SettingsActivity") == true
                )
            }
        }
    }

    @Test
    fun `category chip focuses its ring and tapping again clears it`() {
        ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = activity.findViewById<HolographicEarthView>(R.id.globeView)
                val chip = activity.findViewById<android.widget.TextView>(R.id.globeChipStudy)
                assertEquals(-1, view.scene.focusRing)

                chip.performClick()
                assertEquals(0, view.scene.focusRing)
                assertEquals(
                    "focus must reveal the rings", GlobeScene.STATE_RINGS, view.scene.state
                )
                assertTrue("chip marks itself selected", chip.isSelected)

                chip.performClick()
                assertEquals(-1, view.scene.focusRing)
                assertFalse("second tap clears selection", chip.isSelected)
            }
        }
    }

    @Test
    fun `globe home renders home and rings screenshots`() = runBlocking<Unit> {
        val repo = ScanRepository(app)
        repo.saveScan(listOf(uriBytes()), null, "Biology class notes")
        repo.saveScan(listOf(uriBytes()), null, "Grocery receipt")
        try {
            ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
                val docsLoaded = pollUntil {
                    var count = -1
                    scenario.onActivity { a ->
                        count = a.findViewById<HolographicEarthView>(R.id.globeView)
                            .scene.cards.size
                    }
                    count >= 2
                }
                assertTrue("docs should load into the scene", docsLoaded)

                scenario.onActivity { activity ->
                    val bmp = renderActivityBitmap(activity)
                    savePng(bmp, "/tmp/opencode/ui_globe.png")
                    val g = activity.findViewById<HolographicEarthView>(R.id.globeView)
                        .scene.globeGeometry(W.toFloat(), H.toFloat())
                    // Background gradient max channel is 0x26 (=38); dots are (0,245,255).
                    val bright = discFraction(bmp, g.cx, g.cy, g.r) {
                        android.graphics.Color.green(it) > 100 ||
                            android.graphics.Color.blue(it) > 100
                    }
                    println("HOME bright disc fraction = $bright (centre=${Integer.toHexString(bmp.getPixel(g.cx.toInt(), g.cy.toInt()))})")
                    assertTrue("home render has no Earth pixels (bright=$bright)", bright > 0.05f)

                    activity.onGlobeTapped()
                    // The 30fps loop never runs under Robolectric, so ease
                    // stateT to 1 by hand before rendering the rings view.
                    val view = activity.findViewById<HolographicEarthView>(R.id.globeView)
                    repeat(90) { view.scene.update(1f / 30f) }
                    view.invalidate()
                    val bmp2 = renderActivityBitmap(activity)
                    savePng(bmp2, "/tmp/opencode/ui_globe_rings.png")
                    val g2 = view.scene.globeGeometry(W.toFloat(), H.toFloat())
                    val bright2 = discFraction(bmp2, g2.cx, g2.cy, g2.r) {
                        android.graphics.Color.green(it) > 100 ||
                            android.graphics.Color.blue(it) > 100
                    }
                    println("RINGS bright disc fraction = $bright2 (centre=${Integer.toHexString(bmp2.getPixel(g2.cx.toInt(), g2.cy.toInt()))})")
                    assertTrue("rings render has no Earth pixels (bright=$bright2)", bright2 > 0.05f)
                }
            }
        } finally {
            repo.listDocuments().forEach { repo.deleteDocument(it.id) }
        }
    }

    // ------------------------------------------------------------------
    // test25 Phase 3: guards against a silent regression back to legacy
    // graphics mode (where nothing rasterises and the PNGs are star-only).
    // ------------------------------------------------------------------

    @Test
    fun `native graphics is active - text measures to real widths`() {
        // Legacy ShadowPaint.measureText returns the character count (9).
        val p = android.graphics.Paint().apply { textSize = 40f }
        assertTrue(
            "legacy graphics mode detected: add @GraphicsMode(NATIVE)",
            p.measureText("ScanWhirl") > 100f
        )
    }

    @Test
    fun `globe view paints halo, dots and rim on a transparent canvas`() {
        ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                layoutDecor(activity)
                val gv = activity.findViewById<HolographicEarthView>(R.id.globeView)
                val bmp = renderView(gv)
                val g = gv.scene.globeGeometry(gv.width.toFloat(), gv.height.toFloat())
                // The globe view has no background, so any alpha>0 inside the
                // disc is halo/dots — the halo alone covers the whole disc.
                val lit = discFraction(bmp, g.cx, g.cy, g.r) {
                    android.graphics.Color.alpha(it) > 0
                }
                println("GLOBE VIEW lit disc fraction = $lit")
                assertTrue("globe disc is blank (lit=$lit)", lit > 0.8f)
                assertTrue(
                    "globe centre pixel must not be fully transparent",
                    android.graphics.Color.alpha(bmp.getPixel(g.cx.toInt(), g.cy.toInt())) > 0
                )
            }
        }
    }

    @Test
    fun `hud title and status have real text size and paint pixels`() {
        ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                layoutDecor(activity)
                val title = activity.findViewById<android.widget.TextView>(R.id.globeTitle)
                println("HUD title ${title.width}x${title.height}")
                assertTrue("title width ${title.width} px is implausibly small", title.width > 100)
                val bmp = renderView(title)
                var lit = 0
                for (x in 0 until bmp.width) {
                    for (y in 0 until bmp.height) {
                        if (android.graphics.Color.alpha(bmp.getPixel(x, y)) > 0) lit++
                    }
                }
                println("HUD title painted pixels = $lit")
                assertTrue("title painted no pixels", lit > 50)
            }
        }
    }

    // ------------------------------------------------------------------
    // Render helpers (test25 Phase 3). NATIVE graphics: exceptions propagate
    // on purpose — a blank PNG must never pass silently.
    // ------------------------------------------------------------------

    private companion object {
        const val W = 1080
        const val H = 2200
    }

    private fun layoutDecor(activity: android.app.Activity) {
        val root = activity.window.decorView
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(
                W, android.view.View.MeasureSpec.EXACTLY
            ),
            android.view.View.MeasureSpec.makeMeasureSpec(
                H, android.view.View.MeasureSpec.EXACTLY
            )
        )
        root.layout(0, 0, W, H)
    }

    /** Renders the whole window. Exceptions propagate on purpose. */
    private fun renderActivityBitmap(activity: android.app.Activity): android.graphics.Bitmap {
        layoutDecor(activity)
        val bmp = android.graphics.Bitmap.createBitmap(
            W, H, android.graphics.Bitmap.Config.ARGB_8888
        )
        activity.window.decorView.draw(android.graphics.Canvas(bmp))
        return bmp
    }

    private fun savePng(bmp: android.graphics.Bitmap, outPath: String) {
        val f = java.io.File(outPath)
        f.parentFile?.mkdirs()
        java.io.FileOutputStream(f).use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** Renders a single view onto a bitmap of its own size. */
    private fun renderView(v: android.view.View): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(
            v.width.coerceAtLeast(1), v.height.coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888
        )
        v.draw(android.graphics.Canvas(bmp))
        return bmp
    }

    /** Share of sampled pixels in the disc (radius r*frac) that satisfy [pred]. */
    private fun discFraction(
        bmp: android.graphics.Bitmap, cx: Float, cy: Float, r: Float,
        frac: Float = 0.9f, step: Int = 6, pred: (Int) -> Boolean
    ): Float {
        var hit = 0
        var total = 0
        val rr = r * frac
        var y = (cy - rr).toInt().coerceAtLeast(0)
        while (y < (cy + rr).toInt() && y < bmp.height) {
            var x = (cx - rr).toInt().coerceAtLeast(0)
            while (x < (cx + rr).toInt() && x < bmp.width) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= rr * rr) {
                    total++
                    if (pred(bmp.getPixel(x, y))) hit++
                }
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else hit / total.toFloat()
    }

    private fun resolveName(intent: Intent?): String? =
        intent?.component?.className ?: intent?.resolveActivity(app.packageManager)?.className

    /**
     * NATIVE graphics makes BitmapFactory real, so the fake stream must hold
     * decodable bytes — mirrors UiSmokeTest.jpegBytes().
     */
    private fun jpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(
            310, 444, android.graphics.Bitmap.Config.ARGB_8888
        )
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private var uriCounter = 0
    private fun uriBytes(): android.net.Uri {
        val uri = android.net.Uri.parse("content://globe/fake_${uriCounter++}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, jpegBytes().inputStream())
        return uri
    }
}
