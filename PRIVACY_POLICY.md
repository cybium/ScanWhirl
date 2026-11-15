# Privacy Policy for ScanWhirl

Last updated: 16 November 2026

## The short version

ScanWhirl is a document scanner that works on your phone. It has no account, no ads, no analytics
and no servers. Nothing you scan leaves your phone unless you choose to share or export it.

ScanWhirl's own code has no internet permission. Android will not let it send anything anywhere.

## What stays on your phone

Everything you create stays in ScanWhirl's private storage on your device:

- scans, their pages and their PDFs
- the text ScanWhirl reads from your scans
- names, categories, dates and search history
- drafts, signatures, stamps and annotations
- QR and barcode results, including the last 20 you scanned
- your settings, including the Vault passcode hash

Deleting a document, or uninstalling ScanWhirl, removes this private data from your phone.

## Vault

Files you add to the Vault are encrypted on your phone with AES-256. The Vault passcode is not
stored, only a salted hash of it is. Fingerprint or face unlock uses Android's biometric prompt and
the Android Keystore.

- Screenshots and screen recording of the Vault are blocked, and the recent apps preview is blank.
- Recovery codes and Wi-Fi passwords copied from ScanWhirl are marked sensitive and cleared from
  the clipboard after 60 seconds.
- Android cloud backup and device to device transfer are turned off for ScanWhirl. A Vault backup
  is only made when you ask for one, as an encrypted `.swvault` file.

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
your payment details; ScanWhirl never sees them. The app only asks Google Play whether your Google
account owns Pro, so Pro can turn on after a reinstall or on another phone with the same account.

## Sharing and saving

When you tap Share, Android's share sheet opens and you pick the app or contact. ScanWhirl does not
send the file anywhere itself, and it does not keep a copy after sharing beyond a temporary cache
file that is deleted on the next start.

When you tap Save, or turn on the setting to copy PDFs to Documents, the file is written to
`Documents/ScanWhirl` on your phone. Those copies are yours. They stay after you uninstall
ScanWhirl until you delete them.

## Deleting

- Delete a scan from the library at any time. It goes to Trash.
- Trash keeps a deleted scan restorable for 30 days, then deletes it permanently.
- Empty Trash deletes everything in it immediately.
- Uninstalling ScanWhirl removes all of its private data. Copies you saved to Documents stay.

## Children

ScanWhirl is not directed at children under 13. ScanWhirl itself collects no personal information
from anyone; the Google Play services diagnostics described above apply to every user.

## Changes

If this policy changes, the new version ships with the next app update and the date above changes.

## Contact

Questions about this policy: t.mecybium@gmail.com

Changelog: 2026-11-15, rewritten to match the shipped app: no internet permission, Vault and PDF
encryption, blocked screenshots, clipboard clearing, Google Play services components, 30 day Trash.
2026-11-16: lists the diagnostic data Google's ML Kit sends (matches the Data safety form); adds
Buying Pro (Google Play Billing).
