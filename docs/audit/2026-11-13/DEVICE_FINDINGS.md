# Device verification — candidate D

**Build:** ScanWhirl 1.1.1-test27, versionCode 27, package `com.scanwhirl.app.test`  
**Device:** Infinix X6711, Android 14, 1080 × 2460, density 480  
**Walk:** 2026-11-13, approximately 16:24–16:48  
**APK:** `ScanWhirl-M08-candidate-D.apk`, SHA-256 `ED3055D1…`  
**Method:** ADB screenshots and UIAutomator hierarchy, guarded taps only while ScanWhirl was foreground. Camera was not used because the phone was face-down; a document page was imported through the Android photo picker.

This is **candidate D**, installed before the latest uncommitted UX pass. It is not proof of the current source tree’s behavior.

## Corrections to the video-first critique

### The stale page grid did not reproduce

The video showed the editor at 1/1 and the page grid still saying 2 pages (02:59–03:04). On candidate D:

- Deleted page 2 from DocX.
- Editor changed to 1/1 with “Page deleted · Undo”.
- Returning to the grid showed **1 page** and one thumbnail.
- The grid no longer retained the deleted page.

**Status:** F02 is not reproducible in candidate D. Keep it as a historical video finding, but do not send it to Opus as a current bug without checking the recorded build. The current source may already fix it.

### B/W is image-dependent, not universally destructive

The video’s contents page became nearly white at 02:56. On candidate D, applying B/W to the same type of contents page produced a **legible high-contrast page** (screenshot `page_bw_p2.png`). The imported screenshot also stayed legible.

**Status:** F01 must be reclassified. The extreme failure is real for the video frame, but it did not reproduce on two device samples. The remaining current issue is the absence of an Original/Result comparison and a clear revert; not a universal B/W failure.

## New or confirmed findings

| ID | Severity | Screen | Device evidence | Why it matters | Direction |
|---|---|---|---|---|---|
| D1 | P1 | Page editor / Home, 16:44–16:46 | Deleted page 2 from DocX; editor showed 1/1, grid showed 1 page. The Save changes dialog then offered only Overwrite saved PDF / Cancel / Save as new document. After force-stopping from that dialog and relaunching, Home still showed DocX as **1 page, 1009 KB**. The deleted contents page did not return. | The page-set change survives a force-stop while the PDF-save decision is still unresolved. There is no “Discard changes” route. A user cannot confidently abandon an edit, and the library and PDF may be in different states. This is more serious than the old stale-grid bug. | Decide the transaction model: either edits are committed immediately and the UI says so, or they remain pending until Save and can be discarded. The save dialog must name the file and offer Discard. Verify whether the PDF still contains the deleted page before Share. |
| D2 | P1 | Save changes dialog, 16:45 | “Overwrite saved PDF” is bright cyan; “Cancel” and “Save as new document” are muted. The selected filename is absent. `Cancel` does not discard. There is no “Discard changes” or “Keep editing.” | The visually primary action is destructive, the target is unnamed, and the only escape is ambiguous. This can overwrite an imported original or leave the document in an unresolved state. | Make Save as new primary, name the source file, add Discard, and confirm replacement. |
| D3 | P1 | FREE header / PRO dialog, 16:47–16:48 | With the header showing FREE, Tools still shows the gold PRO band with Lock and Vault. Tapping Lock opens “SCANWHIRL PRO — Not available yet”, with only Preview Pro UI and OK. No price, purchase, benefit list, restore, or reason to upgrade. | This is a developer preview, not a monetisation surface. A free user hits a dead end; a buyer cannot buy. The app’s “PRO” promise has no product behind it. | Build a real PRO value proposition, or remove the PRO badge/band until billing exists. Do not show “Not available yet” after a user taps a premium tool. |
| D4 | P1 | Tools → Vault, 16:28 | Tapping Vault returns to Home with “Adding to Hidden Vault is unavailable. Existing vault items stay accessible.” No Vault screen, contents view, empty state or error page exists. | A privacy feature looks available and dies in a toast. This is the worst possible trust signal for a scanner that claims local ownership. | Hide the tile before entry, or open a real Vault empty state with an explanation. The current WIP reportedly hides it; verify on device. |
| D5 | P1 | Document viewer, 16:39–16:41 | Opening DocX shows only the page images, “Find in text”, and back. Share/Edit/Text/More are not on the reader. They are reachable only by returning Home, opening the row overflow, and choosing “Edit pages”; the page grid then exposes Share/Edit/Text/More. | The primary document actions are hidden behind a row overflow. Users will assume the saved document is read-only or that sharing is impossible. | Put the document action dock in the reader, or make the row overflow the single explicit “Document actions” command with a visible cue. |
| D6 | P2 | Home row overflow / page-grid More, 16:41–16:45 | Home overflow: Edit pages, Rename, Move to category, Share, Move to Trash. It has no Lock action. Page-grid More: Rename, Move to category, Duplicate, Compress, Lock PDF, Hide in Vault, Move to Trash. It is a text-only sheet. Home rows show no lock badge. | Lock lives one level deeper than the document, and there is no visible protected state. A user cannot tell whether a document is locked before opening it, and “Lock PDF” sounds like a file-system action rather than encryption. | Put a lock badge on the row and reader, move Lock/Unlock into document actions, and use “Password-protect PDF” or “Locked PDF” with an explanation. |
| D7 | P1 | Resize image, 16:34 | The custom field opens a **text** keyboard. Cancel and Resize are below the keyboard and invisible. The preset 500 KB sits alone on a second row. The field reads “Up to 500 KB, 3 MB” as if it were a value, and there is no persistent unit once text is entered. | The user loses the completion action and cannot tell whether the field is a hint, a preset or an exact target. | Keep the action row above the keyboard, use a numeric keyboard, make presets and custom mutually exclusive, and show the unit beside the value. |
| D8 | P2 | Merge scans, 16:31 | The multi-select list is name-only: M08 Paper1, M08 Google1, M08 Book1, M08 Book2 Landscape, Document 1–3, DocX, DocX (compressed), and a truncated duplicate M08 Book1. No thumbnails, page counts, dates or order controls. | Duplicate names are indistinguishable; merge order is invisible. A wrong merge is discovered only after creation. | Add thumbnail, page count, date and category; show the selected documents as an ordered list before Merge. |
| D9 | P2 | Compression level, 16:32 | The dialog title does not name the selected document. It mixes four radio presets with a free-text “target size” field and no explanation of precedence. Opening the field uses a text keyboard. | The user cannot tell which file is being compressed or whether the custom number overrides Medium. | Name the file, separate preset from exact target, use a numeric keypad, and show the expected result. |
| D10 | P2 | Import, 16:35 | The bottom sheet spends roughly half the screen on two cards plus a subtitle and a full-width picker-order footnote. The user only needs to choose Photos or PDF. | The first decision is buried in explanatory copy; the sheet feels heavier than the task. | Show two one-line choices; move the picker-order warning to Review. |
| D11 | P1 | Settings / About, 16:27–16:28 | Auto enhance is ON and says “Google processing is on when this is on.” About says “Everything happens on your phone: no accounts, no ads, no servers. Your scans stay on your device only.” | A privacy-sensitive user cannot reconcile “Google processing” with “no servers.” This is the app’s central trust claim, and the words do not prove it. | State exactly what runs locally and what leaves the device. Link the privacy policy at the setting, not only in About. |
| D12 | P2 | Settings, 16:27 | Starfield decoration sits behind all Settings cards. Status line and Wallpaper remain as decorative appearance toggles. The header and card borders compete with the document task. | Settings feels like a themed demo, not a document utility. Decoration behind labels reduces the reading plane. | Remove the starfield from form surfaces, deprioritise Wallpaper and Status line, and use the space for library/privacy controls. |
| D13 | P2 | Page editor, 16:42 | A landscape page floats in a large black field; the document occupies about one third of the screen height, with the bottom action dock. The page count is isolated top-right. | The document is not the visual focus. Users inspect a small page in a large dark frame. | Fit the page to the available width with a deliberate background and a compact toolbar; avoid a giant empty band above landscape content. |
| D14 | P2 | Home, 16:24 | 12 documents, a clipped “IDs 1” chip, “12 documents”, sort and Select, then every card repeats category plus “Search ready.” Four cards fill the screen. Serif titles conflict with sans UI. | The list is visually busy before the content. The repeated green check is low-information because every indexed document has it. | Show index status only when it is not ready; keep category as a quiet chip; use one title face; make the chip rail clearly scrollable. |
| D15 | P2 | Imported review, 16:36 | The imported page is titled “Document 6” with “Edited · not saved”; the title is editable but no smart name is suggested. The preview is a tiny phone screenshot inside the page, with a large empty canvas. | The user must name every document manually, and the primary page preview is too small to judge. | Suggest a name from OCR/date; make the page preview the dominant element. |
| D16 | P2 | Document viewer, 16:39 | The reader has only a Find icon; the 1/2 badge is the only page status. There is no share, edit, OCR, lock or more action. | A saved document feels like a dead-end viewer. Users must learn a second navigation path through Home. | Add the page-grid action dock to the reader, or make the reader explicitly a preview with one visible “Document actions” control. |
| D17 | P3 | About, 16:28 | A large black square with a rough “C” stands in for the app icon. Developer, support and privacy URL are plain text. | The About surface looks unfinished compared with the rest of the app. | Use the actual app mark, make privacy a tappable row, and keep support copy compact. |
| D18 | P3 | Home card overflow, 16:41 | A full-height text-only bottom sheet with five rows, no icons and no grouping. | The sheet is slower to scan than an icon list and hides the destructive action at the bottom. | Use compact rows with icons, group document vs danger, and keep Move to Trash visibly destructive. |

## What the device walk says to protect

- The Home search and snippets remain the strongest part.
- The page grid itself is visually coherent and the two-page count updated correctly after deletion.
- The imported image pipeline works without the camera; this is a useful recovery path for a face-down phone.
- The page-level B/W result can be good on suitable input.
- The direct route from Google scanner is not tested here because the rear camera was unavailable.

## What changed in the working tree but is not on this device

The candidate-D APK predates the uncommitted UX pass. The working tree reportedly adds:

- Vault entry-point hiding.
- One ink palette and no starfield in `BaseActivity`.
- A shorter import sheet and no “to organize” row.
- Tools as an even grid with no PRO band.
- Search fixes and direct save landing changes.

Do not assume those fixes are visible on candidate D. Build the current source, run the pending tests, then re-check D3, D4, D12 and D14 on device.

## Test-document note

During the D1 test, the existing fixture document **DocX** was changed from 2 pages / 1.5 MB to **1 page / 1009 KB**. Its page 2 (the contents page) was deleted and was not restored. The force-stop did not restore it.

This was a test fixture, not an evidentiary claim about every document. The behavior is now recorded as D1. Do not run further destructive edit tests without a backup copy of the document fixture.

## First device-verified scope for Opus

1. Fix the page-edit transaction and add a real Discard route. Prove whether the PDF and the page set can diverge.
2. Fix or remove the PRO surface; make Vault honest before entry.
3. Restore document actions and lock state to the reader/row.
4. Fix Resize keyboard/units/precedence.
5. Recheck B/W with a comparison/revert flow; do not repeat the video claim as universal.
6. Re-test the current WIP UI on device before touching the palette, Vault or Tools.
