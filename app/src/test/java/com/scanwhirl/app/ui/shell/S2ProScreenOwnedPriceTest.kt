package com.scanwhirl.app.ui.shell

import android.app.Activity
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S2 item 9: an already-owned (active) Pro screen has no purchase-price row, so a null Play price
 * must not show a purchase-price error on it. The error belongs to the buy screen only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S2ProScreenOwnedPriceTest {

    private var controller: ActivityController<Activity>? = null

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
    }

    private fun screen(active: Boolean): Pair<Activity, ProScreen> {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller!!.get()
        val callbacks = object : ProScreen.Callbacks {
            override fun onClose() = Unit
            override fun onBuy() = Unit
            override fun onRestore() = Unit
            override fun onSeeAllProTools() = Unit
        }
        // ApplicationProvider keeps the call site honest about which context is used.
        assertNotNull(ApplicationProvider.getApplicationContext<android.app.Application>())
        val pro = ProScreen(activity, callbacks, active = active)
        activity.setContentView(pro)
        return activity to pro
    }

    @Test
    fun `owned Pro screen with no Play price shows no purchase-price error`() {
        val (activity, pro) = screen(active = true)
        pro.setPrice(null)
        val status = activity.findViewById<View>(R.id.swProPriceStatus) as TextView
        assertEquals(
            "an already-owned screen must not show a price error for a missing buy price",
            View.GONE, status.visibility
        )
    }

    @Test
    fun `buy screen with no Play price still shows the honest status`() {
        val (activity, pro) = screen(active = false)
        pro.setPrice(null)
        val status = activity.findViewById<View>(R.id.swProPriceStatus) as TextView
        assertEquals(
            "the missing-price status must stay honest on the purchase screen",
            View.VISIBLE, status.visibility
        )
    }
}
