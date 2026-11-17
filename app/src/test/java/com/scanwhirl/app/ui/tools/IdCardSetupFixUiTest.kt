package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Owner X3: the generic tool setup screen had a second ID-card implementation that hard-required
 * a back image. IdCardActivity is the only ID-card path, and no tile routes to the setup branch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class IdCardSetupFixUiTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the setup screen has no front and back ID card pickers`() {
        val activity = Robolectric
            .buildActivity(ToolSetupActivity::class.java, ToolSetupActivity.intent(app, "id_card"))
            .setup()
            .get()
        idle()

        assertNull("no front picker on the generic setup", activity.findViewById<View>(R.id.toolSetupFront))
        assertNull("no back picker on the generic setup", activity.findViewById<View>(R.id.toolSetupBack))

        activity.findViewById<View>(R.id.toolSetupRun)!!.performClick()
        idle()
        val error = activity.findViewById<TextView>(R.id.toolSetupError)
        assertNotNull(error)
        assertEquals(
            "nothing asks for a back image any more",
            activity.getString(R.string.tools_setup_nothing),
            error.text.toString()
        )
    }

    @Test
    fun `the ID card tile opens only the dedicated activity`() {
        val host = object : ContextWrapper(app), ToolHost {
            var started: Intent? = null
            override fun startActivity(intent: Intent) {
                started = intent
            }

            override fun open(tool: SwTool) {
                throw AssertionError("the ID card tile must not open the generic setup")
            }

            override fun comingSoon(tool: SwTool) {
                throw AssertionError("the ID card tile is not Coming soon")
            }
        }
        val tool = requireNotNull(ToolRegistry.byId("id_card"))
        tool.launch(host, tool)
        assertEquals(
            com.scanwhirl.app.ui.tools.IdCardActivity::class.java.name,
            host.started?.component?.className
        )
    }
}
