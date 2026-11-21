package com.scanwhirl.app.ui

import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.PendingScans
import com.scanwhirl.app.util.ImportFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * C2 item 3: "n चुने" is wrong for one page in Hindi ("1 चुना"). Both selection counters
 * must use the R.plurals.selected_count quantity string. The ScanReview counter is checked
 * end-to-end in Hindi; MergePdfActivity uses the same call.
 *
 * RED on base 282630d1: base calls getString(R.string.selected_count, n) -> "1 चुने".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h740dp-xhdpi")
class SelectedCountPluralTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the Hindi plural has distinct one and other forms`() {
        val one = app.resources.getQuantityString(R.plurals.selected_count, 1, 1)
        val other = app.resources.getQuantityString(R.plurals.selected_count, 2, 2)
        assertEquals("1 चुना", one)
        assertEquals("2 चुने", other)
        assertNotEquals(one, other)
    }

    @Test
    fun `ScanReview uses the singular and plural Hindi forms`() {
        val dir = PendingScans.create(app)
        try {
            repeat(2) { File(dir, "page_%03d.jpg".format(it)).writeBytes(ImportFixtures.image(width = 120 + it)) }
            ActivityScenario.launch<ScanReviewActivity>(
                ScanReviewActivity.intent(app, dir.path, null, "Doc")
            ).use { scenario ->
                scenario.onActivity { activity ->
                    shadowOf(Looper.getMainLooper()).idle()
                    val counter = activity.findViewById<TextView>(R.id.selCount)

                    activity.toggleSelect(0)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals("1 चुना", counter.text.toString())

                    activity.toggleSelect(1)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals("2 चुने", counter.text.toString())
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
