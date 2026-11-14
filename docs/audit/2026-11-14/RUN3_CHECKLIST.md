# Run 3 regression checklist (every bug reported in runs 1-2 + lead verdicts)

For each row: FIXED / STILL BROKEN / CHANGED (describe) + run3 screenshot file. Nothing is "fixed" without a screenshot.

| # | Area | Bug (run, shot) | Expected now |
|---|---|---|---|
| 1 | Library | Search shows nothing (r1 037) | Name, OCR text and tag matches; "No matches for “x”" |
| 2 | Library | Sort "Newest" dead (r1 041) | Sheet Newest/Oldest/Name A-Z/Name Z-A/Size; kept after relaunch |
| 3 | Library | Keyboard puts nav mid-screen (r1 036) | Nav + disc hidden while keyboard is open |
| 4 | Library | Last card sliced by nav (r2 001) | Last card fully visible when scrolled to the end |
| 5 | Library | Stray cyan arc under empty state (r2 410) | Gone |
| 6 | Library | Move to Trash without confirm (r1 193) | Confirm sheet, from library and reader |
| 7 | Trash | Empty Trash dead / silent (r1 100, r2 121) | Hidden when empty; confirm sheet when not |
| 8 | Tools | Labels clipped, tags overlap icons (r1 070, r2 061) | Full labels; tag in tile corner, never on icon (all 44) |
| 9 | Tools | Searchable PDF opens Settings (r1 080) | Chooser -> "Make searchable" sheet -> runs |
| 10 | Tools | Search keeps query, no clear; "read" ranks Book pages first (r2 303, 352) | X button; reset on tab re-entry; "read" -> Read text first |
| 11 | Tools | Inconsistent tool containers (r1 077/074/078) | Every document tool: same chooser first |
| 12 | Chooser | Plain text rows; stacked names "(compressed) (signed)…" (r2 011) | DocCard rows with thumb + meta; search > 8 docs; names "X - Signed", "(2)" |
| 13 | Tools | "1 pages" (r1 133, r2 062) | 1 page / 2 pages everywhere (tools, vault, protect, cards) |
| 14 | Tools | Result "Show in library" opens Tools tab (r1 136) | Library tab, new card highlighted |
| 15 | Tools | Locked source dead end "Unlock first" (r1 160, r2 198) | Unlock sheet opens; correct password continues the tool |
| 16 | Tools | Compression chips no selected state (r2 193) | Selected style, default Medium |
| 17 | ID card | Camera forced first (r2 052, 347) | "Front side" sheet: camera / gallery; back: camera / gallery / front only; Layout sheet |
| 18 | Sheets | Translucent sheets (r1 066, r2 011) | Fully opaque |
| 19 | Reader | More sheet rows without icons; "Lock PDF" (r2 032) | Icons on every row; "Protect PDF" |
| 20 | Reader | Saved snackbar ellipsized over dock (r1 048) | Full name above the dock + Open |
| 21 | Text | Bare text buttons, no icons (owner) | Icon chips; Tidy text sheet; undo/redo icons; language chip; 1-line footer |
| 22 | Text | Cleanup resets tab; Copy/Save silent; To Word "being built" (r1 053, 060, 064) | Tab kept; "Copied"; saved path + Open; To Word runs |
| 23 | Text | Page shown sideways (lead) | Real orientation |
| 24 | Review | Category rail last chip clipped; long dash (r1 004) | Scrolls with end padding; no dash |
| 25 | Crop | Presets don't change frame; no handles; toolbar under panel (r1 022/023) | Preset reshapes frame; 8 handles; only Cancel/Apply in crop mode |
| 26 | Organize | Big select boxes + drag boxes always on; Move-to buttons (owner) | Clean tiles; long-press selects; drag to reorder; selection bar |
| 27 | Organize | "Your edits are not in the original PDF yet" (r2 041) | Draft bar "Draft · N pages · K changes" with Discard / Replace / Save as new |
| 28 | Stamp | First Back swallowed (r2 285) | First Back shows "Discard edits?" when there are marks |
| 29 | PageViewer | Rotate -> Back drops edit silently (r2 252) | Edit kept in the draft (saved doc) / asks in scan review |
| 30 | Vault | Passcode fails after upgrade, closes silently (r2 021-023) | Passcode unlocks; wrong one shows "Wrong passcode. N tries left." |
| 31 | Vault | Fingerprint promised, no control (r2 021b) | "Use fingerprint" button when enrolled; else "Enter your passcode." |
| 32 | Vault | "7 files protected" vs 1 document (r2 027) | "1 document protected" |
| 33 | Vault | Setup fields behind keyboard (r1 105) | Fields + button visible above keyboard |
| 34 | Vault | Empty area when locked; toast over keypad (r2 022, 038) | "Unlock to see your files."; reason line inside sheet |
| 35 | Vault tab | "No ads" pill; half screen empty (r2 020) | "Offline · No ads"; live status card |
| 36 | Protect | Remember + buttons hidden with keyboard (r2 034b) | Sheet scrolls; button reachable |
| 37 | Protect | Save protected copy lost when Remember ON (r2 037) | Save completes; Vault step silent |
| 38 | Import | Password-protected PDF dead end (r2 046) | Unlock sheet + saved-password button; imports a new document |
| 39 | Book pages | Line snaps to clutter; no Keep as one page (r2 014) | (L3 redo in progress: check what this build does and report) |
| 40 | Book pages | Long truncated name (r2 016) | "Book 14 Nov" |
| 41 | Header | Logo not the app icon (owner) | Two rings + page mark |
| 42 | Icons | Wrong icons (owner, r1 081) | Official icons everywhere (nav, Me rows, search, protect, draw, highlight) |

New features to cover end to end in run 3: Folders & tags, Batch rename (+Undo), QR code (camera face down: scanner opens), Business card (gallery card photo), To PowerPoint, To Word with pictures, Insert pages (if merged), Hindi language chip (if merged).
