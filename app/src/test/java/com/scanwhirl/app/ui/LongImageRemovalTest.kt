package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.File

/**
 * WP-08 (owner decision O-01) — Long Image is REMOVED, not hidden.
 *
 * Replaces the old tests that asserted the tile / row / pill / stitcher
 * existed. These verify that nothing is reachable, that no dead reference is
 * left in the shipped sources/resources, and that user data (scan pages and
 * any previously exported long-image file) is untouched and still shareable as
 * an ordinary image.
 *
 * Synthetic fixtures only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LongImageRemovalTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
    }

    // ---- helpers -------------------------------------------------------

    private val longImageText = Regex("(?i)long[\\s_-]*image|stitch")

    private fun jpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(310, 444, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun seedDoc(name: String, pages: Int = 2): String = runBlocking {
        val uris = (0 until pages).map { i ->
            Uri.parse("content://test/li_${System.nanoTime()}_$i.jpg").also {
                shadowOf(app.contentResolver).registerInputStream(it, jpegBytes().inputStream())
            }
        }
        repo.saveScan(uris, null, name).id
    }

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return condition()
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun texts(root: View): List<String> =
        allViews(root).filterIsInstance<TextView>().map { it.text?.toString().orEmpty() }

    private fun id(name: String) = app.resources.getIdentifier(name, "id", app.packageName)

    private fun openViewerMore(
        docId: String,
        check: (android.app.Dialog, DocumentViewerActivity) -> Unit
    ) {
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, docId))
            .use { scenario ->
                assertTrue("document loads", pollUntil {
                    var ready = false
                    scenario.onActivity { a ->
                        ready = a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                    }
                    ready
                })
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.btnMore).performClick()
                    val sheet = ShadowDialog.getLatestDialog()
                    assertNotNull("More sheet opens", sheet)
                    check(sheet!!, activity)
                    sheet.dismiss()
                }
            }
    }

    // ---- UI is unreachable --------------------------------------------

    @Test
    fun `tools sheet has no long image tile in Free or Pro`() {
        for (forcedFree in listOf(true, false)) {
            ProGate.setTestOverride(app, !forcedFree)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.btnTools).performClick()
                    val sheet = ShadowDialog.getLatestDialog()
                    assertNotNull("Tools sheet opens", sheet)
                    assertEquals("tile_long_image id is gone", 0, id("tile_long_image"))
                    val tiles = allViews(sheet!!.window!!.decorView)
                        .filter { it.id != View.NO_ID }
                        .mapNotNull { runCatching { app.resources.getResourceEntryName(it.id) }.getOrNull() }
                        .filter { it.startsWith("tile_") }
                    assertEquals(
                        // M08: six live tiles; the Vault tile only exists for a
                        // legacy Vault owner, and the PRO band is gone.
                        "exactly the 6 remaining tiles: $tiles", 6, tiles.toSet().size
                    )
                    assertFalse("no Long Image label (forcedFree=$forcedFree)",
                        texts(sheet.window!!.decorView).any { longImageText.containsMatchIn(it) })
                    sheet.dismiss()
                }
            }
        }
    }

    @Test
    fun `document more sheet has no long image row or pill in Free or Pro`() {
        val docId = seedDoc("No long image")
        for (forcedFree in listOf(true, false)) {
            ProGate.setTestOverride(app, !forcedFree)
            openViewerMore(docId) { sheet, _ ->
                assertEquals("rowMoreLongImage id is gone", 0, id("rowMoreLongImage"))
                assertEquals("pillLongImage id is gone", 0, id("pillLongImage"))
                assertFalse("no Long Image text in the More sheet (forcedFree=$forcedFree)",
                    texts(sheet.window!!.decorView).any { longImageText.containsMatchIn(it) })
                // The remaining rows are intact.
                for (row in listOf(
                    R.id.rowMoreRename, R.id.rowMoreMove, R.id.rowMoreDuplicate,
                    R.id.rowMoreCompress, R.id.rowMoreLock, R.id.rowMoreVault, R.id.rowMoreDelete
                )) assertNotNull("row $row still exists", sheet.findViewById<View>(row))
                // Pro pill on Lock only for Free; none on the vault row.
                assertEquals(forcedFree, sheet.findViewById<View>(R.id.pillLock).isVisible)
                assertFalse(sheet.findViewById<View>(R.id.pillVault).isVisible)
            }
        }
    }

    @Test
    fun `free pro panel carries no long image marketing`() {
        ProGate.setTestOverride(app, false)
        val docId = seedDoc("Pro panel copy", pages = 1)
        openViewerMore(docId) { sheet, _ ->
            sheet.findViewById<View>(R.id.rowMoreLock).performClick() // Free → Pro panel
            val panel = ShadowDialog.getLatestDialog()
            assertNotNull("Pro panel opens", panel)
            assertNotNull("…it is the Pro dossier", panel!!.findViewById<View>(R.id.proDossierStatus))
            assertFalse("no Long Image copy in the Pro panel",
                texts(panel.window!!.decorView).any { longImageText.containsMatchIn(it) })
        }
    }

    // ---- no dead references -------------------------------------------

    @Test
    fun `no long image class resource or string survives`() {
        for (cls in listOf(
            "com.scanwhirl.app.util.LongImage",
            "com.scanwhirl.app.tools.LongImageFlow"
        )) {
            val found = runCatching { Class.forName(cls) }.isSuccess
            assertFalse("$cls must be deleted", found)
        }
        for ((type, names) in mapOf(
            "id" to listOf("tile_long_image", "rowMoreLongImage", "pillLongImage", "btnLongImage"),
            "drawable" to listOf("ic_long_image"),
            "string" to listOf(
                "btn_long_image", "menu_long_image", "pro_feat_long_image",
                "pro_feat_long_title", "pro_feat_long_sub", "pro_tile_long_sub"
            )
        )) for (name in names) {
            assertEquals("$type/$name must be removed", 0,
                app.resources.getIdentifier(name, type, app.packageName))
        }
    }

    @Test
    fun `shipped sources and resources contain no long image reference`() {
        // Unit tests run with the module directory (app/) as the working dir.
        val main = File("src/main")
        assertTrue("src/main is reachable from the test working dir", main.isDirectory)
        // v2 UI: 03_tools_hub — Long image is a new Build tool owned by L4. The
        // catalogue entry (and its two strings) is the only allowed mention; the
        // removed WP-08 implementation, ids and drawable stay banned below.
        val v2Catalogue = setOf(
            "src/main/java/com/scanwhirl/app/ui/kit/ToolRegistry.kt",
            "src/main/res/values/strings_l1.xml",
            "src/main/res/values-hi/strings_l1.xml"
        )
        val offenders = main.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
            .filter { it.path.replace('\\', '/') !in v2Catalogue }
            .filter { longImageText.containsMatchIn(it.readText()) || longImageText.containsMatchIn(it.name) }
            .map { it.path }
            .toList()
        assertTrue("dead Long Image references remain in: $offenders", offenders.isEmpty())
        assertFalse(File("src/main/java/com/scanwhirl/app/util/LongImage.kt").exists())
        assertFalse(File("src/main/java/com/scanwhirl/app/tools/LongImageFlow.kt").exists())
        assertFalse(File("src/main/res/drawable/ic_long_image.xml").exists())
        // Hindi/English both scanned above; assert the Hindi file really was.
        assertTrue(File("src/main/res/values-hi/strings.xml").isFile)
    }

    // ---- shared utilities and user data are preserved ------------------

    @Test
    fun `existing exported long image stays an ordinary shareable image and pages are untouched`() {
        ProGate.setTestOverride(app, false)
        val docId = seedDoc("Data safety", pages = 2)
        val doc = runBlocking { repo.listDocuments().first { it.id == docId } }
        val pageBytesBefore = doc.pageFiles.map { it.readBytes() }

        // A long image exported by an earlier version: just a JPEG in a file.
        val shareDir = File(app.cacheDir, "documents/share").apply { mkdirs() }
        val exported = File(shareDir, "Old export - long.jpg").apply { writeBytes(jpegBytes()) }
        val exportedBefore = exported.readBytes()

        openViewerMore(docId) { _, _ -> }

        assertTrue("exported file not deleted", exported.exists())
        assertArrayEquals("exported file bytes unchanged", exportedBefore, exported.readBytes())
        val after = runBlocking { repo.listDocuments().first { it.id == docId } }
        assertEquals(pageBytesBefore.size, after.pageFiles.size)
        after.pageFiles.forEachIndexed { i, f ->
            assertArrayEquals("page $i bytes unchanged", pageBytesBefore[i], f.readBytes())
        }

        // Still decodes as a normal image and still shares via ACTION_SEND.
        val dims = com.scanwhirl.app.util.ImageTools.imageSize(exported.readBytes())
        assertEquals(310 to 444, dims)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ShareUtils.shareFile(activity, exported, "image/jpeg")
                val chooser = shadowOf(activity).nextStartedActivity
                assertNotNull("share chooser launched", chooser)
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                assertEquals(Intent.ACTION_SEND, send.action)
                assertEquals("image/jpeg", send.type)
            }
        }
    }
}
