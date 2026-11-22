package com.scanwhirl.app.cz

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lane Z, item 2 source-side companion. The full gate's "Merged release manifest
 * privacy gate" step (tools/release/check_release_manifest.py) checks the AGP-merged
 * release manifest on every full run; this fast test only guards the pieces this
 * lane owns: the dependency line and the existing strip markers.
 */
class CzReviewManifestTest {

    @Test
    fun `in-app review dependency is declared at the audited version`() {
        // Gradle's unit-test working directory is the app module (as in ManifestPrivacyTest).
        val gradle = File("build.gradle.kts")
        assertTrue("app/build.gradle.kts is missing", gradle.isFile)
        val text = gradle.readText()
        assertTrue(
            "review-ktx 2.0.2 must be the audited, permission-free version",
            text.contains("com.google.android.play:review-ktx:2.0.2")
        )
    }

    @Test
    fun `source manifest still strips network permissions`() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml is missing", manifest.isFile)
        val text = manifest.readText()
        for (permission in listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")) {
            assertTrue(
                "$permission must still be declared with tools:node=remove",
                text.contains(permission) && text.contains("tools:node=\"remove\"")
            )
        }
    }
}
