package com.scanwhirl.app.util

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * test25 Phase 4: the ambient-motion gate. Each branch of
 * `scale && interactive && fingerprint` gets its own test with the other two
 * conditions forced green (the `fingerprint` parameter exists exactly so the
 * first two branches can be proven under Robolectric, where the real
 * fingerprint is always "robolectric").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SwMotionTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        // Baseline: animations on, device interactive.
        Settings.Global.putFloat(
            app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
    }

    @Test
    fun `animator scale zero blocks ambient motion`() {
        // Fingerprint forced non-Robolectric so the scale branch decides.
        Settings.Global.putFloat(
            app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f
        )
        assertFalse(
            "animations disabled must block ambient motion",
            SwMotion.ambientAllowed(app, fingerprint = "test/device")
        )

        Settings.Global.putFloat(
            app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
        assertTrue(
            "scale back on must allow ambient motion",
            SwMotion.ambientAllowed(app, fingerprint = "test/device")
        )
    }

    @Test
    fun `non-interactive device blocks ambient motion`() {
        val shadowPm = shadowOf(
            app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        )
        shadowPm.setIsInteractive(false)
        assertFalse(
            "screen off must block ambient motion",
            SwMotion.ambientAllowed(app, fingerprint = "test/device")
        )

        shadowPm.setIsInteractive(true)
        assertTrue(
            "interactive device must allow ambient motion",
            SwMotion.ambientAllowed(app, fingerprint = "test/device")
        )
    }

    @Test
    fun `robolectric fingerprint blocks ambient motion on its own`() {
        // Scale and interactive are both green (setUp + shadow default):
        // only the fingerprint can make this false — which is what keeps the
        // whole suite deterministic (no pulse, no drift, no shimmer).
        assertFalse(
            "the robolectric fingerprint must block ambient motion",
            SwMotion.ambientAllowed(app)
        )
    }
}
