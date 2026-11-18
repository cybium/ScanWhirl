# ScanWhirl 1.2.2/1.2.3-test: heavy UI critique (lead, 2026-11-18)

Source: 174 device screenshots (qa\screenmap\shots, map in SCREEN_MAP.md); 40 key screens reviewed at full size
(qa\screenmap\sheets\board_A..E.jpg). Severity: **H** = hurts trust or loses work, **M** = confuses most users,
**L** = polish. "Fixed" = already fixed in 1.2.3-test or a running lane.

## Verdict in one paragraph
The app does a lot, and much of it works (no crashes in 174 screens). But it **looks like a toolbox, not a
document app**. Home spends ~60% of the screen on buttons before the first document. There are **five ways to
start something** and **three ideas of where a file lives**, every tool run **makes another copy**, and **each
tool screen is laid out differently**. Most of the owner's flaws come from those four patterns, not from single bugs.

## A. Structural problems (fix these and most small ones disappear)
| # | Problem | Evidence | Sev | Proposal |
|---|---|---|---|---|
| A1 | **Home has no hierarchy.** Search, a mixed chip row, 3 entry tiles, 6 quick tools, then "Recent" + sort + chips + "3 documents" all come before the first document. With documents, only ~1 card fits above the nav bar. | 043, 045 | H | Home = search + folders + documents. Move ID card / Photos / Import PDF into the Scan button's sheet; quick tools live in Tools. |
| A2 | **One chip row mixes three kinds of thing**: a filter ("All"), an action ("+ New folder") and a setting ("A/अ"). Folders then appear as more chips ("QA Folder (0)"), which won't scale past 3 folders. | 043, 051 | M | Separate folder row (or folder list); "New folder" inside it; A/अ in the header or in Me. |
| A3 | **Five ways to start**: Scan button, ID card tile, Photos tile, Import PDF tile, plus the Tools CREATE section (Scan, ID card, Photos to PDF, Import PDF, Passport, QR code). Duplicates confuse; this is the owner's F4/F6. | 043, 055 | M | One Scan button that opens: Document · ID card · Passport · Import photos · Import PDF. |
| A4 | **Three ideas of where a file is**: the in-app library, "Save to Documents" on results, and a setting "Also save a copy to Documents". People cannot tell whether their file is saved. | 012, 013, 116 | H | One truth: the library = real files in Documents/ScanWhirl (owner's decision). Drop the per-result "Save" and the setting. |
| A5 | **Every tool run makes a new document**: "SW_TEST_3pages - Compressed (3)", "Passport photo - Compressed (4)". A batch compress made 8 copies; pickers become long lists of near-identical names. | 110, 152-158 | H | Ask once on the result: Replace original / Keep both (owner's decision N3). |
| A6 | **Each tool page is a different form**: "SOURCE" list + "OPTIONS" + Continue (Merge, Split, To Word, Batch); plain list sheets (PDF/A, Sign); a thumbnail sheet (Searchable PDF); the system picker. Uppercase section labels, form-style pages. | 033, 071, 079, 096, 110, 112 | M | One picker (thumbnail sheet with search, owner's decision), then the tool's own options. |
| A7 | **The result screen ("Done") has 5 equal buttons** (Open, Share, Save, Show in library, Undo), shows a white page on a white box, and is titled "Done" whatever the tool. | 038, 066 | M | Title = what happened ("Compressed: 824 KB to 544 KB"); one primary action (Open), Share secondary, Replace/Keep choice; page on a dark backdrop. |
| A8 | **Photos enter documents without cropping** in two paths: ID card "Pick from gallery" (regression from last night: fixing in `fixidcrop`) and **Insert pages > Photos** (page 2 shows the wooden table). | 092, owner | H | Rule: every photo goes through auto-crop + corner adjust (the Passport crop screen already exists). |

## B. Security and privacy
| # | Problem | Evidence | Sev | Proposal |
|---|---|---|---|---|
| S1 | **The Vault recovery code reaches the keyboard's clipboard suggestions** (shown as a chip above the keyboard in another screen). Any app with a keyboard can then see it. | 148 | **H** | The app already marks the copy sensitive and clears the clipboard after 60 s (`VaultActivity.copyRecoveryCode` -> `ClipboardPrivacy`), yet the keyboard still offered the code 3 min later: the phone's keyboard keeps its OWN clipboard history and ignores the sensitive flag. App-side protection cannot fix that. Remove "Copy" for the recovery code; offer "Save as file" (a place the user picks) and "I wrote it down". |
| S2 | "Block screenshots of documents" is off by default. Protected documents are always blocked. | 116 | L | Fine as is. |

## C. Wording that confuses (EN; Hindi follows the same fixes)
| Where | Now | Problem | Better |
|---|---|---|---|
| Compression level | "Low · estimated 761 KB" ... "Ultra · 283 KB" | "Low" makes the file bigger: reversed meaning | "Best quality · 761 KB" / "Balanced · 555 KB" / "Smaller · 436 KB" / "Smallest · 283 KB" |
| Compression level | "...or compress up to a target size (optional)" | two ways in one sheet | Keep levels; target size behind "Custom size" |
| Settings | "Auto enhance scans: Google processing is on. Off gives a fast, original-looking scan." | double negative, names a vendor | "Clean up scans automatically" (on/off) |
| Settings | "Make PDFs searchable: Hides OCR text so PDF apps can search it" | "Hides" sounds bad | "Adds invisible text so other apps can search your PDFs" |
| Settings | "Also save a copy to Documents" | second storage idea (A4) | removed with real files |
| Trash | "Restored documents come back complete." | odd | "Restore brings back every page." |
| Viewer | "UNSORTED" (uppercase pill) | shouting, unclear | folder name in normal case, or nothing |
| Text language sheet | "Switching reads this document again and replaces unsaved edits" | warning hidden in a picker | removed with F18 |
| Results | title "Done" for every tool | says nothing | "Compressed", "Merged 2 documents", "Saved as Word" |
| Titles | "SW_TEST_3pages - Compre..." | names get cut | shorter generated names (A5 removes suffixes) |
| Merge | link "Merge PDFs from this phone" inside the list | a second picker hidden as a link | part of the single picker (A6) |

## D. Visual polish
| # | Problem | Evidence | Sev |
|---|---|---|---|
| D1 | Home background tint stops halfway (list paints its own background) | owner, 001/043 | M, fixing (`fixhome`) |
| D2 | PRO badges sit on top of quick-tool icons; labels are ~10 sp and some wrap | 043 | L |
| D3 | Grey-on-dark labels hard to read ("Front of the card", "Back of the card", empty slots) | 058-065 | L |
| D4 | Bottom sheets have both an X and a "Cancel": two closes for one sheet | 047, 049, 098 | L |
| D5 | "Soon" tiles still occupy the Tools grid (Organize pages, Batch rename) | 055 | L |
| D6 | The Pro badge, the "Offline · No ads" pill and section headers use 3 different pill styles | 043, 055, 114 | L |

## E. What is GOOD (keep it)
- Empty Home state: one clear message ("Scan your first document").
- The edit **draft bar** (Discard / Replace / Save copy) and "Replace?" that moves the old version to Trash.
- Compress shows estimated sizes per level and "Before 824 KB -> After 544 KB".
- Trash with Restore; delete confirmations that explain consequences.
- The Passport **corner crop** screen (reuse it everywhere, A8).
- The document dock (Share / Edit / Text / More) and the More sheet's grouping (Organize / Tools / Danger).
- Merge / Split explain what is missing; the Text screen's Tidy text options.

## F. Recommended order (before the next APK goes to you)
1. **S1** recovery code off the clipboard (security, small).
2. **A8** crop on every photo path (ID card: running; Insert pages > Photos: add).
3. **D1** background (running).
4. **C** wording fixes (one small lane, EN + HI).
5. **A7 + A5** result screen redesign with Replace / Keep (owner decision N3).
6. **A1-A3** Home + one Scan button sheet (needs a short design round with you first: it changes the first screen).
7. **A6** one picker (decided). 8. **A4** real files (decided, biggest).
Items 1-4 are safe to do now. Items 5-8 change what people see first: the lead shows a sketch before coding.
