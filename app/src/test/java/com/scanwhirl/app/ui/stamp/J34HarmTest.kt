package com.scanwhirl.app.ui.stamp

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.SignatureStore
import com.scanwhirl.app.ui.result.ResultSpec
import com.scanwhirl.app.util.PageStamp
import com.scanwhirl.app.util.ResetDialogShadowsRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class J34HarmTest {
    @get:Rule val dialogs = ResetDialogShadowsRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)
    private fun screen(pages: Int = 3): StampEditorActivity {
        val id = DocFixtures.saved(repo, app, pages = pages, name = "Fictional form")
        return Robolectric.buildActivity(StampEditorActivity::class.java,
            StampEditorActivity.intent(app, id, StampEditorActivity.TOOL_REDACT)).setup().get()
    }
    private fun canvas(screen: StampEditorActivity) = screen.findViewById<StampCanvasView>(R.id.canvas)

    @Test fun `hide box stays armed for many boxes`() {
        val s = screen()
        val c = canvas(s)
        val boxes = listOf(PageStamp.Box(.1f,.2f,.4f,.25f), PageStamp.Box(.2f,.4f,.6f,.45f))
        boxes.forEach { c.listener!!.onBox(StampCanvasView.Tool.REDACT, it) }
        assertEquals(StampCanvasView.Tool.REDACT, c.tool)
        assertEquals(boxes, s.editor.marks().filterIsInstance<PageStamp.Mark.Redact>().map { it.box })
        s.findViewById<View>(R.id.nextPage).performClick()
        s.findViewById<View>(R.id.nextPage).performClick()
        c.listener!!.onBox(StampCanvasView.Tool.REDACT, boxes[0])
        assertEquals(StampCanvasView.Tool.REDACT, c.tool)
        assertEquals(2, s.editor.marks(0).size)
        assertEquals(1, s.editor.marks(2).size)
    }

    @Test fun `two-finger pinch never adds a mark`() {
        val s = screen(1)
        val c = canvas(s)
        c.layout(0,0,600,900)
        fun event(action: Int, points: List<Pair<Float,Float>>) {
            val properties = points.indices.map { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
            val coords = points.map { p -> MotionEvent.PointerCoords().apply { x = p.first; y = p.second; pressure = 1f; size = 1f } }.toTypedArray()
            val e = MotionEvent.obtain(0, 10, action, points.size, properties, coords,
                0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
            c.onTouchEvent(e); e.recycle()
        }
        event(MotionEvent.ACTION_DOWN, listOf(200f to 300f))
        event(MotionEvent.ACTION_MOVE, listOf(300f to 350f))
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(300f to 350f, 400f to 450f))
        event(MotionEvent.ACTION_MOVE, listOf(100f to 200f, 500f to 600f))
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(100f to 200f, 500f to 600f))
        event(MotionEvent.ACTION_UP, listOf(100f to 200f))
        assertTrue(s.editor.isEmpty())
        assertFalse(s.editor.canUndo)
        assertTrue(c.zoom > 1f)
        assertEquals(StampCanvasView.Tool.REDACT, c.tool)
    }

    @Test fun `review disables Save in Original`() {
        val s = screen(1)
        cAdd(s)
        s.openReview()
        val dialog = ShadowDialog.getLatestDialog()!!
        val views = J34TestFlow.all(dialog.window!!.decorView)
        val check = views.first { it.tag == "review-checked" } as CheckBox
        check.isChecked = true
        val save = dialog.findViewById<View>(R.id.swSheetPrimary)
        assertTrue(save.isEnabled)
        views.first { it.tag == "review-original" }.performClick()
        assertFalse(save.isEnabled)
        assertFalse(check.isChecked)
        assertFalse(check.isEnabled)
        assertTrue((views.first { it.tag == "review-page" } as StampCanvasView).originalReview)
    }

    private fun cAdd(s: StampEditorActivity) {
        canvas(s).listener!!.onBox(StampCanvasView.Tool.REDACT, PageStamp.Box(.1f,.1f,.4f,.2f))
    }

    @Test fun `an edit invalidates the review`() {
        val s = StampEditorState(3)
        s.add(PageStamp.Mark.Redact(PageStamp.Box(.1f,.2f,.4f,.3f)))
        val gate = StampReview(s.revision, setOf(0,2))
        gate.visit(0); gate.acknowledged = true
        assertFalse(gate.canSave(s.revision))
        gate.visit(2)
        assertTrue(gate.canSave(s.revision))
        s.moveBy(0,.01f,0f)
        assertFalse(gate.canSave(s.revision))
        s.undo()
        assertFalse("undo does not resurrect stale approval", gate.canSave(s.revision))
    }

    @Test fun `signature keeps aspect`() {
        val s = StampEditorState(1)
        s.add(PageStamp.Mark.Signature(emptyList(), PageStamp.Box(.1f,.1f,.5f,.2f)))
        s.resizeTo(0, PageStamp.Box(.1f,.1f,.8f,.9f))
        val box = (s.marks().single() as PageStamp.Mark.Signature).box
        assertEquals(4f,(box.right-box.left)/(box.bottom-box.top),.0001f)
    }

    @Test fun `export only after verified burn-in`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 1)
        val original = repo.getDocument(id)!!
        val originalBytes = original.pdfFile.readBytes()
        val box = PageStamp.Box(.1f,.1f,.6f,.4f)
        val copy = repo.stampDocument(id, listOf(PageStamp.PageEdits(0, listOf(PageStamp.Mark.Redact(box)))), "Verified fixture")
        val page = BitmapFactory.decodeFile(copy.pageFiles.single().path)!!
        for (y in (page.height*.15f).toInt() until (page.height*.35f).toInt())
            for (x in (page.width*.15f).toInt() until (page.width*.55f).toInt()) {
                val p = page.getPixel(x,y)
                assertTrue(Color.red(p)<20 && Color.green(p)<20 && Color.blue(p)<20)
            }
        page.recycle()
        assertTrue(copy.pdfFile.isFile)
        assertTrue(originalBytes.contentEquals(original.pdfFile.readBytes()))
        assertFalse(repo.ocrStore.readGeo(copy.id).isNotEmpty())
    }

    @Test fun `name sheet saves a new copy original untouched`() {
        val s = screen(1)
        val id = s.intent.getStringExtra(StampEditorActivity.EXTRA_DOC_ID)!!
        val source = repo.getDocument(id)!!
        val before = source.pdfFile.readBytes()
        cAdd(s)
        s.findViewById<View>(R.id.saveCopy).performClick()
        J34TestFlow.completeReview()
        assertEquals(1, repo.listDocuments().size)
        J34TestFlow.confirmName("Fictional hidden copy")
        val deadline = System.currentTimeMillis()+10000
        while (repo.listDocuments().size < 2 && System.currentTimeMillis()<deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(2,repo.listDocuments().size)
        val output = repo.listDocuments().first { it.id != id }
        assertEquals("Fictional hidden copy", output.name)
        assertTrue(before.contentEquals(source.pdfFile.readBytes()))
    }

    @Test fun `batch sign places saved vector ink on every page in one undo`() {
        val s = screen()
        val saved = SignatureStore(app).save("Fictional signature",
            listOf(PageStamp.Stroke(listOf(PageStamp.Point(.1f,.1f),PageStamp.Point(.8f,.7f)))))
        s.applySignatureToAll(saved)
        repeat(3) { assertTrue(s.editor.marks(it).single() is PageStamp.Mark.Signature) }
        assertTrue(s.editor.undo())
        assertTrue(s.editor.isEmpty())
    }

    @Test fun `ink extraction never blocks main and OOM keeps editor alive`() = runBlocking {
        val source = Bitmap.createBitmap(80,50,Bitmap.Config.ARGB_8888)
        val main = Thread.currentThread()
        var decodedOffMain = false
        var extractedOffMain = false
        val result = StampInkLoader.load(java.io.File(app.cacheDir,"fictional-ink.jpg"),
            decode = { decodedOffMain = Thread.currentThread() != main; source },
            extract = {
                extractedOffMain = Thread.currentThread() != main
                throw OutOfMemoryError("synthetic allocation failure")
            })
        assertNull(result)
        assertTrue(decodedOffMain)
        assertTrue(extractedOffMain)
        assertTrue(source.isRecycled)
    }

    @Test fun `privacy receipt survives rotation without another copy`() {
        val id = DocFixtures.saved(repo,app,pages=1)
        val mark = PageStamp.Mark.Redact(PageStamp.Box(.1f,.1f,.5f,.2f))
        val copy = runBlocking { repo.stampDocument(id,listOf(PageStamp.PageEdits(0,listOf(mark))),"Receipt fixture") }
        androidx.test.core.app.ActivityScenario.launch<StampEditorActivity>(
            StampEditorActivity.intent(app,id,StampEditorActivity.TOOL_REDACT)).use { scenario ->
            scenario.onActivity {
                it.editor.add(mark)
                it.deliverSaveForTest(Result.success(copy),R.string.private_info_label)
            }
            scenario.recreate()
            scenario.onActivity { s ->
                val dialog = ShadowDialog.getLatestDialog()!!
                assertTrue(dialog.isShowing)
                assertEquals(app.getString(R.string.v2v_redact_receipt_title),
                    dialog.findViewById<android.widget.TextView>(R.id.swSheetTitle).text.toString())
                assertEquals(2,repo.listDocuments().size)
                assertEquals(listOf(mark),s.editor.marks())
            }
        }
    }

    @Test fun `TalkBack can select and resize named areas without drawing`() {
        val s = screen(1)
        cAdd(s)
        val c = canvas(s)
        c.layout(0,0,600,900)
        // Robolectric cannot decode the fixture page through BitmapFactory, so the canvas has no
        // page rectangle yet. Give it an upright raster; the named-node assertions stay unchanged.
        if (c.pageBitmap == null) c.pageBitmap = Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888)
        val provider = androidx.core.view.ViewCompat.getAccessibilityNodeProvider(c)!!
        val node = provider.createAccessibilityNodeInfo(0)!!
        assertTrue(node.contentDescription.toString().contains("Hidden area 1"))
        assertTrue(provider.performAction(0,androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK,null))
        val before = (s.editor.marks().single() as PageStamp.Mark.Redact).box
        assertTrue(provider.performAction(0,R.id.a11y_grow,null))
        val after = (s.editor.marks().single() as PageStamp.Mark.Redact).box
        assertTrue(after.right-after.left > before.right-before.left)
        assertTrue(after.bottom-after.top > before.bottom-before.top)
    }

    @Test fun `editing while review is visible disables its Save immediately`() {
        val s = screen(1)
        cAdd(s)
        s.openReview()
        val dialog = ShadowDialog.getLatestDialog()!!
        val check = J34TestFlow.all(dialog.window!!.decorView).first { it.tag == "review-checked" } as CheckBox
        check.isChecked = true
        assertTrue(dialog.findViewById<View>(R.id.swSheetPrimary).isEnabled)
        s.editor.moveBy(0,.03f,0f)
        s.refresh()
        assertFalse(dialog.findViewById<View>(R.id.swSheetPrimary).isEnabled)
    }

    @Test fun `cancelled move restores exact box without an undo entry`() {
        val s = screen(1)
        cAdd(s)
        val c = canvas(s)
        c.tool = StampCanvasView.Tool.SELECT
        c.layout(0,0,600,900)
        c.pageBitmap = Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888)
        val before = s.editor.marks()
        fun touch(action: Int,x: Float,y: Float) {
            val e = MotionEvent.obtain(0,10,action,x,y,0)
            c.onTouchEvent(e); e.recycle()
        }
        touch(MotionEvent.ACTION_DOWN,155f,140f)
        touch(MotionEvent.ACTION_MOVE,215f,190f)
        touch(MotionEvent.ACTION_CANCEL,215f,190f)
        assertEquals(before,s.editor.marks())
        assertTrue("only the original add remains in history",s.editor.undo())
        assertTrue(s.editor.isEmpty())
        assertFalse(s.editor.canUndo)
    }

    @Test fun `a failed save retains every edit and publishes no copy`() {
        val s = screen(1)
        cAdd(s)
        val id = s.intent.getStringExtra(StampEditorActivity.EXTRA_DOC_ID)!!
        val before = s.editor.edits()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        StampSaveRunner.submit(id,SaveMeta(before,R.string.private_info_label,J34TestFlow.COPY_NAME)) {
            release.await()
            throw java.io.IOException("synthetic write failure")
        }
        s.findViewById<View>(R.id.saveCopy).performClick()
        J34TestFlow.completeReview()
        J34TestFlow.confirmName()
        release.complete(Unit)
        val deadline = System.currentTimeMillis()+10000
        while (StampSaveRunner.pendingFor(id) != null && System.currentTimeMillis()<deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(before,s.editor.edits())
        assertEquals(1,repo.listDocuments().size)
        assertTrue(s.findViewById<View>(R.id.saveCopy).isEnabled)
        assertTrue(canvas(s).isEnabled)
    }

    @Test fun `restoring a reordered source keeps boxes on their immutable page identity`() {
        val state = StampEditorState(3,listOf("form/a","form/b","form/c"))
        val mark = PageStamp.Mark.Redact(PageStamp.Box(.15f,.2f,.45f,.3f))
        state.currentPage = 2
        state.add(mark)
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { state.writeTo(it) }
        val restored = StampEditorState(3,listOf("form/c","form/a","form/b"))
        java.io.DataInputStream(java.io.ByteArrayInputStream(bytes.toByteArray())).use { restored.readFrom(it) }
        assertEquals("form/c",restored.pageId())
        assertEquals(0,restored.currentPage)
        assertEquals(listOf(mark),restored.marks())
        assertTrue(restored.marks(2).isEmpty())
    }
}
