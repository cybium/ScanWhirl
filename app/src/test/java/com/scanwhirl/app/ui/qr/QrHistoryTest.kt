package com.scanwhirl.app.ui.qr

import android.app.Application
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** v2 UI: QR code reader — the local history: last 20, newest first, clear. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QrHistoryTest {

    private lateinit var app: Application
    private lateinit var file: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        file = File(app.filesDir, QrHistory.FILE_NAME)
        file.delete()
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun `the newest scan is first and the list survives a new instance`() {
        val history = QrHistory(app)
        history.add("one", QrKind.TEXT, at = 1_000)
        history.add("two", QrKind.URL, at = 2_000)

        val fresh = QrHistory(app)
        assertEquals(listOf("two", "one"), fresh.list().map { it.value })
        assertEquals(QrKind.URL, fresh.list()[0].kind)
        assertEquals(2_000L, fresh.list()[0].at)
    }

    @Test
    fun `only the last twenty scans are kept`() {
        val history = QrHistory(app)
        repeat(25) { index ->
            history.add("value $index", QrKind.TEXT, at = index.toLong())
        }
        val list = history.list()
        assertEquals(QrHistory.MAX, list.size)
        assertEquals("value 24", list.first().value)
        assertEquals("value 5", list.last().value)
    }

    @Test
    fun `scanning the same value again moves it to the top instead of duplicating`() {
        val history = QrHistory(app)
        history.add("same", QrKind.TEXT, at = 1)
        history.add("other", QrKind.TEXT, at = 2)
        history.add("same", QrKind.TEXT, at = 3)
        assertEquals(2, history.list().size)
        assertEquals(listOf("same", "other"), history.list().map { it.value })
        assertEquals(3L, history.list().first().at)
    }

    @Test
    fun `clear removes everything, including from disk`() {
        val history = QrHistory(app)
        history.add("a", QrKind.TEXT, at = 1)
        history.add("b", QrKind.WIFI, at = 2)
        history.clear()

        assertTrue(history.isEmpty())
        assertFalse(file.exists())
        assertTrue(QrHistory(app).list().isEmpty())
    }

    @Test
    fun `values with tabs and newlines round trip through the file`() {
        val history = QrHistory(app)
        val value = "line one\nline\ttwo"
        history.add(value, QrKind.TEXT, at = 5)
        assertEquals(value, QrHistory(app).list().single().value)
    }

    @Test
    fun `a damaged history file is skipped instead of crashing`() {
        file.writeText("BOGUS\t2\teA==\nTEXT\tx\teA==\nnot-a-line\nTEXT\t3\teA==\n")
        val list = QrHistory(app).list()
        assertEquals(1, list.size)
        assertEquals("eA== decodes to x", "x", list[0].value)
        assertEquals(3L, list[0].at)
    }

    @Test
    fun `an empty value is not stored by the screen but the history tolerates it`() {
        val history = QrHistory(app)
        assertTrue(history.list().isEmpty())
        history.add("", QrKind.TEXT, at = 1)
        assertEquals(listOf(""), history.list().map { it.value })
    }

    @Test
    fun `an old wifi entry with a password is redacted when the history is read`() {
        val old = "WIFI:T:WPA;S:Home;P:s3cret;;"
        val encoded = Base64.encodeToString(old.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        file.writeText("WIFI\t1\t$encoded\n")

        val item = QrHistory(app).list().single()
        assertFalse("the old on-disk password is not shown", item.value.contains("s3cret"))
        assertTrue("the network survives", item.value.contains("Home"))
    }
}
