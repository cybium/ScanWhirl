package com.scanwhirl.app.w5e

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.PdfAFlow
import com.scanwhirl.app.ui.protect.LockToolsActivity
import com.scanwhirl.app.ui.qr.QrActivity
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.tools.ToolSetupActivity
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream

/**
 * VQA acceptance: the atlas "tool first screen" captures are search-result screens (atlas script
 * bug, owned by lead), so the named tools' real first screens cannot be pixel-verified from the
 * atlas. This test draws the REAL screens (and the PDF/A sheet) from the app's view trees in
 * light, dark and 2.0 font, as gate artifacts for lead review. No production behaviour is changed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class W5eToolRenderTest {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val outDir = File("build/test-results/testDebugUnitTest/w5e-renders")
    private val written = ArrayList<File>()

    private data class Leg(val name: String, val qualifiers: String, val scale: Float)

    private val legs = listOf(
        Leg("light", "en-rUS-w360dp-h800dp-notnight-xhdpi", 1f),
        Leg("dark", "en-rUS-w360dp-h800dp-night-xhdpi", 1f),
        Leg("font200", "en-rUS-w360dp-h800dp-notnight-xhdpi", 2f)
    )

    private val setupIds = listOf(
        "split", "to_word", "to_excel", "to_powerpoint", "long_image", "zip",
        "batch_tools", "searchable_pdf", "link_index", "remove_metadata",
        "form_ready", "read_text"
    )

    @Before
    fun setUp() {
        outDir.mkdirs()
        written.clear()
        ProGate.setTestOverride(ApplicationProvider.getApplicationContext(), true)
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(ApplicationProvider.getApplicationContext(), null)
        RuntimeEnvironment.setQualifiers("")
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun dismissOpenSheet() {
        repeat(3) {
            val dialog = ShadowDialog.getLatestDialog() ?: return
            if (!dialog.isShowing) return
            dialog.dismiss()
            idle()
        }
    }

    /** Draw the activity decor over the leg's real background token. */
    private fun renderDecor(name: String, activity: Activity): File {
        val w = 1080
        val h = 2400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(ContextCompat.getColor(activity, R.color.tok_bg))
        val host = activity.window.decorView
        host.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        host.layout(0, 0, w, h)
        host.draw(canvas)
        return saveChecked(name, bmp)
    }

    /** Draw a real sheet's own root over the dimmed host, like the atlas does. */
    private fun renderSheet(name: String, activity: Activity, sheet: View): File {
        val w = 1080
        val h = 2400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(ContextCompat.getColor(activity, R.color.tok_bg))
        val host = activity.window.decorView
        host.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        host.layout(0, 0, w, h)
        host.draw(canvas)
        canvas.drawColor(0x8F000000.toInt())
        val margin = 24
        val sheetW = w - margin * 2
        sheet.measure(
            View.MeasureSpec.makeMeasureSpec(sheetW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)
        )
        val sheetH = sheet.measuredHeight.coerceIn(1, h - 24)
        canvas.save()
        canvas.translate(margin.toFloat(), (h - sheetH).toFloat())
        canvas.clipRect(0, 0, sheetW, sheetH)
        sheet.layout(0, 0, sheetW, sheetH)
        sheet.draw(canvas)
        canvas.restore()
        return saveChecked(name, bmp)
    }

    private fun saveChecked(name: String, bmp: Bitmap): File {
        val colors = HashSet<Int>()
        for (y in 0 until 2400 step 16) for (x in 0 until 1080 step 16) colors.add(bmp.getPixel(x, y))
        assertTrue("$name: the render is blank (only ${colors.size} colours)", colors.size > 4)
        val file = File(outDir, "$name.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    @Test
    fun `toolFirstScreensRenderInLightDarkAndTwoTimesFont`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        for (leg in legs) {
            RuntimeEnvironment.setQualifiers(leg.qualifiers)
            RuntimeEnvironment.setFontScale(leg.scale)

            for (id in setupIds) {
                ActivityScenario.launch<ToolSetupActivity>(
                    ToolSetupActivity.intent(app, id)
                ).use { scenario ->
                    scenario.onActivity { activity ->
                        idle()
                        written += renderDecor("tool-$id-${leg.name}", activity)
                    }
                }
            }

            ActivityScenario.launch<QrActivity>(QrActivity.intent(app)).use { scenario ->
                scenario.onActivity { activity ->
                    idle()
                    dismissOpenSheet()
                    written += renderDecor("tool-qr_code-${leg.name}", activity)
                }
            }

            for (mode in listOf(LockToolsActivity.MODE_PROTECT, LockToolsActivity.MODE_UNLOCK)) {
                ActivityScenario.launch<LockToolsActivity>(
                    LockToolsActivity.intent(app, mode)
                ).use { scenario ->
                    scenario.onActivity { activity ->
                        idle()
                        dismissOpenSheet()
                        written += renderDecor("tool-$mode-${leg.name}", activity)
                    }
                }
            }

            // PDF/A has no ToolSetup screen: its real first screen is the confirm sheet.
            val repo = ScanRepository(app)
            val docId = DocFixtures.saved(repo, app, pages = 1, name = "PDF A fixture")
            ActivityScenario.launch<ShellActivity>(ShellActivity.intent(app)).use { scenario ->
                scenario.onActivity { activity ->
                    PdfAFlow.start(activity, repo)
                    idle()
                    val picker = ShadowDialog.getLatestDialog()
                    assertTrue("PDF/A picker did not open", picker?.isShowing == true)
                    val row = picker!!.findViewById<View>(R.id.docPickerRow)
                    assertTrue("PDF/A picker has no document row", row != null)
                    row!!.performClick()
                    idle()
                    val confirm = ShadowDialog.getLatestDialog()
                    assertTrue("PDF/A confirm sheet did not open", confirm?.isShowing == true)
                    val root = confirm!!.findViewById<View>(R.id.swSheetRoot)
                    assertTrue("PDF/A sheet has no root", root != null)
                    written += renderSheet("tool-pdfa-${leg.name}", activity, root!!)
                }
            }
        }

        File(outDir, "tool-manifest.txt").writeText(
            written.joinToString("\n") { "${it.name}  ${it.length()} bytes" } +
                "\nAll screens/sheets rendered from real view trees; atlas tool captures use the wrong screen.\n"
        )
        assertTrue(written.size == legs.size * (setupIds.size + 4))
        assertTrue(written.all { it.exists() && it.length() > 0 })
    }
}
