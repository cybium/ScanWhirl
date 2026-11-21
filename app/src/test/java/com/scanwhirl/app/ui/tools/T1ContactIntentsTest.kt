package com.scanwhirl.app.ui.tools

import android.provider.ContactsContract
import com.scanwhirl.app.util.ContactParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T1: ACTION_INSERT carries the Android limit of three phone numbers and three emails, and tells
 * the caller how many values had to be left out so the UI can say so in English or Hindi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class T1ContactIntentsTest {

    @Test
    fun `up to three phones and emails are forwarded and the rest are reported`() {
        val contact = ContactParser.Contact(
            name = "Rahul Sharma",
            phones = listOf("+91 1", "+91 2", "+91 3", "+91 4", "+91 5"),
            emails = listOf("a@x.in", "b@x.in", "c@x.in", "d@x.in")
        )
        val intent = ContactIntents.insert(contact)

        assertEquals("+91 1", intent.getStringExtra(ContactsContract.Intents.Insert.PHONE))
        assertEquals("+91 2", intent.getStringExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE))
        assertEquals("+91 3", intent.getStringExtra(ContactsContract.Intents.Insert.TERTIARY_PHONE))
        assertEquals("a@x.in", intent.getStringExtra(ContactsContract.Intents.Insert.EMAIL))
        assertEquals("b@x.in", intent.getStringExtra(ContactsContract.Intents.Insert.SECONDARY_EMAIL))
        assertEquals("c@x.in", intent.getStringExtra(ContactsContract.Intents.Insert.TERTIARY_EMAIL))
        assertEquals("two phones and one email were dropped",
            3, intent.getIntExtra("com.scanwhirl.app.contacts.dropped", 0))
    }
}
