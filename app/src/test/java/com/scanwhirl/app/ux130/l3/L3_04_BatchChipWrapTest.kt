package com.scanwhirl.app.ux130.l3
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.tools.BatchActivity
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * L3-04 P2 — batch option chips wrap at 200% type instead of being pinned to a
 * fixed 48dp height. The 48dp rule is a minimum, not a cap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class L3_04_BatchChipWrapTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        ProGate.setTestOverride(app, true)
        val repo = ScanRepository(app)
        if (repo.listDocuments().isEmpty()) DocFixtures.saved(repo, app, pages = 1, name = "chip doc")
        val config = Configuration(app.resources.configuration).apply { fontScale = 2f }
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
        val config = Configuration(app.resources.configuration).apply { fontScale = 1f }
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000L
        while (!condition() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(25L)
        }
        idle()
    }

    @Test
    fun `the four batch option chips wrap and clear 48dp at 200 percent`() {
        val ids = ScanRepository(app).listDocuments().map { it.id }
        ActivityScenario.launch<BatchActivity>(BatchActivity.intent(app, ids)).use { scenario ->
            waitUntil { ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchActionImages) != null }
            scenario.onActivity { _ ->
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.batchActionImages).performClick()
                idle()
            }
            waitUntil { ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchFormatJpg) != null }
            scenario.onActivity { _ ->
                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                val decor = requireNotNull(dialog.window?.decorView)
                val density = app.resources.displayMetrics.density
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.EXACTLY)
                )
                decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                for (id in listOf(R.id.batchFormatJpg, R.id.batchFormatPng, R.id.batchPerDoc, R.id.batchOneZip)) {
                    val chip = dialog.findViewById<TextView>(id)
                    assertTrue("chip $id exists", chip != null)
                    assertTrue("chip $id clears 48dp", chip.minimumHeight >= (48 * density).toInt())
                    assertTrue("chip $id has real height", chip.measuredHeight >= (48 * density).toInt())
                    assertTrue("chip $id keeps its text", chip.text.isNotBlank())
                }
            }
        }
    }
}
