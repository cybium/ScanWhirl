package com.scanwhirl.app

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream

/** Drive the real SAF result before confirming, with a disposable in-memory destination. */
internal fun saveRecoveryFileForTest(activity: Activity, recovery: Dialog) {
    val provider = Robolectric.setupContentProvider(FixCopyTest.RecordingDocumentsProvider::class.java,
        "com.scanwhirl.app.test")
    val uri = Uri.parse("content://com.scanwhirl.app.test/recovery-confirm.txt")
    val output = ByteArrayOutputStream()
    shadowOf(activity.contentResolver).registerOutputStream(uri, output)
    val main = shadowOf(Looper.getMainLooper())
    recovery.findViewById<View>(R.id.vaultRecoveryCopy).performClick()
    main.idle()
    val started = requireNotNull(shadowOf(activity).nextStartedActivityForResult)
    assertTrue("the recovery Save opens the system create-document picker",
        started.intent.action == Intent.ACTION_CREATE_DOCUMENT)
    // startActivityForResult records the same intent in the plain started-activities queue too;
    // consume it so later assertions about what the app starts never read this picker intent.
    val plain = shadowOf(activity).nextStartedActivity
    assertTrue("the consumed plain entry is this same picker intent",
        plain != null && plain.action == Intent.ACTION_CREATE_DOCUMENT)
    shadowOf(activity).callOnActivityResult(started.requestCode, Activity.RESULT_OK, Intent().setData(uri))
    val primary = recovery.findViewById<View>(R.id.swSheetPrimary)
    val deadline = System.nanoTime() + 8_000_000_000L
    while (!primary.isEnabled && System.nanoTime() < deadline) {
        main.idle()
        Thread.sleep(10)
    }
    main.idle()
    assertTrue("a completed recovery file enables confirmation", primary.isEnabled)
    assertTrue("recovery file has bytes", output.size() > 0)
}
