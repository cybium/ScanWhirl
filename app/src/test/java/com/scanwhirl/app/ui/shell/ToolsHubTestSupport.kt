package com.scanwhirl.app.ui.shell

import android.os.Looper
import com.scanwhirl.app.R
import org.robolectric.Shadows.shadowOf

/**
 * B4-TOOLS test helper: the Tools tab shows the first rows of a long job and one "All N tools"
 * door. Tests that need the whole catalogue open every door first, exactly as a person would.
 */
internal fun openAllToolsDoors(activity: ShellActivity) {
    var guard = 0
    while (guard++ < 20) {
        val door = ToolsHubScreen.doors(activity.findViewById(R.id.swToolsBody)).firstOrNull()
            ?: return
        door.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
