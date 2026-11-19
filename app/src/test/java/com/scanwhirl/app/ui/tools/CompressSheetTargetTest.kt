package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * B2-M3 (real-phone sweep 2026-11-19, build 2 = 1485874c): the Compress setup sheet exposed
 * 32dp-high compression-level rows and a 40dp custom-size field, under the app's 48dp rule
 * (UI_PLAN phase acceptance: "every touch target >= 48 dp").
 *
 * Driven through the real path the sweep used: document More -> Compress -> the level sheet.
 * Measured in English and Hindi, at 360dp.
 */
abstract class CompressSheetTargetBase {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        repo = ScanRepository(app)
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 15000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    private fun layoutAt360(activity: android.app.Activity, root: View) {
        val d = activity.resources.displayMetrics.density
        val w = Math.round(360f * d)
        val h = Math.round(800f * d)
        val bar = Math.round(48f * d)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, bar, 0, bar))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        val passW = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val passH = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        repeat(2) {
            root.measure(passW, passH)
            root.layout(0, 0, w, h)
        }
        idle()
    }

    private fun allViews(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test
    fun `compress level rows and custom size field are 48dp targets`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Compress setup")
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, id)
        ).use { s ->
            s.onActivity { a ->
                idle()
                assertTrue(
                    "the document loads before the sheet opens",
                    pollUntil {
                        a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                    }
                )
                a.findViewById<View>(R.id.btnMore).performClick()
                idle()
                val moreSheet = ShadowDialog.getLatestDialog()
                assertNotNull("the document More sheet opens", moreSheet)
                val compressRow = moreSheet.findViewById<View>(R.id.rowMoreCompress)
                assertNotNull("the Compress row is in the More sheet", compressRow)
                compressRow!!.performClick()
                // The estimates run off the main thread; the level sheet opens when they land.
                assertTrue(
                    "the compress sheet opens",
                    pollUntil {
                        ShadowDialog.getLatestDialog()?.window?.decorView
                            ?.findViewById<View>(R.id.editCompressTarget) != null
                    }
                )
                idle()
                val dialog = ShadowDialog.getLatestDialog()
                val decor = dialog.window?.decorView
                assertNotNull("the compress sheet has a window", decor)
                layoutAt360(a, decor!!)

                val d = a.resources.displayMetrics.density
                val min = Math.round(48f * d)
                val radios = allViews(decor).filterIsInstance<RadioButton>()
                assertTrue(
                    "the four compression levels are in the sheet, found ${radios.size}",
                    radios.size >= 4
                )
                val tooSmall = radios.filter { it.height < min }
                assertTrue(
                    "B2-M3: ${tooSmall.size} compression row(s) are shorter than 48dp: " +
                        radios.joinToString { "\"${it.text}\" ${it.height}px" },
                    tooSmall.isEmpty()
                )
                val field = decor.findViewById<EditText>(R.id.editCompressTarget)
                assertNotNull("the custom-size field is in the sheet", field)
                // B3: the size field belongs to the "Aim for a file size" aim, so it is measured
                // there (the quality aim shows the estimates instead).
                decor.findViewById<View>(R.id.compressModeTarget).performClick()
                idle()
                layoutAt360(a, decor)
                assertTrue(
                    "B2-M3: the custom-size field is ${field!!.height}px tall " +
                        "(${Math.round(field.height / d)}dp), needs 48dp",
                    field.height >= min
                )
                dialog.dismiss()
            }
        }
    }
}

/** The phone the sweep used: 360dp, English. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class CompressSheetTargetEnTest : CompressSheetTargetBase()

/** The same phone in Hindi, where the level labels are longer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class CompressSheetTargetHiTest : CompressSheetTargetBase()
