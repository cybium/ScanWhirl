package com.scanwhirl.app.ui.shell

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T32 (phone build 3, DEVICE_BUILD3 minor finding 3): the multi-select confirm sheet
 * said "Move 2 documents to Trash?" while the snackbar said "Moved 2 scans to Trash".
 * A scan is not the saved thing the owner keeps: the app says "documents"
 * ("दस्तावेज़" in Hindi, the same word as the confirm sheet and the count row).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrashWordingTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the moved plural says documents, not scans (EN)`() {
        assertEquals(
            "Moved 2 documents to Trash",
            app.resources.getQuantityString(R.plurals.trash_moved_many, 2, 2)
        )
        assertEquals(
            "Moved 1 document to Trash",
            app.resources.getQuantityString(R.plurals.trash_moved_many, 1, 1)
        )
        assertTrue(
            "the partial move plural also says documents",
            app.resources.getQuantityString(R.plurals.trash_moved_partial, 2, 2, 1)
                .startsWith("Moved 2 documents to Trash")
        )
        assertEquals(
            "Restored 2 documents",
            app.resources.getQuantityString(R.plurals.trash_restored, 2, 2)
        )
    }

    @Test
    fun `the moved plural uses the Hindi document word (HI)`() {
        RuntimeEnvironment.setQualifiers("hi")
        try {
            val two = app.resources.getQuantityString(R.plurals.trash_moved_many, 2, 2)
            assertTrue("Hindi plural names documents: $two", two.contains("दस्तावेज़"))
            assertFalse("Hindi plural must not say 'scans': $two", two.contains("स्कैन"))

            val one = app.resources.getQuantityString(R.plurals.trash_moved_many, 1, 1)
            assertTrue("Hindi singular names one document: $one", one.contains("दस्तावेज़"))
            assertFalse("Hindi singular must not say 'scans': $one", one.contains("स्कैन"))

            val restored = app.resources.getQuantityString(R.plurals.trash_restored, 2, 2)
            assertTrue("Hindi restore names documents: $restored", restored.contains("दस्तावेज़"))
            assertFalse("Hindi restore must not say 'scans': $restored", restored.contains("स्कैन"))

            // Same word as the row the sheet offers and the library count.
            val sheet = app.getString(R.string.sel_delete_title, 2)
            assertTrue("the sheet offers documents: $sheet", sheet.contains("दस्तावेज़"))
        } finally {
            RuntimeEnvironment.setQualifiers("")
        }
    }

    /**
     * T46: "हटाएँ" (delete) on a reversible action read as a permanent delete. The
     * destination says where the document goes, in every form the app shows.
     */
    @Test
    fun `the Hindi move names the Trash destination for one, many and partial moves`() {
        RuntimeEnvironment.setQualifiers("hi")
        try {
            val move = app.getString(R.string.trash_move)
            assertEquals("the menu action names the destination", "हटाई गई फ़ाइलों में भेजें", move)
            assertEquals(
                "the sheet action says the same as the menu",
                move,
                app.getString(R.string.shell_move_to_trash)
            )

            val one = app.getString(R.string.trash_moved_one, "किराया.pdf")
            assertTrue("one moved document names the destination: $one", one.contains("हटाई गई फ़ाइलों में भेजा गया"))

            val many = app.resources.getQuantityString(R.plurals.trash_moved_many, 2, 2)
            assertTrue("many moved documents name the destination: $many", many.contains("हटाई गई फ़ाइलों में भेजे गए"))

            val partial = app.resources.getQuantityString(R.plurals.trash_moved_partial, 2, 2, 1)
            assertTrue("a partial move names the destination: $partial", partial.contains("हटाई गई फ़ाइलों में भेजे गए"))
            assertTrue("a partial move says the rest is unchanged: $partial", partial.contains("बाकी 1"))

            val failed = app.getString(R.string.trash_move_failed)
            assertTrue("a failed move names the destination: $failed", failed.contains("हटाई गई फ़ाइलों में नहीं भेज सके"))
            assertTrue("a failed move promises nothing was deleted: $failed", failed.contains("कोई दस्तावेज़ नहीं हटा"))
        } finally {
            RuntimeEnvironment.setQualifiers("")
        }
    }

    /**
     * T46: the permanent delete must not borrow the draft's "बदलाव न रखें" (discard the
     * changes) wording — it deletes the document itself, and says so.
     */
    @Test
    fun `the Hindi permanent delete never uses the discard-changes wording`() {
        RuntimeEnvironment.setQualifiers("hi")
        try {
            val texts = listOf(
                app.getString(R.string.trash_delete_forever),
                app.getString(R.string.trash_delete_forever_title),
                app.getString(R.string.trash_delete_forever_named, "किराया.pdf"),
                app.getString(R.string.trash_delete_forever_message, "किराया.pdf"),
            )
            for (text in texts) {
                assertFalse("permanent delete must not say discard-changes: $text", text.contains("बदलाव न रखें"))
            }
            assertTrue(
                "permanent delete says the copy is gone for good: ${texts[3]}",
                texts[3].contains("हमेशा के लिए मिट जाएँगे") && texts[3].contains("वापस नहीं ला सकते")
            )
            assertTrue("the destructive action uses मिटाएँ: ${texts[0]}", texts[0].contains("मिटाएँ"))
        } finally {
            RuntimeEnvironment.setQualifiers("")
        }
    }
}
