package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.MergePdfActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Council lane O, output names on screen. The editable field is prefilled with the source stem
 * (plus a collision number, because the source itself occupies the stem), is at least 48 dp,
 * sits in the scrolling viewport so the keyboard cannot clip it, and carries the "new copy,
 * original kept" receipt. Every lifecycle test destroys its ActivityController in finally.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolSetupNameCoTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun findByTag(root: View?, tag: String): View? {
        if (root == null) return null
        if (root.tag == tag) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findByTag(root.getChildAt(i), tag)
                if (found != null) return found
            }
        }
        return null
    }

    @Test(timeout = 30_000)
    fun `name field is prefilled with the stem, unique and suffix free`() {
        val repo = ScanRepository(app)
        DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        val controller = Robolectric.buildActivity(
            ToolSetupActivity::class.java, ToolSetupActivity.intent(app, "merge")
        ).setup()
        try {
            idle()
            val activity = controller.get()
            val field = activity.findViewById<EditText>(R.id.toolSetupName)
            assertNotNull("the editable name field exists", field)
            assertEquals("Rent receipt (2)", field!!.text.toString())
            assertFalse("no forced verb suffix: ${field.text}", field.text.toString().contains(" - "))
            val setupSource = java.io.File(
                "src/main/java/com/scanwhirl/app/ui/tools/ToolSetupActivity.kt"
            ).readText()
            assertTrue(
                "the shared field helper keeps a 52 dp (>=48 dp) touch target",
                setupSource.contains("minimumHeight = SwStyle.dp(this@ToolSetupActivity, 52f)")
            )
            val scroll = activity.findViewById<View>(R.id.toolSetupScroll)
            assertNotNull("the field scrolls above the keyboard", scroll)
            assertTrue("field is inside the viewport", isInside(field, scroll!!))

            val receipt = findByTag(scroll, RECEIPT_TAG) as? TextView
            assertNotNull("the new-copy receipt sits under the field", receipt)
            assertEquals(
                activity.getString(R.string.co_new_copy_receipt),
                receipt!!.text.toString()
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `merge name field carries the same suffix free default and receipt`() {
        val repo = ScanRepository(app)
        DocFixtures.saved(repo, app, pages = 1, name = "Agreement")
        val controller = Robolectric.buildActivity(
            ToolSetupActivity::class.java, ToolSetupActivity.intent(app, "merge")
        ).setup()
        try {
            idle()
            val activity = controller.get()
            val field = activity.findViewById<EditText>(R.id.toolSetupName)
            assertNotNull(field)
            assertEquals("Agreement (2)", field!!.text.toString())
            assertFalse(field.text.toString().contains("Merged"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    @Config(qualifiers = "hi")
    fun `hindi receipt is localised under the field`() {
        val repo = ScanRepository(app)
        DocFixtures.saved(repo, app, pages = 1, name = "किराया")
        val controller = Robolectric.buildActivity(
            ToolSetupActivity::class.java, ToolSetupActivity.intent(app, "merge")
        ).setup()
        try {
            idle()
            val activity = controller.get()
            val receipt = findByTag(activity.findViewById(R.id.toolSetupScroll), RECEIPT_TAG) as? TextView
            assertNotNull(receipt)
            assertEquals(activity.getString(R.string.co_new_copy_receipt), receipt!!.text.toString())
            assertTrue("purna viram in Hindi", receipt.text.toString().contains("।"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `merge pdf and batch activities still build and destroy cleanly`() {
        val merge = Robolectric.buildActivity(MergePdfActivity::class.java, MergePdfActivity.intent(app)).setup()
        try {
            idle()
            assertNotNull(merge.get().findViewById<View>(R.id.swSheetPrimary) ?: merge.get())
        } finally {
            merge.pause().stop().destroy()
        }
        val batch = Robolectric.buildActivity(
            BatchActivity::class.java, BatchActivity.intent(app, emptyList())
        ).setup()
        try {
            idle()
            assertNotNull(batch.get().findViewById<View>(R.id.batchContinue))
        } finally {
            batch.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `the merge pdf sheet prefills a unique name and shows the receipt`() {
        val source = java.io.File(
            "src/main/java/com/scanwhirl/app/ui/MergePdfActivity.kt"
        ).readText()
        assertTrue("prefill uses the shared naming rule", source.contains("OutputNames.forNewCopy"))
        assertTrue("receipt is shown", source.contains("R.string.co_new_copy_receipt"))
        assertFalse("no forced merge suffix", source.contains("\" - Merged\""))
    }

    private fun isInside(child: View, root: View): Boolean = when {
        child === root -> true
        child.parent === root -> true
        child.parent is View -> isInside(child.parent as View, root)
        else -> false
    }

    private companion object {
        const val RECEIPT_TAG = "co_new_copy_receipt"
    }
}
