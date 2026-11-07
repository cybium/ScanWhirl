package com.scanwhirl.app.ui

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.StatusLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * test26 T3.10 (complaint #14) — the *screen* must show the truth: the status
 * line tracks the unfiltered library through load, add and delete, and a
 * category filter never changes it.
 *
 * Red-proofed first: on T3.9's code these fail because `updateStatusLine()`
 * read `adapter.currentList` and was never called from `applyList()`.
 * `StatusLineTest` (util) only covers the formatter — this file covers the wiring.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StatusLineScreenTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
        com.scanwhirl.app.util.Prefs.setShowGreeting(app, true)
    }

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://status_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private fun jpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(310, 444, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Saves one page under an explicit category ([null] = rules decide). */
    private fun seed(name: String, category: String?): String = runBlocking {
        val uri = contentUri(jpegBytes())
        repo.saveScan(listOf(uri), null, name, category = category).id
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

    private fun statusOf(activity: Activity): String =
        activity.findViewById<android.widget.TextView>(R.id.statusLine).text.toString()

    /** Same bytes the card shows (ScanListAdapter uses pdfFile.length() too). */
    private fun totalBytes(): Long = repo.listDocuments().sumOf { it.pdfFile.length() }

    private fun rowCount(activity: MainActivity): Int =
        activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
            .adapter!!.itemCount

    /** Robolectric: a stop/start cycle fires onResume (the refresh trigger). */
    private fun reenter(scenario: ActivityScenario<MainActivity>) {
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
    }

    // ---- T3.10: the screen, not just the formatter -----------------------

    @Test
    fun `status line reads one doc with the card's size after first load`() {
        seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("list lands", pollUntil { rowCount(activity) == 1 })
                val status = statusOf(activity)
                assertTrue("says 1 DOC, was: $status", status.contains("1 DOC"))
                val want = StatusLine.formatBytes(totalBytes())
                assertTrue("says the card's size ($want), was: $status", status.contains(want))
            }
        }
    }

    @Test
    fun `status line counts the second doc after it is added`() {
        seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("first doc lands", pollUntil { rowCount(activity) == 1 })
                assertTrue("says 1 DOC first", pollUntil { statusOf(activity).contains("1 DOC") })

                seed("Electricity bill", CategoryRules.RECEIPTS)
                reenter(scenario) // onResume -> refreshList (the real trigger)

                assertTrue(
                    "says 2 DOCS after add, was: ${statusOf(activity)}",
                    pollUntil { statusOf(activity).contains("2 DOCS") }
                )
            }
        }
    }

    @Test
    fun `deleting every doc shows 0 DOCS and the empty state`() {
        val a = seed("Lecture notes", CategoryRules.STUDY)
        val b = seed("Electricity bill", CategoryRules.RECEIPTS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("2 docs land", pollUntil { rowCount(activity) == 2 })
                assertTrue("says 2 DOCS first", pollUntil { statusOf(activity).contains("2 DOCS") })

                repo.deleteDocument(a)
                repo.deleteDocument(b)
                reenter(scenario)

                assertTrue(
                    "says 0 DOCS after delete, was: ${statusOf(activity)}",
                    pollUntil { statusOf(activity).contains("0 DOCS") }
                )
                assertTrue(
                    "empty state shows with it",
                    pollUntil {
                        activity.findViewById<android.view.View>(R.id.emptyState).visibility ==
                            android.view.View.VISIBLE
                    }
                )
            }
        }
    }

    @Test
    fun `category filter never changes the status line`() {
        seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("doc lands", pollUntil { rowCount(activity) == 1 })
                assertTrue("says 1 DOC", pollUntil { statusOf(activity).contains("1 DOC") })

                // Receipts is empty -> filtered list 0, unfiltered library still 1.
                val receipts = activity.findViewById<com.google.android.material.chip.ChipGroup>(
                    R.id.chipGroup
                )
                val chip = (0 until receipts.childCount)
                    .map { receipts.getChildAt(it) as com.google.android.material.chip.Chip }
                    .first { it.tag as String? == CategoryRules.RECEIPTS }
                chip.performClick()
                assertTrue("filter lands", pollUntil { rowCount(activity) == 0 })

                val status = statusOf(activity)
                assertTrue("status stays at 1 DOC while filtered, was: $status", status.contains("1 DOC"))
            }
        }
    }
}
