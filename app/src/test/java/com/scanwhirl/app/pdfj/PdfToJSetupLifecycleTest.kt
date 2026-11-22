package com.scanwhirl.app.pdfj

import android.app.Application
import android.content.Intent
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.tools.ToolSetupActivity
import com.scanwhirl.app.ui.tools.form.FormReadyActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FF-J setup lifecycle: every ActivityController is destroyed in a finally block and every
 * test is bounded, so a leaked native renderer or a stuck loader fails loudly instead of
 * hanging the suite.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class PdfToJSetupLifecycleTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun open(): ActivityController<ToolSetupActivity> =
        Robolectric.buildActivity(
            ToolSetupActivity::class.java,
            ToolSetupActivity.intent(app, "pdf_to_images")
        ).setup()

    @Test(timeout = 30_000)
    fun `setup offers choose pages separate files zip bundle and the form handoff`() {
        val controller = open()
        try {
            val activity = controller.get()
            assertNotNull("Choose pages", activity.findViewById<View>(R.id.toolSetupPdfPages))
            assertNotNull("Separate JPG files", activity.findViewById<View>(R.id.toolSetupPdfSeparate))
            assertNotNull("ZIP bundle", activity.findViewById<View>(R.id.toolSetupPdfZip))
            assertNotNull("Prepare one image for a form", activity.findViewById<View>(R.id.toolSetupPdfForm))
            assertNotNull("existing JPG chip stays", activity.findViewById<View>(R.id.toolSetupFormatJpg))
            assertNotNull("existing PNG chip stays", activity.findViewById<View>(R.id.toolSetupFormatPng))
            assertNotNull("existing quality control stays", activity.findViewById<View>(R.id.toolSetupQuality))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `separate is the default and the zip chip is an explicit opt in`() {
        val controller = open()
        try {
            val activity = controller.get()
            val separate = activity.findViewById<View>(R.id.toolSetupPdfSeparate)
            val zip = activity.findViewById<View>(R.id.toolSetupPdfZip)
            val pages = activity.findViewById<EditText>(R.id.toolSetupPdfPages)

            assertTrue("separate files start selected", separate.isSelected)
            assertTrue("ZIP starts off", !zip.isSelected)
            zip.performClick()
            assertTrue("ZIP can be chosen", zip.isSelected)
            assertTrue("choosing ZIP turns separate off", !separate.isSelected)

            pages.setText("2")
            assertEquals("2", pages.text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `the form handoff uses the existing Form photo launch intent with source context`() {
        val controller = open()
        try {
            val activity = controller.get()
            activity.findViewById<View>(R.id.toolSetupPdfForm).performClick()
            val started: Intent = Shadows.shadowOf(activity).nextStartedActivity
                ?: throw AssertionError("Form photo did not open")
            assertEquals(FormReadyActivity::class.java.name, started.component?.className)
            assertTrue("source document travels with the handoff", started.hasExtra("ffj_source_doc"))
            assertTrue("source page travels with the handoff", started.hasExtra("ffj_source_page"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `rotation keeps the typed pages and the chosen layout`() {
        val controller = open()
        try {
            val first = controller.get()
            first.findViewById<EditText>(R.id.toolSetupPdfPages).setText("1,3")
            first.findViewById<View>(R.id.toolSetupPdfZip).performClick()
            controller.recreate()
            val after = controller.get()
            assertEquals("1,3", after.findViewById<EditText>(R.id.toolSetupPdfPages).text.toString())
            assertTrue("the ZIP choice survives recreation", after.findViewById<View>(R.id.toolSetupPdfZip).isSelected)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
