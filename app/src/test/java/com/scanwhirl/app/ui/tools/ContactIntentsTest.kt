package com.scanwhirl.app.ui.tools

import android.provider.ContactsContract
import com.scanwhirl.app.util.ContactParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2 UI: 03_tools_hub — Save to contacts uses the standard `ACTION_INSERT` contract and needs no
 * contacts permission: the phone's contacts editor opens prefilled and the user confirms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactIntentsTest {

    @Test
    fun `insert intent carries every prefilled field`() {
        val contact = ContactParser.Contact(
            name = "Rahul Sharma",
            company = "Acme Technologies Pvt Ltd",
            jobTitle = "Sales Manager",
            phones = listOf("+91 98765 43210", "044 2345 6789"),
            emails = listOf("rahul@acme.in", "sales@acme.in"),
            website = "www.acmetech.in",
            address = "12 MG Road, Bengaluru 560001"
        )
        val intent = ContactIntents.insert(contact)

        assertEquals(ContactsContract.Intents.Insert.ACTION, intent.action)
        assertEquals(ContactsContract.Contacts.CONTENT_TYPE, intent.type)
        assertEquals("Rahul Sharma", intent.getStringExtra(ContactsContract.Intents.Insert.NAME))
        assertEquals("Acme Technologies Pvt Ltd", intent.getStringExtra(ContactsContract.Intents.Insert.COMPANY))
        assertEquals("Sales Manager", intent.getStringExtra(ContactsContract.Intents.Insert.JOB_TITLE))
        assertEquals("+91 98765 43210", intent.getStringExtra(ContactsContract.Intents.Insert.PHONE))
        assertEquals("044 2345 6789", intent.getStringExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE))
        assertEquals("rahul@acme.in", intent.getStringExtra(ContactsContract.Intents.Insert.EMAIL))
        assertEquals("sales@acme.in", intent.getStringExtra(ContactsContract.Intents.Insert.SECONDARY_EMAIL))
        assertEquals(
            "12 MG Road, Bengaluru 560001",
            intent.getStringExtra(ContactsContract.Intents.Insert.POSTAL)
        )
        assertEquals("www.acmetech.in", intent.getStringExtra(ContactsContract.Intents.Insert.NOTES))
    }

    @Test
    fun `empty fields are not sent`() {
        val intent = ContactIntents.insert(ContactParser.Contact(name = "Ravi"))
        assertNull(intent.getStringExtra(ContactsContract.Intents.Insert.PHONE))
        assertNull(intent.getStringExtra(ContactsContract.Intents.Insert.EMAIL))
        assertNull(intent.getStringExtra(ContactsContract.Intents.Insert.COMPANY))
        assertNull(intent.getStringExtra(ContactsContract.Intents.Insert.POSTAL))
        assertEquals("Ravi", intent.getStringExtra(ContactsContract.Intents.Insert.NAME))
    }
}
