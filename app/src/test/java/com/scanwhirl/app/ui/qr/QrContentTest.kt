package com.scanwhirl.app.ui.qr

import com.scanwhirl.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 UI: QR code reader — parsing and action selection per value type (design §3). Pure:
 * no scanner, no Android runtime, so every type has an exact expectation.
 */
class QrContentTest {

    @Test
    fun `a link offers open, copy and share`() {
        val content = QrContent.parse("https://example.com/a?b=1")
        assertEquals(QrKind.URL, content.kind)
        assertEquals("https://example.com/a?b=1", content.title)
        assertEquals(listOf(QrAction.OPEN_LINK, QrAction.COPY, QrAction.SHARE), content.actions)
        assertEquals("https://example.com/a?b=1", content.actionUri)
    }

    @Test
    fun `a bare www link is still a link and opens over https`() {
        val content = QrContent.parse("www.example.com/offer")
        assertEquals(QrKind.URL, content.kind)
        assertEquals("https://www.example.com/offer", content.actionUri)
        assertEquals("https://www.example.com/offer", content.copyText)
    }

    @Test
    fun `a wifi code shows the network and the password, and copies the password`() {
        val content = QrContent.parse("WIFI:T:WPA;S:Home 5G;P:s3cr\\;et;H:true;;")
        assertEquals(QrKind.WIFI, content.kind)
        assertEquals("Home 5G", content.title)
        assertEquals(
            listOf(
                QrField(R.string.qr_field_network, "Home 5G"),
                QrField(R.string.qr_field_password, "s3cr;et", secret = true),
                QrField(R.string.qr_field_security, "WPA")
            ),
            content.fields
        )
        assertEquals(listOf(QrAction.COPY), content.actions)
        assertEquals("s3cr;et", content.copyText)
        assertNull(content.actionUri)
    }

    @Test
    fun `an open wifi network without a password copies the network name`() {
        val content = QrContent.parse("WIFI:S:Cafe Guest;T:nopass;;")
        assertEquals(QrKind.WIFI, content.kind)
        assertEquals("Cafe Guest", content.copyText)
        assertTrue("no password row", content.fields.none { it.secret })
        assertTrue("nopass is not shown as security", content.fields.none { it.labelRes == R.string.qr_field_security })
    }

    @Test
    fun `a vcard contact offers add to contacts and shows name, phone and email`() {
        val vcard = "BEGIN:VCARD\nVERSION:3.0\nN:Doe;John;;;\nFN:John Doe\n" +
            "TEL:+91 90000 00000\nEMAIL:john@example.com\nORG:Acme Ltd\nEND:VCARD"
        val content = QrContent.parse(vcard)
        assertEquals(QrKind.CONTACT, content.kind)
        assertEquals("John Doe", content.title)
        assertEquals(listOf(QrAction.ADD_CONTACT, QrAction.COPY), content.actions)
        assertTrue(content.fields.contains(QrField(R.string.qr_field_phone, "+91 90000 00000")))
        assertTrue(content.fields.contains(QrField(R.string.qr_field_email, "john@example.com")))
        assertTrue(content.fields.contains(QrField(R.string.qr_field_org, "Acme Ltd")))
        assertNull("contacts use the contacts app, not a uri", content.actionUri)
    }

    @Test
    fun `a mecard contact is read like a vcard`() {
        val content = QrContent.parse("MECARD:N:Sharma,Asha;TEL:9876543210;EMAIL:asha@example.in;;")
        assertEquals(QrKind.CONTACT, content.kind)
        assertEquals("Asha Sharma", content.title)
        assertTrue(content.fields.contains(QrField(R.string.qr_field_phone, "9876543210")))
        assertTrue(content.fields.contains(QrField(R.string.qr_field_email, "asha@example.in")))
    }

    @Test
    fun `a phone number offers call and copy with a dial uri`() {
        val content = QrContent.parse("tel:+91-90000-00000")
        assertEquals(QrKind.PHONE, content.kind)
        assertEquals("+91-90000-00000", content.title)
        assertEquals(listOf(QrAction.CALL, QrAction.COPY), content.actions)
        assertEquals("tel:+91-90000-00000", content.actionUri)
    }

    @Test
    fun `an email address offers compose and copy without the mailto query`() {
        val content = QrContent.parse("mailto:hello@example.com?subject=Hi")
        assertEquals(QrKind.EMAIL, content.kind)
        assertEquals("hello@example.com", content.title)
        assertEquals(listOf(QrAction.COMPOSE_EMAIL, QrAction.COPY), content.actions)
        assertEquals("mailto:hello@example.com", content.actionUri)
    }

    @Test
    fun `a text message shows its body and can be copied and shared`() {
        val content = QrContent.parse("SMSTO:+919000000000:See you at 7")
        assertEquals(QrKind.SMS, content.kind)
        assertEquals("+919000000000", content.title)
        assertEquals(listOf(QrField(R.string.qr_field_message, "See you at 7")), content.fields)
        assertEquals(listOf(QrAction.COPY, QrAction.SHARE), content.actions)
    }

    @Test
    fun `a location offers a map`() {
        val content = QrContent.parse("geo:19.0760,72.8777")
        assertEquals(QrKind.GEO, content.kind)
        assertEquals(listOf(QrAction.OPEN_MAP, QrAction.COPY, QrAction.SHARE), content.actions)
        assertEquals("geo:19.0760,72.8777", content.actionUri)
    }

    @Test
    fun `plain text can be copied and shared and does not pretend to be a link`() {
        val content = QrContent.parse("Invoice 4711 paid")
        assertEquals(QrKind.TEXT, content.kind)
        assertEquals("Invoice 4711 paid", content.title)
        assertEquals(listOf(QrAction.COPY, QrAction.SHARE), content.actions)
        assertNull(content.actionUri)
    }

    @Test
    fun `the scanner value type is used when the text has no prefix`() {
        assertEquals(QrKind.URL, QrContent.parse("example.com", QrKind.URL).kind)
        assertEquals(QrKind.PHONE, QrContent.parse("+919000000000", QrKind.PHONE).kind)
        assertEquals(QrKind.EMAIL, QrContent.parse("a@b.in", QrKind.EMAIL).kind)
        assertEquals(QrKind.WIFI, QrContent.parse("S:Net;P:pw;;", QrKind.WIFI).kind)
        assertEquals(QrKind.TEXT, QrContent.parse("a@b.in", QrKind.TEXT).kind)
    }

    @Test
    fun `a very long value is cut for the title but copied whole`() {
        val long = "x".repeat(QrContent.MAX_VALUE + 200)
        val content = QrContent.parse(long)
        assertEquals(QrContent.MAX_TITLE, content.title.length)
        assertTrue(content.title.endsWith("…"))
        assertEquals(QrContent.MAX_VALUE, content.copyText.length)
    }

    @Test
    fun `an empty code parses to an empty text result`() {
        val content = QrContent.parse("   ")
        assertEquals(QrKind.TEXT, content.kind)
        assertEquals("", content.raw)
        assertTrue(content.actions.contains(QrAction.COPY))
    }

    @Test
    fun `every kind has its own label and every action a label`() {
        val kinds = QrKind.entries.map { kind -> QrContent.parse("x", kind).kind }
        assertEquals(QrKind.entries.toSet(), kinds.toSet())
        assertEquals(QrAction.entries.size, QrAction.entries.toSet().size)
    }

    @Test
    fun `a wifi value kept for history drops the password but keeps network and security`() {
        val raw = "WIFI:T:WPA;S:Home 5G;P:s3cr\\;et;;"
        val safe = QrContent.historyValue(raw, QrKind.WIFI)
        assertFalse("the password is not in the reversible Base64 history", safe.contains("s3cr"))
        assertTrue("the network stays", safe.contains("Home 5G"))
        assertTrue("the security stays", safe.contains("WPA"))
        assertEquals("a non-wifi code is stored unchanged", raw, QrContent.historyValue(raw, QrKind.TEXT))
    }

    @Test
    fun `a link shows the real host decoded from punycode without changing the link`() {
        val raw = "https://xn--bcher-kva.example/offer"
        val content = QrContent.parse(raw)
        assertEquals(
            QrField(R.string.qr_field_website, "bücher.example (xn--bcher-kva.example)"),
            content.fields.first()
        )
        assertEquals("what is opened is untouched", raw, content.actionUri)
    }

    @Test
    fun `a link with userinfo shows only the real host`() {
        val raw = "https://trusted.example@evil.example/path"
        val content = QrContent.parse(raw)
        assertEquals(QrField(R.string.qr_field_website, "evil.example"), content.fields.first())
        assertEquals("what is opened is untouched", raw, content.actionUri)
    }

    @Test
    fun `a vcard without a name uses a real field or a contact fallback`() {
        val withPhone = QrContent.parse("BEGIN:VCARD\nVERSION:3.0\nTEL:98765\nEND:VCARD")
        assertEquals("98765", withPhone.title)
        val empty = QrContent.parse("BEGIN:VCARD\nVERSION:3.0\nEND:VCARD")
        assertEquals("Contact", empty.title)
        assertEquals(
            "the fallback is localisable",
            "संपर्क",
            QrContent.parse("BEGIN:VCARD\nEND:VCARD", QrKind.CONTACT, "संपर्क").title
        )
    }
}
