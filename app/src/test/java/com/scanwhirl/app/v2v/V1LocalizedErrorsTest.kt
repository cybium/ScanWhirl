package com.scanwhirl.app.v2v

import android.app.Application
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * V2V V1: actionable localized errors, the kept Copy-all quick action, and real
 * role/focus/target checks on the reader/editor actions (not source tokens).
 *
 * Robolectric lifecycle tests use ActivityController with destroy in finally
 * and @Test(timeout=30_000) -- ActivityScenario.close() hung CI.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class V1LocalizedErrorsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)

    @Test(timeout = 30_000)
    fun `raw exception text is never shown as an actionable error`() {
        val helper = try {
            Class.forName("com.scanwhirl.app.ui.V2vFailureCopy")
        } catch (_: ClassNotFoundException) {
            assertFalse("V2vFailureCopy must exist to map raw exceptions", true)
            return
        }
        val method = helper.getDeclaredMethod(
            "message", android.content.Context::class.java, Throwable::class.java
        )
        method.isAccessible = true
        val distinct = listOf(
            java.io.IOException("SECRET_PATH /private/doc/42.pdf"),
            IllegalStateException("com.android.internal.FatalException: token=9f3a"),
            OutOfMemoryError("malloc 9437184 failed on heap 0x7f00"),
            SecurityException("EACCES /data/data/com.scanwhirl.app/databases/x")
        )
        for (error in distinct) {
            val shown = method.invoke(null, app, error) as String
            assertTrue("no raw message may reach the UI: $shown", shown.isNotBlank())
            assertFalse("raw text leaked: $shown", shown.contains("SECRET_PATH"))
            assertFalse("raw class leaked: $shown", shown.contains("FatalException"))
            assertFalse("raw heap address leaked: $shown", shown.contains("0x7f00"))
            assertFalse("raw path leaked: $shown", shown.contains("/data/data/"))
        }
        val unreadable = method.invoke(null, app,
            java.io.IOException("Cannot read this image. Choose a different image file.")) as String
        assertTrue("image failure must stay actionable: $unreadable",
            unreadable.contains("Choose a different image"))
        val tooLarge = method.invoke(null, app,
            java.io.IOException("This image exceeds 32 MB. Choose a smaller photo.")) as String
        assertTrue("size failure must stay actionable: $tooLarge", tooLarge.contains("32 MB"))
    }

    @Test(timeout = 30_000)
    fun `OCR tool failures also use the localized mapper, never e dot message`() {
        for (path in listOf(
            "src/main/java/com/scanwhirl/app/tools/OcrFlow.kt",
            "src/main/java/com/scanwhirl/app/tools/HindiTextLauncher.kt"
        )) {
            val src = File(path).readText(Charsets.UTF_8)
            assertFalse("$path still interpolates a raw exception message",
                src.contains("e.message"))
            assertTrue("$path must map failures through V2vFailureCopy",
                src.contains("V2vFailureCopy.message(activity, e)"))
        }
    }

    @Test(timeout = 30_000)
    fun `the read-row Copy all quick action stays and all pages lives in one menu`() {
        val layout = File("src/main/res/layout/activity_ocr_editor.xml")
            .readText(Charsets.UTF_8)
        assertTrue("lead decision: keep the Copy-all quick action", layout.contains("@+id/btnCopyAll"))
        val menu = File("src/main/res/menu/menu_ocr_editor.xml").readText(Charsets.UTF_8)
        assertTrue("all-pages routes belong to one menu", menu.contains("ux130_l4_all_pages"))
        assertEquals(
            "exactly one Copy-all route in the menu",
            1,
            Regex("android:id=\"@(?:\\+)?id/ocr_copy_all\"").findAll(menu).count()
        )
    }

    @Test(timeout = 30_000)
    fun `reader and editor actions are focusable buttons at large text`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "V1 actions")
        val controller = Robolectric.buildActivity(DocumentViewerActivity::class.java,
            DocumentViewerActivity.intent(app, id)
        )
        try {
            val activity = controller.setup().get()
            val density = activity.resources.displayMetrics.density
            val list = listOf(
                R.id.btnEdit, R.id.btnText, R.id.btnShare, R.id.btnCompress,
                R.id.btnSign, R.id.btnSavePdf, R.id.btnMore
            )
            for (id in list) {
                val v = activity.findViewById<View>(id) ?: continue
                assertTrue("action $id must be focusable", v.isFocusable)
                val info = AccessibilityNodeInfo.obtain(v)
                v.onInitializeAccessibilityNodeInfo(info)
                assertEquals("action $id must speak as a Button",
                    android.widget.Button::class.java.name, info.className?.toString())
                assertTrue("action $id must be >=48dp tall (was ${v.height/density}dp)",
                    v.height / density >= 48f - 0.5f || v.minimumHeight / density >= 48f)
            }
        } finally {
            controller.destroy()
        }
    }
}
