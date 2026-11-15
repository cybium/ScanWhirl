package com.scanwhirl.app.util

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Security audit (merged manifest): the app is offline, has no backup surface and exposes a
 * single entry point. This reads the MERGED manifest of the test APK, so a library that adds a
 * permission or an exported component fails the gate too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestPrivacyTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun requestedPermissions(): Set<String> =
        app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet().orEmpty()

    @Test
    fun `the merged manifest requests no network permission`() {
        val permissions = requestedPermissions()
        assertFalse("INTERNET must never be requested", permissions.contains("android.permission.INTERNET"))
        assertFalse(
            "ACCESS_NETWORK_STATE must never be requested",
            permissions.contains("android.permission.ACCESS_NETWORK_STATE")
        )
        assertTrue(permissions.contains("android.permission.USE_BIOMETRIC"))
        // androidx.biometric also merges the deprecated (normal) USE_FINGERPRINT; it grants no
        // capability of its own and is kept by the library's manifest.
        // The test build adds its own <package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION.
        val unexpected = permissions.filterNot {
            it == "android.permission.USE_BIOMETRIC" ||
                it == "android.permission.USE_FINGERPRINT" ||
                it == "android.permission.WRITE_EXTERNAL_STORAGE" ||
                it.startsWith(app.packageName + ".")
        }
        assertTrue("unexpected permissions: $unexpected", unexpected.isEmpty())
    }

    @Test
    fun `cloud backup and device transfer are off`() {
        val info = app.applicationInfo
        assertEquals(
            "allowBackup must stay false (no cloud backup, no device-to-device transfer)",
            0,
            info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP
        )
    }

    @Test
    fun `only the launcher activity is exported`() {
        val activities = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES)
            .activities.orEmpty()
        val exported = activities.filter { it.exported }.map { it.name }
        // The launcher is an activity-alias pair (Cyan/Gold); nothing else may be
        // exported. PackageManager resolves only the enabled alias as a launcher.
        assertTrue("exported: $exported", exported.all { it.contains("ShellActivity") })
        assertTrue("exported: $exported", exported.size in 1..3)
        val launchers = app.packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .setPackage(app.packageName),
            0
        )
        assertEquals("exactly one launcher entry point is enabled", 1, launchers.size)
        assertTrue(
            "the launcher is the shell: ${launchers.map { it.activityInfo.name }}",
            launchers.single().activityInfo.name.contains("ShellActivity")
        )
    }

    @Test
    fun `the file provider is not exported`() {
        val providers = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PROVIDERS)
            .providers.orEmpty()
            .filter { it.name.contains("FileProvider") }
        assertTrue("the FileProvider is declared", providers.isNotEmpty())
        assertTrue("no provider is exported", providers.none { it.exported })
    }
}
