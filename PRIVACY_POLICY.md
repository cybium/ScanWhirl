# Privacy Policy for ScanWhirl

Last updated: 21 November 2026

## The short version

ScanWhirl is a document scanner that works on your phone. It has no account, no ads, no analytics
and no servers of its own. ScanWhirl itself does not upload your documents. Sharing, exporting,
saving or backing up can give files to another app or a destination you choose, including a cloud
provider. Google Play services diagnostics are described below.

ScanWhirl has no internet permission, so it cannot make direct internet connections.

## What stays on your phone

ScanWhirl stores the following in its private storage on your device:

- scans, their pages and their PDFs
- the text ScanWhirl reads from your scans
- names, categories and dates
- drafts, signatures, stamps and annotations
- QR and barcode results, including the last 20 you scanned
- your settings and Vault unlock data

Deleting a document from the library moves it to Trash. It is removed during app cleanup after
30 days, or when you empty Trash. Uninstalling ScanWhirl removes its private data.

## Vault

Files you add to the Vault are encrypted on your phone with AES-256. The Vault passcode is not
stored. A key derived from it with a random salt protects the stored Vault key. Older Vault PINs
are stored only as salted hashes. Fingerprint or face unlock uses Android's biometric prompt and
the Android Keystore.

- Screenshots and screen recording of the Vault are blocked, and the recent apps preview is blank.
- The Vault re-locks when you return after the app has been in the background for two minutes,
  or when its process ends. You can also lock it yourself.
- Copied Wi-Fi passwords are marked sensitive on Android 13 and later. ScanWhirl schedules
  clipboard clearing after 60 seconds, only if the copied text is still there and the app process
  is running. Vault recovery codes are saved to a file you choose, not copied to the clipboard.
  Keep that file private: it contains the readable recovery code.
- Android cloud backup and device to device transfer are turned off for ScanWhirl. Backups are
  only made when you ask. A Vault-only backup is an encrypted `.swvault` file. Backup & restore
  makes an AES-256 encrypted `.swbackup` file, protected by a backup password or Vault recovery
  code, which you save to a location you choose or share through another app. It can include
  library documents, text, drafts, signatures, stamps, the encrypted Vault and selected settings.
  A Vault with unfinished recovery-code confirmation is left out, with a warning. Pro entitlement
  and device-security settings are not carried in the backup.

PDFs you protect with a password are encrypted with AES-256 in the standard PDF security format.

## Google Play services

Some scanning features are provided by Google Play services on your phone:

- the document scanner (page capture and edge detection)
- English and Latin text recognition
- the QR and barcode scanner

Google Play services runs as a separate app. Google says it processes your images and text on your
device and does not send them to Google servers. It may download or update recognition models, and
it sends Google diagnostic and usage data about how the feature performs: device and app
information (such as phone model, Android version and ScanWhirl's version), a device identifier,
performance numbers, event types and error codes. Google encrypts this in transit and does not share
it with third parties. That is Google's processing under Google's privacy policy, and ScanWhirl
never receives it. Because it happens through features ScanWhirl uses, ScanWhirl's Google Play
Data safety section lists it as "Diagnostics" and "Device or other IDs", used for analytics.

The Hindi (Devanagari) text model is included inside ScanWhirl and works fully offline.

Google privacy policy: https://policies.google.com/privacy
ML Kit terms: https://developers.google.com/ml-kit/terms

## Buying Pro

ScanWhirl Pro is a one-time purchase made through Google Play. Google Play handles the payment and
your payment details; ScanWhirl never sees them. The app checks Google Play's signature on purchase
data and asks Google Play whether your Google account owns Pro, so Pro can turn on after a
reinstall or on another phone with the same account. Pro entitlement is not carried in backups.

## Sharing and saving

When you tap Share, Android's share sheet opens and you pick the app or contact. ScanWhirl does not
upload the file itself. The library original stays unless you delete it. Temporary document share
copies are kept so the receiving app can read them, then cleaned up once they are at least
24 hours old, on a later app start or when another share copy is created. This is not a timer that
deletes them exactly 24 hours later.

When you choose Also save in Documents, or turn on the setting to copy PDFs to Documents, the
copy is written to `Documents/ScanWhirl` on your phone. Save as lets you choose a destination in
Android's file picker. Copies outside the app's private storage stay after you uninstall
ScanWhirl until you or the destination app or provider delete them.

## Deleting

- Delete a scan from the library at any time. It goes to Trash.
- Trash keeps a deleted scan restorable for 30 days, then removes it during app cleanup.
- Empty Trash deletes everything in it immediately.
- Uninstalling ScanWhirl removes all of its private data. Saved, shared and backup copies outside
  the app's private storage stay until you or the receiving app or provider delete them.

## Children

ScanWhirl is not directed at children under 13. ScanWhirl itself collects no personal information
from anyone; the Google Play services diagnostics described above apply to every user.

## Changes

If this policy changes, the new version ships with the next app update and the date above changes.

## Contact

Questions about this policy: cybiumsupport@gmail.com

Changelog: 2026-11-15, rewritten to match the shipped app: no internet permission, Vault and PDF
encryption, blocked screenshots, clipboard clearing, Google Play services components, 30 day Trash.
2026-11-16: lists the diagnostic data Google's ML Kit sends (matches the Data safety form); adds
Buying Pro (Google Play Billing).
2026-11-21: clarifies Trash cleanup, sharing, encrypted backups, Vault unlock data, recovery-code
saving, clipboard limits, re-locking and purchase verification for release 1.3.0.
