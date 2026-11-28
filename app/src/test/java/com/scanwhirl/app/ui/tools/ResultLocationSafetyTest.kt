package com.scanwhirl.app.ui.tools

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.result.ResultSpec
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.FakeMediaStore
import com.scanwhirl.app.util.Prefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class ResultLocationSafetyTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun imageIntent(): Intent = ToolResultActivity.intent(app,
        ResultSpec("form_ready", files = listOf(RsFixtures.jpeg("location", 413, 531, app)), mime = "image/jpeg"))

    @Test(timeout = 30_000)
    fun `location primary and New badge remain legible after compositing in both themes`() {
        for (night in listOf(android.content.res.Configuration.UI_MODE_NIGHT_NO,
            android.content.res.Configuration.UI_MODE_NIGHT_YES)) {
            val config = android.content.res.Configuration(app.resources.configuration).apply {
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
            val context = app.createConfigurationContext(config)
            val base = SwStyle.color(context, R.color.void_abyss)
            for ((text, surface) in listOf(
                R.color.text_hologram_hi to R.color.void_abyss,
                R.color.sw_disc_ink to R.color.cyan_primary,
                R.color.cyan_primary to R.color.rv_surface_2
            )) {
                val background = androidx.core.graphics.ColorUtils.compositeColors(SwStyle.color(context, surface), base)
                val foreground = androidx.core.graphics.ColorUtils.compositeColors(SwStyle.color(context, text), background)
                assertTrue("composited text contrast in mode $night",
                    androidx.core.graphics.ColorUtils.calculateContrast(foreground, background) >= 4.5)
            }
        }
    }

    private fun media(): FakeMediaStore = FakeMediaStore().also {
        it.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", it)
        DownloadSaver.resetForTests()
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    @Test(timeout = 30_000)
    fun `failed image export keeps input and unsaved location then Retry verifies one copy`() {
        val media = media().apply { failOpenWrite = true }
        val intent = imageIntent()
        val input = ResultSpec.fromIntent(intent).files.single()
        val original = input.readBytes()
        val controller = Robolectric.buildActivity(ToolResultActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val primary = activity.window.decorView.findViewWithTag<TextView>("result-primary")
            val location = activity.findViewById<TextView>(R.id.toolResultName)
            val before = location.text.toString()
            primary.performClick()
            assertTrue("failure is visible", await {
                activity.findViewById<View>(R.id.toolResultError).visibility == View.VISIBLE && primary.isEnabled
            })
            assertEquals(before, location.text.toString())
            assertArrayEquals(original, input.readBytes())
            assertTrue(media.scanwhirlPdfs().isEmpty())
            media.failOpenWrite = false
            activity.window.decorView.findViewWithTag<View>("result-retry").performClick()
            assertTrue("verified save updates the persistent location", await {
                location.text.contains(app.getString(R.string.rs_where_documents)) && !primary.isEnabled
            })
            assertEquals(1, media.scanwhirlPdfs().size)
            assertArrayEquals(original, media.scanwhirlPdfs().single().bytes())
        } finally {
            controller.destroy()
            DownloadSaver.resetForTests()
            media.close()
        }
    }

    @Test(timeout = 30_000)
    fun `failed automatic Documents copy never hides library success or changes the original`() {
        val media = media().apply { failOpenWrite = true }
        val repo = ScanRepository(app)
        val source = DocFixtures.saved(repo, app, 1, "Untouched source")
        val out = DocFixtures.saved(repo, app, 1, "New PDF")
        val original = requireNotNull(repo.getDocument(source)).pdfFile.readBytes()
        Prefs.setAutoCopyToDocuments(app, true)
        val controller = Robolectric.buildActivity(ToolResultActivity::class.java,
            ToolResultActivity.intent(app, ResultSpec("split", docIds = listOf(out), receiptSourceId = source))).setup()
        try {
            val activity = controller.get()
            assertTrue(await { activity.findViewById<View>(R.id.toolResultError).visibility == View.VISIBLE })
            val where = activity.findViewById<TextView>(R.id.toolResultName).text.toString()
            assertTrue(where.contains(app.getString(R.string.rs_library_as, "New PDF")))
            assertFalse(where.contains(app.getString(R.string.rs_where_documents)))
            assertNotNull(repo.getDocument(out))
            assertArrayEquals(original, requireNotNull(repo.getDocument(source)).pdfFile.readBytes())
            assertTrue(media.scanwhirlPdfs().isEmpty())
        } finally {
            controller.destroy()
            Prefs.setAutoCopyToDocuments(app, false)
            DownloadSaver.resetForTests()
            media.close()
        }
    }

    @Test(timeout = 30_000)
    fun `image result never says saved before the write`() {
        val controller = Robolectric.buildActivity(ToolResultActivity::class.java, imageIntent()).setup()
        try {
            idle()
            val activity = controller.get()
            assertTrue(activity.findViewById<TextView>(R.id.toolResultName).text.toString()
                .startsWith(app.getString(R.string.rs_not_saved_yet)))
            val primary = activity.window.decorView.findViewWithTag<TextView>("result-primary")
            assertEquals(app.getString(R.string.rs_save_to_phone), primary.text.toString())
            assertTrue(primary.isEnabled)
            assertFalse(activity.findViewById<TextView>(R.id.toolResultName).isClickable)
        } finally { controller.destroy() }
    }

    @Test(timeout = 30_000)
    fun `PDF result has exactly one filled action`() {
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, 1, "Fictional result")
        val controller = Robolectric.buildActivity(ToolResultActivity::class.java,
            ToolResultActivity.intent(app, ResultSpec("split", docIds = listOf(id)))).setup()
        try {
            idle()
            val activity = controller.get()
            val filled = mutableListOf<View>()
            fun walk(v: View) {
                if (v.visibility != View.VISIBLE) return
                if (v.isClickable && (v.background as? GradientDrawable)?.color?.defaultColor ==
                    SwStyle.color(activity, R.color.cyan_primary)) filled += v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(activity.window.decorView)
            assertEquals(1, filled.size)
            assertEquals(R.id.toolResultShare, filled.single().id)
            assertTrue(activity.findViewById<TextView>(R.id.toolResultName).text.contains("Fictional result"))
        } finally { controller.destroy() }
    }

    @Test(timeout = 30_000)
    fun `file PDF preview shows the produced first page without claiming a library save`() {
        val repo = ScanRepository(app)
        val source = DocFixtures.saved(repo, app, 2, "Fictional pages")
        val sourceFile = requireNotNull(repo.getDocument(source)).pdfFile
        val original = sourceFile.readBytes()
        val output = java.io.File(app.cacheDir, "documents/result-preview.pdf").apply {
            parentFile?.mkdirs()
            writeBytes(original)
        }
        val controller = Robolectric.buildActivity(ToolResultActivity::class.java,
            ToolResultActivity.intent(app, ResultSpec("pdfa", files = listOf(output), mime = "application/pdf"))).setup()
        try {
            val activity = controller.get()
            assertTrue("a real rendered page replaces the type-only fallback", await {
                val preview = activity.findViewById<android.widget.ImageView>(R.id.toolResultPreview)
                preview.visibility == View.VISIBLE && preview.drawable is android.graphics.drawable.BitmapDrawable
            })
            assertTrue(activity.findViewById<TextView>(R.id.toolResultSummary).text.toString().contains("2 pages"))
            assertTrue(activity.findViewById<TextView>(R.id.toolResultName).text.toString()
                .startsWith(app.getString(R.string.rs_not_saved_yet)))
            assertArrayEquals(original, sourceFile.readBytes())
        } finally { controller.destroy() }
    }

    @Test(timeout = 30_000)
    fun `cancelled chooser leaves the where-line unchanged`() {
        val controller = Robolectric.buildActivity(ToolResultActivity::class.java, imageIntent()).setup()
        try {
            val activity = controller.get()
            idle()
            val before = activity.findViewById<TextView>(R.id.toolResultName).text.toString()
            activity.findViewById<View>(R.id.toolResultMore).performClick()
            activity.findViewById<View>(R.id.toolResultSave).performClick()
            val chooser = requireNotNull(shadowOf(activity).nextStartedActivityForResult).intent
            shadowOf(activity).receiveResult(chooser, Activity.RESULT_CANCELED, null)
            idle()
            assertEquals(before, activity.findViewById<TextView>(R.id.toolResultName).text.toString())
            assertTrue(activity.window.decorView.findViewWithTag<View>("result-primary").isEnabled)
        } finally { controller.destroy() }
    }

    @Test(timeout = 30_000)
    fun `Adjust restores the exact input intent instead of using the output`() {
        val original = Intent(app, ToolSetupActivity::class.java)
            .putExtra("source_uri", "content://fictional/photo")
            .putExtra("min_kb", 20).putExtra("max_kb", 50)
            .putExtra("crop", intArrayOf(1, 2, 413, 531))
        val spec = ResultSpec("form_ready", files = listOf(RsFixtures.jpeg("adjust", 413, 531, app)),
            mime = "image/jpeg", adjustIntent = original)
        val controller = Robolectric.buildActivity(ToolResultActivity::class.java,
            ToolResultActivity.intent(app, spec)).setup()
        try {
            val activity = controller.get()
            activity.window.decorView.findViewWithTag<View>("result-adjust").performClick()
            val restored = shadowOf(activity).nextStartedActivity
            assertEquals(original.component, restored.component)
            assertEquals(original.getStringExtra("source_uri"), restored.getStringExtra("source_uri"))
            assertEquals(20, restored.getIntExtra("min_kb", 0))
            assertEquals(50, restored.getIntExtra("max_kb", 0))
            assertArrayEquals(original.getIntArrayExtra("crop"), restored.getIntArrayExtra("crop"))
            assertTrue(restored.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
            assertTrue(restored.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        } finally { controller.destroy() }
    }
}
