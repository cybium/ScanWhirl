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

    /** The contacts-editor intent for [contact], with the standard Insert extras. */
    fun insert(contact: ContactParser.Contact): Intent {
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
            contact.phones.getOrNull(0)?.takeIf { it.isNotBlank() }?.let {
                putExtra(ContactsContract.Intents.Insert.PHONE, it)
            }
            contact.phones.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
                putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it)
            }
            contact.emails.getOrNull(0)?.takeIf { it.isNotBlank() }?.let {
                putExtra(ContactsContract.Intents.Insert.EMAIL, it)
            }
            contact.emails.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
                putExtra(ContactsContract.Intents.Insert.SECONDARY_EMAIL, it)
            }
            contact.address.trim().takeIf { it.isNotEmpty() }?.let {
                putExtra(ContactsContract.Intents.Insert.POSTAL, it)
            }
            // Insert has no website extra; keep it in the note so nothing from the card is lost.
            contact.website.trim().takeIf { it.isNotEmpty() }?.let {
                putExtra(ContactsContract.Intents.Insert.NOTES, it)
            }
        }
        return intent
    }
}
