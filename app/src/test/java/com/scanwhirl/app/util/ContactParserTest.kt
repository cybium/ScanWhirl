package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 UI: 03_tools_hub — the pure business-card parser, on realistic cards and OCR noise.
 *
 * Every case is synthetic text, including the noise shapes ML Kit produces on real cards:
 * `O` for `0`, `l` for `1`, spaced-out digits, and labels such as `Ph:`, `M:`, `E-mail`, `www.`.
 */
class ContactParserTest {

    private fun parse(text: String) = ContactParser.parse(text)

    @Test
    fun `indian card with title company two phones email web and address`() {
        val c = parse(
            """
            Rahul Sharma
            Sales Manager
            Acme Technologies Pvt Ltd
            M: +91 98765 43210
            Ph: 044 2345 6789
            E-mail: Rahul.Sharma@Acme.in
            www.acmetech.in
            12 MG Road, Bengaluru 560001
            """.trimIndent()
        )
        assertEquals("Rahul Sharma", c.name)
        assertEquals("Sales Manager", c.jobTitle)
        assertEquals("Acme Technologies Pvt Ltd", c.company)
        assertEquals(listOf("+91 98765 43210", "044 2345 6789"), c.phones)
        assertEquals(listOf("rahul.sharma@acme.in"), c.emails)
        assertEquals("www.acmetech.in", c.website)
        assertTrue("address: ${c.address}", c.address.contains("MG Road"))
        assertTrue("pincode: ${c.address}", c.address.contains("560001"))
    }

    @Test
    fun `ocr noise O and l in a phone number`() {
        val c = parse(
            """
            Priya Nair
            Director
            Bluewave Media
            Mob: 98l65 4321O
            priya@bluewave.co.in
            """.trimIndent()
        )
        assertEquals("Priya Nair", c.name)
        assertEquals(listOf("98165 43210"), c.phones)
        assertEquals("Bluewave Media", c.company)
    }

    @Test
    fun `spaced out digits are one Indian mobile`() {
        val c = parse("Suresh Kumar\n9 8 7 6 5 4 3 2 1 0\nsuresh@example.in")
        assertEquals("Suresh Kumar", c.name)
        assertEquals(listOf("98765 43210"), c.phones)
    }

    @Test
    fun `labels Ph M E-mail and www are stripped`() {
        val c = parse(
            """
            Asha Verma
            Ph: 91234 56780
            M: 90000 11111
            E-mail: asha@example.com
            www.example.com
            """.trimIndent()
        )
        assertEquals(listOf("91234 56780", "90000 11111"), c.phones)
        assertEquals(listOf("asha@example.com"), c.emails)
        assertEquals("www.example.com", c.website)
        assertEquals("", c.company)
    }

    @Test
    fun `international numbers keep a plus and drop spaces`() {
        val us = parse("John Smith\nVP Engineering\nGlobex Inc\n+1 415 555 0132\njohn@globex.com")
        assertEquals(listOf("+14155550132"), us.phones)
        assertEquals("VP Engineering", us.jobTitle)
        assertEquals("Globex Inc", us.company)

        val uk = parse("Emma Brown\nConsultant\nBrown and Co\nTel: +44 20 7946 0958")
        assertEquals(listOf("+442079460958"), uk.phones)
    }

    @Test
    fun `indian landline is grouped with its STD code`() {
        val c = parse("Vikram Rao\nPh: (044) 2345 6789\nvikram@example.in")
        assertEquals(listOf("044 2345 6789"), c.phones)
    }

    @Test
    fun `two numbers on one line separated by a slash`() {
        val c = parse("Meera Iyer\nM: 9876543210 / 9123456780\nmeera@example.in")
        assertEquals(listOf("98765 43210", "91234 56780"), c.phones)
    }

    @Test
    fun `hindi name line is kept as the name`() {
        val c = parse(
            """
            राहुल शर्मा
            विक्रय प्रबंधक
            एकता उद्योग
            मोबाइल: 98765 43210
            """.trimIndent()
        )
        assertEquals("राहुल शर्मा", c.name)
        assertEquals(listOf("98765 43210"), c.phones)
    }

    @Test
    fun `multi line address is joined`() {
        val c = parse(
            """
            Neha Gupta
            Proprietor
            Gupta Traders
            14, Nehru Road
            Near Bus Stand
            Pune 411001
            Ph: 020 2555 1234
            """.trimIndent()
        )
        assertEquals("Neha Gupta", c.name)
        assertEquals("Gupta Traders", c.company)
        assertTrue("address: ${c.address}", c.address.contains("Nehru Road"))
        assertTrue("address: ${c.address}", c.address.contains("Pune 411001"))
        assertEquals(listOf("020 2555 1234"), c.phones)
    }

    @Test
    fun `email only card leaves the other fields empty`() {
        val c = parse("Ravi Menon\nravi.menon@example.org")
        assertEquals("Ravi Menon", c.name)
        assertEquals(listOf("ravi.menon@example.org"), c.emails)
        assertEquals(emptyList<String>(), c.phones)
        assertEquals("", c.company)
        assertEquals("", c.jobTitle)
    }

    @Test
    fun `all caps letterhead is the company not the name`() {
        val c = parse(
            """
            SUNRISE ENTERPRISES
            Anita Desai
            Accounts Officer
            Ph: 98111 22233
            """.trimIndent()
        )
        assertEquals("SUNRISE ENTERPRISES", c.company)
        assertEquals("Anita Desai", c.name)
        assertEquals("Accounts Officer", c.jobTitle)
    }

    @Test
    fun `website wins over a domain-like company line`() {
        val c = parse("Karan Shah\nKaran Shah Design Studio\nhttp://karanshah.design\nPh: 98200 11223")
        assertEquals("http://karanshah.design", c.website)
        assertEquals("Karan Shah", c.name)
        assertTrue("company: ${c.company}", c.company.contains("Design"))
    }

    @Test
    fun `empty and label-only text yields an empty contact`() {
        val c = parse("Ph:\nE-mail:\nM:\n\n")
        assertEquals(ContactParser.Contact(), c)
    }

    @Test
    fun `vcard 3 escapes separators and backslashes`() {
        val contact = ContactParser.Contact(
            name = "Rahul Sharma",
            company = "A; B, C\\D",
            jobTitle = "Sales; Lead",
            phones = listOf("+91 98765 43210"),
            emails = listOf("rahul@example.in"),
            website = "www.example.in",
            address = "Line1; Line2, Line3"
        )
        val vcf = ContactParser.vCard(contact)
        assertTrue(vcf.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        assertTrue(vcf.endsWith("END:VCARD\r\n"))
        assertTrue("FN missing", vcf.contains("FN:Rahul Sharma\r\n"))
        assertTrue("N split", vcf.contains("N:Sharma;Rahul;;;\r\n"))
        assertTrue("ORG escape", vcf.contains("ORG:A\\; B\\, C\\\\D\r\n"))
        assertTrue("TITLE escape", vcf.contains("TITLE:Sales\\; Lead\r\n"))
        assertTrue("TEL", vcf.contains("TEL;TYPE=CELL:+91 98765 43210\r\n"))
        assertTrue("EMAIL", vcf.contains("EMAIL;TYPE=INTERNET:rahul@example.in\r\n"))
        assertTrue("URL", vcf.contains("URL:www.example.in\r\n"))
        assertTrue("ADR escape", vcf.contains("ADR;TYPE=WORK:;;Line1\\; Line2\\, Line3;;;;\r\n"))
    }

    @Test
    fun `normalizePhone formats Indian numbers and refuses junk`() {
        assertEquals("98765 43210", ContactParser.normalizePhone("9876543210"))
        assertEquals("+91 98765 43210", ContactParser.normalizePhone("+91 98765 43210"))
        assertEquals("98765 43210", ContactParser.normalizePhone("09876543210"))
        assertEquals("+14155550132", ContactParser.normalizePhone("+1 (415) 555-0132"))
        assertEquals(null, ContactParser.normalizePhone("12345"))
    }
}
