package com.scanwhirl.app.ui.stamp

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.SignatureStore
import com.scanwhirl.app.store.StampStore
import com.scanwhirl.app.util.PageStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Phone QA run4a: photo stamps/signatures must save into their store and land on the page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StampImportTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val repo get() = ScanRepository(context)

    private fun activity(id: String): StampEditorActivity {
        val intent = Intent(context, StampEditorActivity::class.java)
            .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Robolectric.buildActivity(StampEditorActivity::class.java, intent).setup().get()
    }

    private fun ink(): Bitmap = Bitmap.createBitmap(80, 50, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.TRANSPARENT)
        val paint = Paint().apply { color = Color.rgb(120, 20, 160) }
        Canvas(this).drawRect(6f, 8f, 74f, 42f, paint)
    }

    @Test
    fun `a photo stamp saves and places and the next one cascades`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val screen = activity(id)

        screen.placeImportedStampForTest(ink())

        assertEquals("the stamp must be in My stamps", 1, StampStore(context).list().size)
        val first = screen.editor.marks().single() as PageStamp.Mark.Picture
        assertTrue("the placed mark reads the saved PNG", first.file.isFile)

        screen.placeImportedStampForTest(ink())
        val marks = screen.editor.marks()
        assertEquals(2, marks.size)
        val second = marks.last() as PageStamp.Mark.Picture
        assertEquals("cascade right", first.box.left + 0.06f, second.box.left, 0.001f)
        assertEquals("cascade down", first.box.top + 0.06f, second.box.top, 0.001f)
        assertEquals(2, StampStore(context).list().size)
    }

    @Test
    fun `a photo signature saves and places`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val screen = activity(id)

        screen.placeImportedSignatureForTest(ink())

        assertEquals(1, SignatureStore(context).list().size)
        assertTrue(SignatureStore(context).list().single().isBitmap)
        assertTrue(screen.editor.marks().single() is PageStamp.Mark.Picture)
    }

    @Test
    fun `save with no marks is disabled and says add a mark first`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val screen = activity(id)
        val save = screen.findViewById<android.view.View>(R.id.saveCopy)
        assertTrue("the empty editor cannot save", !save.isEnabled)
        assertTrue("disabled controls are dimmed", save.alpha < 0.5f)

        save.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("nothing was written", 1, repo.listDocuments().size)
        val snackbar = screen.findViewById<TextView>(
            com.google.android.material.R.id.snackbar_text
        )
        assertEquals(context.getString(R.string.stamp_no_marks), snackbar?.text?.toString())
    }
}
