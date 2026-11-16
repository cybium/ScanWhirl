package com.scanwhirl.app.util

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Category chips must read in the UI language while the value written to `meta.txt`
 * stays the English key (Study, Receipts, … Unsorted). [CategoryRules.label] is the
 * stored value; [CategoryLabels.display] is what the user reads.
 *
 * The Hindi case uses a configuration context instead of a second Robolectric sandbox,
 * so this class stays cheap in the full suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CategoryLabelsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun hindiContext(): Context {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale("hi"))
        }
        return context.createConfigurationContext(config)
    }

    @Test
    fun `english shows the stored names`() {
        assertEquals("Unsorted", CategoryLabels.display(context, CategoryRules.UNSORTED))
        assertEquals("Study", CategoryLabels.display(context, CategoryRules.STUDY))
        assertEquals("Receipts", CategoryLabels.display(context, CategoryRules.RECEIPTS))
        assertEquals("IDs", CategoryLabels.display(context, CategoryRules.IDS))
        assertEquals("Forms", CategoryLabels.display(context, CategoryRules.FORMS))
        assertEquals("Other", CategoryLabels.display(context, CategoryRules.OTHER))
    }

    @Test
    fun `hindi shows translated names and the stored value never changes`() {
        val hi = hindiContext()
        for (stored in CategoryRules.ALL_WITH_UNSORTED) {
            assertEquals(stored, CategoryRules.label(stored))
            assertTrue("$stored must stay valid", CategoryRules.isValid(stored))
        }
        assertEquals("बिना श्रेणी", CategoryLabels.display(hi, CategoryRules.UNSORTED))
        assertEquals("पढ़ाई", CategoryLabels.display(hi, CategoryRules.STUDY))
        assertEquals(hi.getString(R.string.cat_label_receipts), CategoryLabels.display(hi, CategoryRules.RECEIPTS))
        assertEquals(hi.getString(R.string.cat_label_ids), CategoryLabels.display(hi, CategoryRules.IDS))
        assertEquals(hi.getString(R.string.cat_label_forms), CategoryLabels.display(hi, CategoryRules.FORMS))
        assertEquals(hi.getString(R.string.cat_label_other), CategoryLabels.display(hi, CategoryRules.OTHER))
        // The Hindi resources are real translations, not the English text.
        assertTrue(CategoryLabels.display(hi, CategoryRules.STUDY) != CategoryRules.STUDY)
        assertEquals("Unsorted", CategoryRules.UNSORTED)
        assertEquals("Study", CategoryRules.STUDY)
    }

    @Test
    fun `an unknown value is shown exactly as stored`() {
        assertEquals("My own tag", CategoryLabels.display(context, "My own tag"))
        assertEquals("", CategoryLabels.display(context, null))
    }
}
