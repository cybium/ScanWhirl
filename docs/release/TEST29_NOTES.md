# ScanWhirl 1.1.1-test29 — what changed since test28

Installs side by side with the store app as "ScanWhirl Test" (package com.scanwhirl.app.test).
Test builds count as Pro; Settings > Privacy & security > "Testing: act as a Free user" shows the Free app.

## New
- **Quick tools you choose**: pencil on Home > remove, add, drag to reorder, up to 2 rows (12). Remembered.
- **Pro screen and Pro pop-up animate**: gold logo turns, rings, title and pills fade in, a shine crosses
  "Unlock Pro", one pulse. Price and Unlock Pro stay pinned at the bottom. Off when animations are off.
- **Pro Gold theme + gold app icon** (Settings > Accent, Pro). Cyan stays the default.
- **Find and hide numbers** (Redact > Find): Aadhaar (first 8 digits), VID, PAN, card numbers, phone,
  email, IBAN, SSN, long account numbers. "Mask Aadhaar" is a quick chip inside Find.
- **Full Backup 2.0** (Me > Backup & restore): one encrypted file with the library, Vault, signatures,
  stamps and settings; password or Vault recovery code; restores without duplicates; Vault files can be
  merged into a phone that already has a Vault.
- **Book pages**: sideways photos turn upright, pages in reading order, the cut never runs through text,
  and a **tilted spine is cut along its tilt** (books photographed at an angle). Flip pages on every card.
- **Scan Enhance 2.0**: Auto / Colour boost / Grey / B&W; B&W keeps photos grey instead of black blotches.
- **ID card on A4 or US Letter** (picked by country, remembered).
- **Protect PDF / Unlock PDF** tiles work from Tools and Home.
- **Block screenshots of documents** (Settings > Privacy & security). Protected documents always blocked.
- English + Hindi only (no other OCR languages). Better Hindi on phone photos. Text indexing 9x faster.
- Accessibility: 129 problems found by the new audit, all fixed (bigger touch targets, readable contrast,
  screen-reader labels, no clipped text at large font sizes). A "no dead buttons" audit now runs on every check.

## Fixed after the lead's checks on the owner's phone
- Settings page was completely blank; two Settings switches were invisible.
- Quick tools: badges covering PRO tags, broken second row, misaligned circles, words split mid-word,
  "Insert pages" had no icon.
- Pro screen: title missing a space, rings cut off, price below the fold, animation glitch (bar at the top +
  full-screen shine), PRO tags not refreshing after a purchase.
- Backup: restoring with a recovery code typed with spaces failed; Share crashed; declining the Vault unlock
  looped; the share sheet closing lost "Save to device"; result wording.
- Book pages: cut through words on a tilted spine.

## Checked on the owner's phone (Infinix) by the lead
Quick tools editing (add, remove, drag, saved after restart); Pro screen + Pro pop-up animation (recorded,
frame by frame); Find and hide numbers on an Aadhaar card with the real text reader (first 8 digits fully
covered); Backup with the spaced recovery code, Share, Save, Restore (no duplicates); Settings sections;
Me tab.

## NOT yet checked on the phone (checked by tests and on the owner's photos only)
- Book pages tilted cut inside the app (the phone was disconnected); verified on photo 005924 by zooming the
  result at five heights and by automatic tests.
- Restoring Vault files into a phone that already has a Vault (the test Vault is empty).
- Gold app-icon swap on the home screen.

## Known
- Tapping a .swbackup file in the Files app opens Google Wallet ("Unsupported file type"); open backups from
  Me > Backup & restore. (After launch: ScanWhirl will open its own backup files.)
- The automatic test run sometimes hits a crash inside the test tool (not the app); it is retried once and
  L2 is finding the root cause.
