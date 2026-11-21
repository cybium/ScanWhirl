package com.scanwhirl.app.ui.tools

import android.content.Intent
import android.provider.ContactsContract
import com.scanwhirl.app.util.ContactParser

/**
 * v2 UI: 03_tools_hub — hands a parsed business card to the phone's contacts app.
 *
 * `ACTION_INSERT` opens the contacts editor with the fields prefilled; ScanWhirl needs no
 * contacts permission, writes nothing itself and the user confirms the save.
 */
object ContactIntents {

    /** Extra carrying how many values did not fit, so the caller can say so in EN or HI. */
    const val EXTRA_DROPPED = "com.scanwhirl.app.contacts.dropped"

    private const val MAX_PHONES = 3
    private const val MAX_EMAILS = 3

    /** The contacts-editor intent for [contact], with the standard Insert extras. */
    fun insert(contact: ContactParser.Contact): Intent {
        // The Insert contract has three slots for each; anything past them is counted here
        // instead of being silently dropped, so the UI can report the loss.
        val phones = contact.phones.filter { it.isNotBlank() }
        val emails = contact.emails.filter { it.isNotBlank() }
        val dropped = (phones.size - MAX_PHONES).coerceAtLeast(0) +
            (emails.size - MAX_EMAILS).coerceAtLeast(0)
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            contact.name.trim().takeIf { it.isNotEmpty() }?.let {
                putExtra(ContactsContract.Intents.Insert.NAME, it)
            }
            contact.company.trim().takeIf { it.isNotEmpty() }?.let {
                putExtra(ContactsContract.Intents.Insert.COMPANY, it)
            }
            contact.jobTitle.trim().takeIf { it.isNotEmpty() }?.let {
                putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it)
            }
            phones.getOrNull(0)?.let {
                putExtra(ContactsContract.Intents.Insert.PHONE, it)
            }
            phones.getOrNull(1)?.let {
                putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it)
            }
            phones.getOrNull(2)?.let {
                putExtra(ContactsContract.Intents.Insert.TERTIARY_PHONE, it)
            }
            emails.getOrNull(0)?.let {
                putExtra(ContactsContract.Intents.Insert.EMAIL, it)
            }
            emails.getOrNull(1)?.let {
                putExtra(ContactsContract.Intents.Insert.SECONDARY_EMAIL, it)
            }
            emails.getOrNull(2)?.let {
                putExtra(ContactsContract.Intents.Insert.TERTIARY_EMAIL, it)
            }
            contact.address.trim().takeIf { it.isNotEmpty() }?.let {
                putExtra(ContactsContract.Intents.Insert.POSTAL, it)
            }
            // Insert has no website extra; keep it in the note so nothing from the card is lost.
            contact.website.trim().takeIf { it.isNotEmpty() }?.let {
                putExtra(ContactsContract.Intents.Insert.NOTES, it)
            }
            putExtra(EXTRA_DROPPED, dropped)
        }
        return intent
    }
}
