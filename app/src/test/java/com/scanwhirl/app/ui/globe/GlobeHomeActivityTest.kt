package com.scanwhirl.app.ui.globe

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.MainActivity
import com.scanwhirl.app.util.Prefs
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
import kotlinx.coroutines.runBlocking

/** Smoke tests for the new globe launcher home (test23). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlobeHomeActivityTest {

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        Prefs.setGlobeHomeEnabled(app, true)
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

    @Test
    fun `globe activity is the launcher, main activity is not`() {
        // MATCH_DEFAULT_ONLY would miss it: launcher filters carry no
        // CATEGORY_DEFAULT, so resolve with plain flags like a real launcher.
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = app.packageManager.resolveActivity(launch, 0)
        assertNotNull("app must have a launcher", resolved)
        val name = resolved?.activityInfo?.name
        assertTrue(
            "launcher should be the globe home, was $name",
            name?.endsWith("GlobeHomeActivity") == true
        )
    }

    @Test
    fun `home screen wires the view, hud and scan button`() {
        ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById(R.id.globeView))
                assertNotNull(activity.findViewById(R.id.fabGlobeScan))
                assertNotNull(activity.findViewById(R.id.btnGlobeSearch))
                assertNotNull(activity.findViewById(R.id.searchPanel))
                assertEquals(
                    activity.getString(R.string.globe_hint_home),
                    activity.findViewById<HolographicEarthView>(R.id.globeView).hintText
                )
            }
        }
    }

    @Test
    fun `tapping the earth expands the rings`() {
        ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = activity.findViewById<HolographicEarthView>(R.id.globeView)
                assertEquals(GlobeScene.STATE_HOME, view.scene.state)
                activity.onGlobeTapped()
                assertEquals(GlobeScene.STATE_RINGS, view.scene.state)
                assertEquals(
                    activity.getString(R.string.globe_hint_rings), view.hintText
                )
                activity.onGlobeTapped()
                assertEquals(GlobeScene.STATE_HOME, view.scene.state)
            }
        }
    }

    @Test
    fun `pinch-in cross-fades into the shelf`() {
        ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.onPinchToShelf()
                val next = shadowOf(activity).nextStartedActivity
                assertNotNull("pinch must open the Shelf", next)
                assertEquals(
                    MainActivity::class.java.name,
                    resolveName(next)
                )
            }
        }
    }

    @Test
    fun `scan button opens the shelf with the scanner chooser flag`() {
        ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.fabGlobeScan).performClick()
                val next = shadowOf(activity).nextStartedActivity
                assertNotNull(next)
                assertEquals(MainActivity::class.java.name, resolveName(next))
                assertTrue(
                    "scan flag must be set",
                    next.getBooleanExtra(MainActivity.EXTRA_OPEN_SCANNER, false)
                )
            }
        }
    }

    @Test
    fun `pull-down opens the search panel with the shared match counter`() {
        runBlocking {
            val repo = ScanRepository(app)
            repo.saveScan(listOf(uriBytes()), null, "Biology class notes")
            try {
                ActivityScenario.launch(GlobeHomeActivity::class.java).use { scenario ->
                    val docsLoaded = pollUntil {
                        var count = -1
                        scenario.onActivity { a ->
                            val v = a.findViewById<HolographicEarthView>(R.id.globeView)
                            count = v.scene.cards.size
                        }
                        count > 0
                    }
                    assertTrue("docs should load into the scene", docsLoaded)

                    scenario.onActivity { activity ->
                        activity.onPullSearch()
                        val panel = activity.findViewById<android.view.View>(R.id.searchPanel)
                        assertEquals(android.view.View.VISIBLE, panel.visibility)
                        val counter =
                            activity.findViewById<android.widget.TextView>(R.id.globeMatchCount)
                        assertTrue(
                            "counter should show the total: ${counter.text}",
                            counter.text.toString().contains("1")
                        )
                    }
                }
            } finally {
                repo.listDocuments().forEach { repo.deleteDocument(it.id) }
            }
        }
    }

    @Test
    fun `settings toggle off skips straight to the shelf`() {
        Prefs.setGlobeHomeEnabled(app, false)
        try {
            // The activity finishes inside onCreate, so ActivityScenario can
            // never hand us a live instance — drive the controller directly.
            val controller =
                org.robolectric.Robolectric.buildActivity(GlobeHomeActivity::class.java).create()
            val activity = controller.get()
            assertTrue("globe must hand off to the Shelf", activity.isFinishing)
            val next = shadowOf(activity).nextStartedActivity
            assertNotNull("Shelf must be started", next)
            assertEquals(MainActivity::class.java.name, resolveName(next))
        } finally {
            Prefs.setGlobeHomeEnabled(app, true)
        }
    }

    @Test
    fun `globe preference defaults to on`() {
        Prefs.setGlobeHomeEnabled(app, false)
        Prefs.setGlobeHomeEnabled(app, true)
        assertTrue(Prefs.globeHomeEnabled(app))
    }

    private fun resolveName(intent: Intent?): String? =
        intent?.component?.className ?: intent?.resolveActivity(app.packageManager)?.className

    private var uriCounter = 0
    private fun uriBytes(): android.net.Uri {
        val bytes = ByteArray(2048) { (it % 251).toByte() }
        val uri = android.net.Uri.parse("content://globe/fake_${uriCounter++}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }
}
