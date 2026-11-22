package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.CompressFlow
import com.scanwhirl.app.tools.CompressIdentity
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.util.StatusLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * W2 (winner spec lane 2): the Compress set sheet must open at once and say which document it
 * is about, before any level estimate has been measured. On the base build the level sheet only
 * appeared after the four estimates finished, so a slow estimate delayed every control — the
 * size aim included.
 *
 * The real phone path is used throughout: document More -> Compress -> the set sheet. Estimates
 * are held or failed through [CompressFlow.estimateForTest], the seam this lane adds.
 */
abstract class CompressSetSheetBase {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        repo = ScanRepository(app)
        CompressFlow.estimateForTest = null
    }

    @After
    fun tearDown() {
        CompressFlow.estimateForTest = null
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    // ---- plumbing ---------------------------------------------------------

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    private fun latestDecor(): View? = ShadowDialog.getLatestDialog()?.window?.decorView

    /** Document More -> Compress; the set sheet must be on screen without waiting for estimates. */
    private fun openSetSheet(activity: android.app.Activity, timeoutMs: Long = 8_000): View {
        idle()
        assertTrue(
            "the document loads before the sheet opens",
            pollUntil {
                activity.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
            }
        )
        activity.findViewById<View>(R.id.btnMore).performClick()
        idle()
        val more = requireNotNull(ShadowDialog.getLatestDialog()) { "the More sheet opens" }
        requireNotNull(more.findViewById<View>(R.id.rowMoreCompress)) {
            "the Compress row is in the More sheet"
        }.performClick()
        assertTrue(
            "the compress set sheet opens at once",
            pollUntil(timeoutMs) {
                latestDecor()?.findViewById<View>(R.id.compressSourceName) != null
            }
        )
        idle()
        return requireNotNull(latestDecor())
    }

    private fun levelRows(decor: View): List<RadioButton> {
        val group = requireNotNull(decor.findViewById<RadioGroup>(R.id.levelGroup)) {
            "the four level rows are in the sheet"
        }
        return (0 until group.childCount).map { group.getChildAt(it) as RadioButton }
    }

    private val levelRes = listOf(
        R.string.compress_level_low,
        R.string.compress_level_medium,
        R.string.compress_level_high,
        R.string.compress_level_ultra
    )

    private fun levelName(index: Int): String = app.getString(levelRes[index])

    private fun pendingText(index: Int): String =
        app.getString(R.string.compress_est_pending, levelName(index))

    private fun estimateText(index: Int, bytes: Long): String =
        app.getString(R.string.compress_est_item, levelName(index), StatusLine.formatBytes(bytes))

    private fun failedText(index: Int): String =
        app.getString(R.string.compress_est_failed, levelName(index))

    private fun metaFor(doc: ScanDocument): String {
        val bytes = if (doc.pdfFile.exists() && doc.pdfFile.length() > 0) {
            doc.pdfFile.length()
        } else {
            doc.pageFiles.sumOf { it.length() }
        }
        return app.resources.getQuantityString(
            R.plurals.page_count_and_date, doc.pageCount, doc.pageCount,
            StatusLine.formatBytes(bytes)
        )
    }

    private fun pollStartedResult(
        activity: android.app.Activity,
        timeoutMs: Long = 20_000
    ): Intent? {
        var started: Intent? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            started = shadowOf(activity).peekNextStartedActivity()
            if (started != null) break
            Thread.sleep(20)
        }
        idle()
        return started ?: shadowOf(activity).peekNextStartedActivity()
    }

    private fun viewer(id: String) = ActivityScenario.launch<DocumentViewerActivity>(
        DocumentViewerActivity.intent(app, id)
    )

    // ---- the sheet opens first, identifies the document -------------------

    @Test
    fun `the set sheet opens with the exact document before any estimate`() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        try {
            CompressFlow.estimateForTest = { _, _ ->
                entered.countDown()
                gate.await(20, TimeUnit.SECONDS)
                140_000L
            }
            val id = DocFixtures.saved(repo, app, pages = 2, name = "Electricity bill Oct")
            val doc = requireNotNull(repo.getDocument(id))
            viewer(id).use { scenario ->
                scenario.onActivity { activity ->
                    val decor = openSetSheet(activity)
                    assertTrue(
                        "the estimate has started (and is held back)",
                        pollUntil(5_000) { entered.count == 0L }
                    )
                    assertEquals(
                        "the sheet names the document it will compress",
                        "Electricity bill Oct",
                        decor.findViewById<TextView>(R.id.compressSourceName).text.toString()
                    )
                    assertEquals(
                        "and reports its own page count and measured size",
                        metaFor(doc),
                        decor.findViewById<TextView>(R.id.compressSourceMeta).text.toString()
                    )
                    val rows = levelRows(decor)
                    assertEquals("four levels are offered", 4, rows.size)
                    rows.forEachIndexed { index, row ->
                        assertEquals(
                            "row $index says it is still estimating, not a number it does not have",
                            pendingText(index),
                            row.text.toString()
                        )
                    }
                    // Both aims work from the first frame.
                    assertTrue(
                        "the quality aim is usable while estimates run",
                        decor.findViewById<View>(R.id.compressModeQuality).isEnabled
                    )
                    decor.findViewById<View>(R.id.compressModeTarget).performClick()
                    idle()
                    assertEquals(
                        "the size field appears at once",
                        View.VISIBLE,
                        decor.findViewById<View>(R.id.compressTargetBlock).visibility
                    )
                    assertTrue(
                        "the Compress button is not gated on an estimate",
                        decor.findViewById<View>(R.id.swSheetPrimary).isEnabled
                    )
                }
            }
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun `a typed target runs while the estimates are still held`() {
        val gate = CountDownLatch(1)
        try {
            CompressFlow.estimateForTest = { _, _ ->
                gate.await(20, TimeUnit.SECONDS)
                140_000L
            }
            val id = DocFixtures.saved(repo, app, pages = 2, name = "Marksheet 12th")
            viewer(id).use { scenario ->
                scenario.onActivity { activity ->
                    val decor = openSetSheet(activity)
                    decor.findViewById<View>(R.id.compressModeTarget).performClick()
                    idle()
                    decor.findViewById<EditText>(R.id.editCompressTarget).setText("200 KB")
                    idle()
                    decor.findViewById<View>(R.id.swSheetPrimary).performClick()
                    val started = pollStartedResult(activity)
                    assertNotNull("the target compress starts with no estimate at all", started)
                    assertEquals(
                        "the typed size is the one that runs (200 KB = 200,000 bytes)",
                        200_000L,
                        started!!.getLongExtra("target_bytes", 0L)
                    )
                }
            }
        } finally {
            gate.countDown()
        }
    }

    // ---- estimates arrive one at a time -----------------------------------

    @Test
    fun `each estimate appears as soon as it is measured`() {
        val gates = List(4) { CountDownLatch(1) }
        try {
            CompressFlow.estimateForTest = { _, index ->
                gates[index].await(20, TimeUnit.SECONDS)
                120_000L - index * 10_000L
            }
            val id = DocFixtures.saved(repo, app, pages = 3, name = "Rent receipt")
            viewer(id).use { scenario ->
                scenario.onActivity { activity ->
                    val decor = openSetSheet(activity)
                    val rows = levelRows(decor)
                    assertEquals("nothing is measured yet", pendingText(0), rows[0].text.toString())

                    gates[0].countDown()
                    assertTrue(
                        "the first measured level fills its own row",
                        pollUntil(10_000) {
                            rows[0].text.toString() == estimateText(0, 120_000L)
                        }
                    )
                    assertEquals(
                        "and a level that is still measuring keeps saying so",
                        pendingText(1),
                        rows[1].text.toString()
                    )

                    (1..3).forEach { gates[it].countDown() }
                    assertTrue(
                        "every level lands on its own row",
                        pollUntil(10_000) {
                            rows[3].text.toString() == estimateText(3, 90_000L)
                        }
                    )
                    assertEquals(estimateText(1, 110_000L), rows[1].text.toString())
                    assertEquals(estimateText(2, 100_000L), rows[2].text.toString())
                    assertEquals(estimateText(0, 120_000L), rows[0].text.toString())
                }
            }
        } finally {
            gates.forEach { it.countDown() }
        }
    }

    @Test
    fun `a level that cannot be measured says so and still runs`() {
        CompressFlow.estimateForTest = { _, index ->
            if (index == 2) throw IllegalStateException("that page could not be re-encoded")
            95_000L
        }
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Aadhaar front - FAKE")
        viewer(id).use { scenario ->
            scenario.onActivity { activity ->
                val decor = openSetSheet(activity)
                val rows = levelRows(decor)
                assertTrue(
                    "the failed level says it could not be measured",
                    pollUntil(10_000) { rows[2].text.toString() == failedText(2) }
                )
                assertEquals(
                    "the other levels still report their own sizes",
                    estimateText(0, 95_000L),
                    rows[0].text.toString()
                )
                // A failed estimate is not a gate: the level still runs.
                rows[2].performClick()
                idle()
                decor.findViewById<View>(R.id.swSheetPrimary).performClick()
                assertNotNull(
                    "the chosen level runs even though its estimate failed",
                    pollStartedResult(activity)
                )
            }
        }
    }

    // ---- one document's estimate never lands in another's sheet -----------

    @Test
    fun `a slow estimate for one document never lands in another document's sheet`() {
        val releaseA = CountDownLatch(1)
        try {
            CompressFlow.estimateForTest = { doc, _ ->
                if (doc.name == "Aadhaar front - FAKE") {
                    releaseA.await(20, TimeUnit.SECONDS)
                    111_000L
                } else {
                    222_000L
                }
            }
            val aId = DocFixtures.saved(repo, app, pages = 2, name = "Aadhaar front - FAKE")
            val bId = DocFixtures.saved(repo, app, pages = 2, name = "Marksheet 12th")
            viewer(aId).use { aScenario ->
                var decorA: View? = null
                aScenario.onActivity { activity -> decorA = openSetSheet(activity) }
                val aRows = levelRows(requireNotNull(decorA))
                viewer(bId).use { bScenario ->
                    bScenario.onActivity { activity ->
                        val decorB = openSetSheet(activity)
                        assertEquals(
                            "the second sheet names the second document",
                            "Marksheet 12th",
                            decorB.findViewById<TextView>(R.id.compressSourceName).text.toString()
                        )
                        val bRows = levelRows(decorB)
                        assertTrue(
                            "B's own estimate lands in B's sheet",
                            pollUntil(10_000) {
                                bRows[0].text.toString() == estimateText(0, 222_000L)
                            }
                        )
                        // Release A's held estimate while B is the sheet on screen.
                        releaseA.countDown()
                        assertTrue(
                            "A's estimate does reach A's own sheet (the seam really resumed)",
                            pollUntil(10_000) {
                                aRows[0].text.toString() == estimateText(0, 111_000L)
                            }
                        )
                        assertEquals(
                            "B keeps B's measured size",
                            estimateText(0, 222_000L),
                            bRows[0].text.toString()
                        )
                        assertFalse(
                            "A's size never appears in B's sheet",
                            bRows.any {
                                it.text.toString().contains(StatusLine.formatBytes(111_000L))
                            }
                        )
                        assertEquals(
                            "and B's target block still belongs to B",
                            "Marksheet 12th",
                            decorB.findViewById<TextView>(R.id.compressSourceName).text.toString()
                        )
                    }
                }
            }
        } finally {
            releaseA.countDown()
        }
    }

    // ---- one submit, and closing runs nothing -----------------------------

    @Test
    fun `a double tap on Compress starts one job`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Electricity bill Oct")
        viewer(id).use { scenario ->
            scenario.onActivity { activity ->
                val before = repo.listDocuments().size
                val decor = openSetSheet(activity)
                val primary = decor.findViewById<View>(R.id.swSheetPrimary)
                primary.performClick()
                // The sheet has already closed; a second tap must not start a second run.
                primary.performClick()
                idle()
                val started = pollStartedResult(activity)
                assertNotNull("the compress starts once", started)
                // Consume the one legitimate result screen.
                shadowOf(activity).getNextStartedActivity()
                assertTrue(
                    "exactly one compressed copy appears",
                    pollUntil { repo.listDocuments().size == before + 1 }
                )
                repeat(10) {
                    idle()
                    Thread.sleep(20)
                }
                assertEquals(
                    "a second tap writes no second copy",
                    before + 1,
                    repo.listDocuments().size
                )
                assertNull(
                    "and opens no second result screen",
                    shadowOf(activity).peekNextStartedActivity()
                )
            }
        }
    }

    @Test
    fun `X and Back produce no output`() {
        val gate = CountDownLatch(1)
        try {
            CompressFlow.estimateForTest = { _, _ ->
                gate.await(20, TimeUnit.SECONDS)
                90_000L
            }
            val id = DocFixtures.saved(repo, app, pages = 2, name = "Rent receipt")
            viewer(id).use { scenario ->
                scenario.onActivity { activity ->
                    val before = repo.listDocuments().size

                    // SH2: OLD = swSheetSecondary (Cancel). NEW = swSheetClose (X) only.
                    var decor = openSetSheet(activity)
                    decor.findViewById<View>(R.id.swSheetClose).performClick()
                    idle()
                    assertTrue(
                        "X closes the sheet",
                        ShadowDialog.getLatestDialog()?.isShowing != true
                    )

                    // Back
                    decor = openSetSheet(activity)
                    val sheet = requireNotNull(ShadowDialog.getLatestDialog())
                    sheet.onBackPressed()
                    idle()
                    assertTrue("Back closes the sheet", !sheet.isShowing)

                    // Let the held estimates finish: a dismissed sheet still runs nothing.
                    gate.countDown()
                    repeat(20) {
                        idle()
                        Thread.sleep(20)
                    }
                    assertNull(
                        "a cancelled sheet starts no result",
                        shadowOf(activity).peekNextStartedActivity()
                    )
                    assertEquals(
                        "a cancelled sheet writes nothing",
                        before,
                        repo.listDocuments().size
                    )
                }
            }
        } finally {
            gate.countDown()
        }
    }

    // ---- inline size checks -----------------------------------------------

    @Test
    fun `blank, zero, unreadable and overflowing sizes are rejected inline`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Electricity bill Oct")
        val before = repo.listDocuments().size
        viewer(id).use { scenario ->
            scenario.onActivity { activity ->
                val decor = openSetSheet(activity)
                decor.findViewById<View>(R.id.compressModeTarget).performClick()
                idle()
                val field = decor.findViewById<EditText>(R.id.editCompressTarget)
                val error = decor.findViewById<TextView>(R.id.compressTargetError)
                val cases = listOf(
                    "" to R.string.compress_target_required,
                    "0" to R.string.compress_target_zero,
                    "0 KB" to R.string.compress_target_zero,
                    "20KBg" to R.string.resize_invalid_target,
                    "-5" to R.string.resize_invalid_target,
                    "999999999999999999999 MB" to R.string.compress_target_too_large
                )
                for ((input, message) in cases) {
                    field.setText(input)
                    idle()
                    decor.findViewById<View>(R.id.swSheetPrimary).performClick()
                    idle()
                    assertEquals(
                        "for '$input' the sheet names the problem",
                        app.getString(message),
                        error.text.toString()
                    )
                    assertEquals(
                        "for '$input' the error is visible inline",
                        View.VISIBLE,
                        error.visibility
                    )
                    assertTrue(
                        "for '$input' the sheet stays open to fix it",
                        ShadowDialog.getLatestDialog()?.isShowing == true
                    )
                }
                assertNull(
                    "no rejected size may start a run",
                    pollStartedResult(activity, timeoutMs = 1_000)
                )
                assertEquals("no copy may be written", before, repo.listDocuments().size)
                assertNull(
                    "no toast may carry the only explanation",
                    ShadowToast.getTextOfLatestToast()
                )
            }
        }
    }

    // ---- the identity is private where it must be -------------------------

    @Test
    fun `a locked or Vault document keeps its name and pages but never a page image`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Protected rent agreement")
        val doc = requireNotNull(repo.getDocument(id))
        assertTrue(
            "an ordinary document may show its real first page",
            CompressIdentity.of(doc).showsPageImage
        )
        val locked = CompressIdentity.of(doc.copy(isLocked = true))
        assertFalse("a locked document shows no page image", locked.showsPageImage)
        assertEquals("but it is still identified", "Protected rent agreement", locked.name)
        assertEquals("with its page count", 2, locked.pageCount)
        assertTrue("and its measured size", locked.sizeBytes > 0L)

        val vaulted = CompressIdentity.of(doc.copy(isVaulted = true))
        assertFalse("a Vault document shows no page image", vaulted.showsPageImage)
        assertEquals("and is still the document it names", "Protected rent agreement", vaulted.name)
    }
}

/** The phone the sweep used: 360dp, English. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class CompressSetSheetEnTest : CompressSetSheetBase()

/** The same phone in Hindi, where every label is longer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class CompressSetSheetHiTest : CompressSetSheetBase()
