package com.scanwhirl.app.ui.qr

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Intent
import android.os.Looper
import android.provider.ContactsContract
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * v2 UI: QR code reader — the screen: scan, result with actions by type, history (20, clear),
 * cancel saving nothing, and scanner errors as a sheet. The scanner is a seam, so Robolectric
 * needs no Play services.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QrActivityTest {

    private lateinit var app: Application
    private lateinit var historyFile: File
    private var controller: ActivityController<QrActivity>? = null

    /** Hands out the queued outcomes; an empty queue cancels. */
    private class FakeScanner(outcomes: List<QrScanOutcome>) : QrScanner {
        private val queue = ArrayDeque(outcomes)
        var calls = 0
            private set

        override fun scan(activity: Activity, callback: (QrScanOutcome) -> Unit) {
            calls++
            callback(if (queue.isEmpty()) QrScanOutcome.Cancelled else queue.removeFirst())
        }
    }

    /** A scanner that holds the callback: simulates a slow or never-returning scanner UI. */
    private class HoldingScanner : QrScanner {
        var calls = 0
            private set
        var callback: ((QrScanOutcome) -> Unit)? = null
            private set

        override fun scan(activity: Activity, callback: (QrScanOutcome) -> Unit) {
            calls++
            this.callback = callback
        }
    }

    /** Keeps every callback so a late one from an old scan can be replayed. */
    private class CallbackScanner : QrScanner {
        val callbacks = ArrayList<(QrScanOutcome) -> Unit>()
        var calls = 0
            private set

        override fun scan(activity: Activity, callback: (QrScanOutcome) -> Unit) {
            calls++
            callbacks += callback
        }
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        historyFile = File(app.filesDir, QrHistory.FILE_NAME)
        historyFile.delete()
        QrScanners.override = null
    }

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        QrScanners.override = null
        historyFile.delete()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(vararg outcomes: QrScanOutcome): QrActivity {
        QrScanners.override = FakeScanner(outcomes.toList())
        controller = Robolectric.buildActivity(QrActivity::class.java).setup()
        idle()
        return controller!!.get()
    }

    private fun found(value: String, kind: QrKind = QrKind.TEXT) = QrScanOutcome.Found(value, kind)

    private fun saved(): List<QrHistory.Item> = QrHistory(app).list()

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> Activity.need(id: Int): T = findViewById<View>(id) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> View.need(id: Int): T = findViewById<View>(id) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> Dialog.need(id: Int): T = findViewById<View>(id) as T

    private fun texts(activity: QrActivity): List<String> {
        val row = activity.need<ViewGroup>(R.id.qrActions)
        return (0 until row.childCount).map { (row.getChildAt(it) as TextView).text.toString() }
    }

    @Test
    fun `a link result shows its type, value and the link actions`() {
        val activity = open(found("https://example.com/offer", QrKind.URL))

        assertEquals("LINK", activity.need<TextView>(R.id.qrKind).text.toString())
        assertEquals("https://example.com/offer", activity.need<TextView>(R.id.qrTitle).text.toString())
        assertEquals(listOf("Open link", "Copy", "Share"), texts(activity))
        assertEquals(1, saved().size)
        assertEquals("https://example.com/offer", saved()[0].value)
    }

    @Test
    fun `action labels follow the value type`() {
        assertEquals(listOf("Copy"), texts(open(found("WIFI:S:Home;P:pw;;", QrKind.WIFI))))
        controller?.close()
        assertEquals(listOf("Add to contacts", "Copy"), texts(open(found("BEGIN:VCARD\nFN:Asha\nEND:VCARD", QrKind.CONTACT))))
        controller?.close()
        assertEquals(listOf("Call", "Copy"), texts(open(found("tel:+919000000000", QrKind.PHONE))))
        controller?.close()
        assertEquals(listOf("Compose email", "Copy"), texts(open(found("mailto:a@b.in", QrKind.EMAIL))))
        controller?.close()
        assertEquals(listOf("Copy", "Share"), texts(open(found("plain text"))))
    }

    @Test
    fun `opening a link starts a view intent`() {
        val activity = open(found("https://example.com/x", QrKind.URL))
        activity.need<View>(R.id.qrPrimaryAction).performClick()
        idle()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("a browser is asked to open the link", started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.com/x", started.data.toString())
    }

    @Test
    fun `a contact is added through the contacts app without extra permission`() {
        val vcard = "BEGIN:VCARD\nFN:Asha Sharma\nTEL:9876543210\nEMAIL:asha@example.in\nEND:VCARD"
        val activity = open(found(vcard, QrKind.CONTACT))
        activity.need<View>(R.id.qrPrimaryAction).performClick()
        idle()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_INSERT, started.action)
        assertEquals(ContactsContract.Contacts.CONTENT_TYPE, started.type)
        assertEquals("Asha Sharma", started.getStringExtra(ContactsContract.Intents.Insert.NAME))
        assertEquals("9876543210", started.getStringExtra(ContactsContract.Intents.Insert.PHONE))
        assertEquals("asha@example.in", started.getStringExtra(ContactsContract.Intents.Insert.EMAIL))
    }

    @Test
    fun `a phone number dials without calling`() {
        val activity = open(found("tel:+919000000000", QrKind.PHONE))
        activity.need<View>(R.id.qrPrimaryAction).performClick()
        idle()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_DIAL, started.action)
        assertEquals("tel:+919000000000", started.data.toString())
    }

    @Test
    fun `copy puts the wifi password on the clipboard, not the whole code`() {
        val activity = open(found("WIFI:T:WPA;S:Home;P:s3cret;;", QrKind.WIFI))
        val copy = activity.need<ViewGroup>(R.id.qrActions).getChildAt(0)
        copy.performClick()
        idle()

        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertEquals("s3cret", clipboard.primaryClip?.getItemAt(0)?.text.toString())
    }

    @Test
    fun `cancelling the first scan saves nothing and closes the screen`() {
        val activity = open(QrScanOutcome.Cancelled)
        assertTrue("nothing to show, so the screen closes", activity.isFinishing)
        assertTrue(saved().isEmpty())
    }

    @Test
    fun `cancelling a second scan keeps the result already on screen`() {
        val activity = open(found("https://example.com/a", QrKind.URL), QrScanOutcome.Cancelled)
        activity.need<View>(R.id.qrScanAgain).performClick()
        idle()

        assertFalse(activity.isFinishing)
        assertEquals("https://example.com/a", activity.need<TextView>(R.id.qrTitle).text.toString())
        assertEquals("only the successful scan is saved", 1, saved().size)
    }

    @Test
    fun `a scanner failure is explained in a sheet and saves nothing`() {
        val activity = open(QrScanOutcome.Failed("Could not start the scanner: no Play services"))
        idle()

        val dialog = ShadowDialog.getLatestDialog() as BottomSheetDialog
        assertEquals(
            "Could not start the scanner: no Play services",
            dialog.need<TextView>(R.id.swSheetMessage).text.toString()
        )
        assertFalse(activity.isFinishing)
        assertTrue(saved().isEmpty())
    }

    @Test
    fun `the history keeps twenty scans and clear empties it`() {
        val outcomes = (0 until 21).map { found("https://example.com/$it", QrKind.URL) }
        val activity = open(*outcomes.toTypedArray())

        repeat(20) {
            activity.need<View>(R.id.qrScanAgain).performClick()
            idle()
        }
        assertEquals("the file keeps only the last twenty", QrHistory.MAX, saved().size)
        assertEquals("https://example.com/20", saved().first().value)
        assertEquals(
            "the list shows only the last twenty",
            QrHistory.MAX.toLong(),
            activity.need<ViewGroup>(R.id.qrHistoryList).childCount.toLong()
        )

        activity.need<View>(R.id.qrHistoryClear).performClick()
        idle()
        assertTrue(saved().isEmpty())
        assertEquals("the history section is gone", null, activity.findViewById<View>(R.id.qrHistoryClear))
    }

    @Test
    fun `tapping a history row shows that result again`() {
        val activity = open(found("https://example.com/first", QrKind.URL), found("plain text"))
        activity.need<View>(R.id.qrScanAgain).performClick()
        idle()
        assertEquals("plain text", activity.need<TextView>(R.id.qrTitle).text.toString())

        // Newest first: row 0 is "plain text", row 1 is the link.
        activity.need<ViewGroup>(R.id.qrHistoryList).getChildAt(1).performClick()
        idle()

        assertEquals("https://example.com/first", activity.need<TextView>(R.id.qrTitle).text.toString())
        assertEquals("LINK", activity.need<TextView>(R.id.qrKind).text.toString())
    }

    @Test
    fun `an empty code shows the empty message instead of a blank result`() {
        val activity = open(found("   ", QrKind.TEXT))
        idle()

        val dialog = ShadowDialog.getLatestDialog() as BottomSheetDialog
        assertEquals(
            activity.getString(R.string.qr_empty_code),
            dialog.need<TextView>(R.id.swSheetMessage).text.toString()
        )
        assertTrue(saved().isEmpty())
    }

    @Test
    fun `the close button finishes the screen`() {
        val activity = open(found("hello"))
        activity.need<View>(R.id.qrClose).performClick()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a wifi password is masked until Show is tapped and can be hidden again`() {
        val activity = open(found("WIFI:T:WPA;S:Home;P:s3cret;;", QrKind.WIFI))
        val value = activity.need<TextView>(R.id.qrSecretValue)
        assertEquals(QrContent.MASK, value.text.toString())

        val toggle = activity.need<View>(R.id.qrFieldToggle)
        assertEquals("Show", (toggle as TextView).text.toString())
        toggle.performClick()
        assertEquals("s3cret", value.text.toString())
        assertEquals("Hide", (toggle as TextView).text.toString())

        toggle.performClick()
        assertEquals(QrContent.MASK, value.text.toString())
    }

    @Test
    fun `clearing history clears a copied wifi password still on the clipboard`() {
        val activity = open(found("WIFI:T:WPA;S:Home;P:s3cret;;", QrKind.WIFI))
        activity.need<ViewGroup>(R.id.qrActions).getChildAt(0).performClick()
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertEquals("s3cret", clipboard.primaryClip?.getItemAt(0)?.text.toString())

        activity.need<View>(R.id.qrHistoryClear).performClick()
        assertTrue(
            "Clear also removes a secret that is still on the clipboard",
            clipboard.primaryClip == null || clipboard.primaryClip!!.itemCount == 0
        )
    }

    @Test
    fun `a timed-out scanner keeps Scan blocked until the screen resumes`() {
        val scanner = HoldingScanner()
        QrScanners.override = scanner
        controller = Robolectric.buildActivity(QrActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idleFor(61_000L, TimeUnit.MILLISECONDS)
        val activity = controller!!.get()

        activity.need<View>(R.id.qrScan).performClick()
        assertEquals("the first scanner may still be showing: no second sheet", 1, scanner.calls)

        // Coming back means the Play-services scanner UI is no longer in front.
        controller!!.pause().resume()
        activity.need<View>(R.id.qrScan).performClick()
        assertEquals("Scan is live again after a resume", 2, scanner.calls)
    }

    @Test
    fun `a late callback from an old scan cannot block a timed-out newer scan`() {
        val scanner = CallbackScanner()
        QrScanners.override = scanner
        controller = Robolectric.buildActivity(QrActivity::class.java).setup()
        val activity = controller!!.get()

        // Scan #1 times out, its UI goes away, and a fresh scan #2 starts.
        shadowOf(Looper.getMainLooper()).idleFor(61_000L, TimeUnit.MILLISECONDS)
        controller!!.pause().resume()
        activity.need<View>(R.id.qrScan).performClick()
        assertEquals(2, scanner.calls)

        // Scan #2 times out too; a late callback from #1 must not touch #2's state.
        shadowOf(Looper.getMainLooper()).idleFor(61_000L, TimeUnit.MILLISECONDS)
        scanner.callbacks[0].invoke(found("old result"))
        controller!!.pause().resume()

        activity.need<View>(R.id.qrScan).performClick()
        assertEquals("a stale callback must not permanently disable Scan", 3, scanner.calls)
        assertTrue("the stale result is not saved", saved().isEmpty())
    }

    @Test
    fun `a callback from a destroyed activity does not write history`() {
        val scanner = HoldingScanner()
        QrScanners.override = scanner
        controller = Robolectric.buildActivity(QrActivity::class.java).setup()
        idle()
        controller!!.destroy()

        scanner.callback?.invoke(found("WIFI:T:WPA;S:Home;P:s3cret;;", QrKind.WIFI))
        assertTrue("no result is saved by a destroyed screen", saved().isEmpty())

        val fresh = open(found("hello"))
        assertEquals("hello", fresh.need<TextView>(R.id.qrTitle).text.toString())
    }

    @Test
    fun `clickable controls expose a Button role and a 48dp target`() {
        val activity = open(found("https://example.com/x", QrKind.URL), found("plain"))
        val minPx = (48f * activity.resources.displayMetrics.density).toInt()
        val targets = listOf(
            activity.need<View>(R.id.qrPrimaryAction),
            activity.need<View>(R.id.qrHistoryClear),
            activity.need<ViewGroup>(R.id.qrHistoryList).getChildAt(0)
        )
        targets.forEach { view ->
            assertTrue("id=${view.id} has a 48dp target", view.minimumHeight >= minPx)
            val info = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain())
            ViewCompat.onInitializeAccessibilityNodeInfo(view, info)
            assertEquals("Button role", Button::class.java.name, info.className.toString())
        }
    }

    @Test
    fun `a link shows only the real host before Open link and still opens the same link`() {
        val raw = "https://trusted.example@evil.example/path"
        val activity = open(found(raw, QrKind.URL))
        val label = activity.findViewById<TextView>(R.id.qrFieldLabel)
        assertNotNull("a Website row is shown", label)
        assertEquals("WEBSITE", label!!.text.toString())
        assertEquals(
            "the user@ trick is not shown as the host",
            "evil.example",
            activity.need<TextView>(R.id.qrFieldValue).text.toString()
        )

        activity.need<View>(R.id.qrPrimaryAction).performClick()
        idle()
        assertEquals(raw, shadowOf(activity).nextStartedActivity.data.toString())
    }

    @Test
    fun `a sensitive copy says it is cleared but an ordinary copy just says Copied`() {
        val wifi = open(found("WIFI:T:WPA;S:Home;P:s3cret;;", QrKind.WIFI))
        wifi.need<ViewGroup>(R.id.qrActions).getChildAt(0).performClick()
        assertTrue(
            "the toast explains the auto-clear",
            ShadowToast.getTextOfLatestToast().toString().contains("1 minute")
        )
        controller?.close()

        val link = open(found("https://example.com/x", QrKind.URL))
        link.need<ViewGroup>(R.id.qrActions).getChildAt(1).performClick()
        assertEquals("Copied", ShadowToast.getTextOfLatestToast().toString())
    }
}
