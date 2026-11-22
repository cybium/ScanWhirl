package com.scanwhirl.app.ui.tools

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.MergePdfActivity
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Review BLOCK of cc0866d4, P1: an erased (or whitespace-only) name must never silently fall
 * back to an auto-generated name. Every lifecycle here uses ActivityController with destroy in
 * finally; rotation is exercised through the controller's saved state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class NameValidationCoTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        val repo = ScanRepository(app)
        DocFixtures.saved(repo, app, pages = 1, name = "First source")
        DocFixtures.saved(repo, app, pages = 1, name = "Second source")
        ProGate.setTestOverride(app, true)
    }

    @After
    fun tearDown() = ProGate.setTestOverride(app, null)

    private fun findByTag(root: View?, tag: String): View? {
        if (root == null) return null
        if (root.tag == tag) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findByTag(root.getChildAt(i), tag)
                if (found != null) return found
            }
        }
        return null
    }

    private fun mergeController(): ActivityController<ToolSetupActivity> {
        val controller = Robolectric.buildActivity(
            ToolSetupActivity::class.java, ToolSetupActivity.intent(app, "merge")
        ).setup()
        idle()
        // Select both sources so Continue's readiness depends only on the name field.
        val source = controller.get().findViewById<ViewGroup>(R.id.toolSetupSource)!!
        source.getChildAt(0).performClick(); idle()
        controller.get().findViewById<ViewGroup>(R.id.toolSetupSource)!!
            .getChildAt(0).performClick(); idle()
        controller.get().findViewById<ViewGroup>(R.id.toolSetupSource)!!
            .getChildAt(1).performClick(); idle()
        return controller
    }

    @Test(timeout = 30_000)
    fun `blank or whitespace name disables Continue and shows an inline error`() {
        val controller = mergeController()
        try {
            val activity = controller.get()
            val field = activity.findViewById<EditText>(R.id.toolSetupName)!!
            val run = activity.findViewById<View>(R.id.toolSetupRun)!!
            val error = findByTag(activity.findViewById(R.id.toolSetupScroll), ToolSetupActivity.NAME_ERROR_TAG) as TextView
            assertNotNull(error)

            field.setText("")
            idle()
            assertTrue("empty name shows the error", error.isVisible)
            assertEquals(activity.getString(R.string.co_name_required), error.text.toString())
            assertFalse("empty name keeps Continue disabled", run.isEnabled)

            field.setText("   ")
            idle()
            assertTrue("whitespace-only is still blank", error.isVisible)
            assertFalse(run.isEnabled)

            field.setText("Valid copy")
            idle()
            assertFalse("a real name clears the error", error.isVisible)
            assertTrue("a real name allows Continue", run.isEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `an erased name and its error survive rotation`() {
        val controller = mergeController()
        try {
            val activity = controller.get()
            activity.findViewById<EditText>(R.id.toolSetupName)!!.setText("")
            idle()

            controller.recreate()
            idle()
            val restored = controller.get()
            assertEquals(
                "the blank field is restored",
                "",
                restored.findViewById<EditText>(R.id.toolSetupName)?.text?.toString()
            )
            val error = findByTag(
                restored.findViewById(R.id.toolSetupScroll), ToolSetupActivity.NAME_ERROR_TAG
            ) as TextView
            assertTrue("the inline error is restored", error.isVisible)
            assertFalse("Continue stays disabled after rotation", restored.findViewById<View>(R.id.toolSetupRun)!!.isEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test(timeout = 30_000)
    fun `device PDF merge name sheet rejects a blank field too`() {
        val controller = Robolectric.buildActivity(
            MergePdfActivity::class.java, MergePdfActivity.intent(app)
        ).setup()
        try {
            idle()
            val activity = controller.get()
            val pickedType = Class.forName("com.scanwhirl.app.ui.MergePdfActivity\$Picked")
            val ctor = pickedType.getDeclaredConstructor(Uri::class.java, String::class.java)
            ctor.isAccessible = true
            val pickedField = MergePdfActivity::class.java.getDeclaredField("picked")
            pickedField.isAccessible = true
            val list = pickedField.get(activity) as MutableList<Any>
            list.add(ctor.newInstance(Uri.parse("content://first.pdf"), "First.pdf"))
            list.add(ctor.newInstance(Uri.parse("content://second.pdf"), "Second.pdf"))

            val prompt = MergePdfActivity::class.java.getDeclaredMethod(
                "promptName", String::class.java
            )
            prompt.isAccessible = true
            prompt.invoke(activity, null)
            idle()

            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            val field = dialog.findViewById<EditText>(R.id.swSheetInput)!!
            val primary = dialog.findViewById<View>(R.id.swSheetPrimary)!!
            val error = findByTag(dialog.window!!.decorView, MergePdfActivity.NAME_ERROR_TAG) as TextView
            assertTrue("prefilled, so enabled", primary.isEnabled)

            field.setText("")
            idle()
            assertTrue("blank shows the error", error.isVisible)
            assertFalse("blank disables the merge action", primary.isEnabled)
            field.setText("  ")
            idle()
            assertTrue("whitespace is blank", error.isVisible)
            assertFalse(primary.isEnabled)
            field.setText("Combined")
            idle()
            assertFalse("a real name clears the error", error.isVisible)
            assertTrue(primary.isEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
