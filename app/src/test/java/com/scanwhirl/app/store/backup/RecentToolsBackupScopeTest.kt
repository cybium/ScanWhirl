package com.scanwhirl.app.store.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.Prefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T1 (owner-approved 2026-11-20): "Recent tools" is a device habit, not a setting to carry.
 *
 * The Full Backup restores only the portable choices in [FullBackup.portableSettingKeys]; the
 * recent list must never travel in settings.json, so a restored phone starts with the tools its
 * own person actually uses rather than someone else's history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecentToolsBackupScopeTest {

    @Test
    fun `the recent tools key is not in the Full Backup settings allowlist`() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val keys = FullBackup(app).portableSettingKeys()

        // Positive control: the instrument really returns the portable settings, so a missing
        // key cannot be a broken accessor.
        assertTrue("the allowlist is not empty", keys.isNotEmpty())
        assertTrue("a portable choice is still carried (quick_tools)", "quick_tools" in keys)

        assertFalse(
            "${Prefs.KEY_RECENT_TOOLS} must stay device-local",
            Prefs.KEY_RECENT_TOOLS in keys
        )
    }
}
