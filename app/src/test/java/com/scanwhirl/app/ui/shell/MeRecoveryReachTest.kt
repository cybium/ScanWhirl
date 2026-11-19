package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.TrashActivity
import com.scanwhirl.app.ui.backup.BackupActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI-W8: from Me, Trash and Backup & restore are recovery surfaces a worried person
 * reaches first. Each must open in one tap from its named row, and the rows must stay
 * readable — full labels, no ellipsis, a 48dp target, a TalkBack name — at 200 % in
 * English and Hindi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class MeRecoveryReachTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun poll(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    /** Opens the Me tab and returns the row with [id] once it is laid out. */
    private fun openMe(scenario: ActivityScenario<ShellActivity>, id: Int): View {
        var row: View? = null
        poll {
            scenario.onActivity { activity ->
                if (activity.findViewById<View>(id) == null) {
                    activity.findViewById<View>(R.id.swNavMe)?.performClick()
                }
                row = activity.findViewById(id)
            }
            row != null
        }
        assertNotNull("the Me tab shows row ${app.getString(labelOf(id))}", row)
        return row!!
    }

    private fun labelOf(id: Int): Int = when (id) {
        R.id.swMeTrash -> R.string.me_trash
        R.id.swMeBackup -> R.string.me_backup
        else -> R.string.me_title
    }

    @Test
    fun `Trash and Backup and restore open from Me in one tap`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            val trashRow = openMe(scenario, R.id.swMeTrash)
            scenario.onActivity { activity ->
                trashRow.performClick()
                val started = shadowOf(activity).nextStartedActivity
                assertNotNull("Trash starts from its Me row", started)
                assertEquals(
                    "the Trash row opens Trash",
                    TrashActivity::class.java.name,
                    started!!.component?.className
                )

                val backupRow = activity.findViewById<View>(R.id.swMeBackup)
                assertNotNull("Backup & restore is on Me", backupRow)
                backupRow!!.performClick()
                val second = shadowOf(activity).nextStartedActivity
                assertNotNull("Backup & restore starts from its Me row", second)
                assertEquals(
                    "the Backup row opens Backup & restore",
                    BackupActivity::class.java.name,
                    second!!.component?.className
                )
            }
        }
    }

    @Test
    fun `Me recovery rows stay readable and named at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        try {
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                val trashRow = openMe(scenario, R.id.swMeTrash)
                val backupRow = openMe(scenario, R.id.swMeBackup)
                scenario.onActivity { activity ->
                    val density = activity.resources.displayMetrics.density
                    for ((row, expected) in listOf(
                        trashRow to app.getString(R.string.me_trash),
                        backupRow to app.getString(R.string.me_backup)
                    )) {
                        activity.window.decorView.measure(
                            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
                        )
                        activity.window.decorView.layout(0, 0, 1080, 2400)
                        assertTrue(
                            "\"$expected\" keeps a 48dp touch target at 200 % (${row.height}px)",
                            row.height >= (48 * density).toInt()
                        )
                        assertTrue("\"$expected\" is visible", row.isShown)
                        val texts = textViews(row)
                        assertTrue("\"$expected\" keeps its full visible label", texts.any { it == expected })
                        for (text in texts) {
                            val tv = textView(row, text)
                            val layout = tv?.layout
                            assertNotNull("\"$expected\" is laid out", layout)
                            for (line in 0 until layout!!.lineCount) {
                                assertEquals(
                                    "\"$text\" is not truncated at 200 %",
                                    0,
                                    layout!!.getEllipsisCount(line)
                                )
                            }
                        }
                        assertNotNull(
                            "\"$expected\" has a TalkBack name",
                            row.contentDescription?.toString()?.takeIf { it.contains(expected) }
                        )
                    }
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1.0f)
        }
    }

    @Test
    fun `Me recovery rows stay readable and named in Hindi at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        RuntimeEnvironment.setQualifiers("hi-rIN-w360dp-h800dp-xhdpi")
        try {
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                val trashRow = openMe(scenario, R.id.swMeTrash)
                val backupRow = openMe(scenario, R.id.swMeBackup)
                scenario.onActivity { activity ->
                    assertEquals(
                        "Trash is named in Hindi on Me",
                        app.getString(R.string.me_trash),
                        textViews(trashRow).first()
                    )
                    assertEquals(
                        "Backup & restore is named in Hindi on Me",
                        app.getString(R.string.me_backup),
                        textViews(backupRow).first()
                    )
                    activity.window.decorView.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
                    )
                    activity.window.decorView.layout(0, 0, 1080, 2400)
                    for ((row, expected) in listOf(
                        trashRow to app.getString(R.string.me_trash),
                        backupRow to app.getString(R.string.me_backup)
                    )) {
                        val tv = textView(row, expected)
                        assertNotNull("the Hindi label \"$expected\" is on the row", tv)
                        for (line in 0 until tv!!.layout.lineCount) {
                            assertEquals(
                                "the Hindi label \"$expected\" is not truncated at 200 %",
                                0,
                                tv.layout.getEllipsisCount(line)
                            )
                        }
                    }
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1.0f)
            RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun textViews(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView && v.text?.isNotBlank() == true) out += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun textView(root: View, text: String): TextView? {
        var found: TextView? = null
        fun walk(v: View) {
            if (found == null && v is TextView && v.text?.toString() == text) found = v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return found
    }
}
