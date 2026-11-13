# ScanWhirl — first-time-user audit (Infinix X6711, Android 14, build D `ed3055d1…`)

Walked every reachable screen over ADB on 2026-11-13, phone held over a real book.
Screenshots: `evidence/a01…a5_*.png`. Owner direction: Google ML Kit scanner only,
no feature work beyond fixing, speed and clarity first.

## Defects (wrong behaviour)
| # | Where | What happens | Cause |
|---|---|---|---|
| D1 | Home search | "art" matches "st**art**", "ear" matches "l**ear**n"; "Document 2" also returns Document 1 and 3 | `SearchMatcher` uses substring `contains`; a multi-word query may match one word in the *name* and another anywhere in the *text* |
| D2 | Document → More → **Hide in Vault** | nothing visible (only a toast) | new Vault items are disabled (O-02) but the row is still offered |
| D3 | Tools → **Vault** | sheet closes, nothing opens on a phone without a legacy PIN | same; tile shown to everyone |
| D4 | After Google's Next | lands on Home; owner wants the page overview to check order | M08 direct save went to Home |
| D5 | OCR of a two-page spread | columns interleaved, noisy text → poor search | Google returns one image for an open book; ML Kit block order |

## Clutter and inconsistency
| # | Where | Problem |
|---|---|---|
| C1 | All secondary screens | **Three visual styles**: Home/Review/Trash use the ink palette; Settings, document screen, page editor, text editor and More sheet use the old navy "hologram/glass" palette with a star-field window background (`BaseActivity` sets `SpaceBackgroundDrawable` for every screen) |
| C2 | Home cards | every card spends a line on "Unsorted" + "✓ Search ready" (true for all 8) → ~3.5 cards per screen; serif titles vs sans UI |
| C3 | Home | "Unsorted" repeated three times (chip, "8 to organize · Quick sort ›" row, each card) |
| C4 | Tools | Quick sort is a full-width hero tile; orange **PRO** section for Lock/Vault although Pro is not sold |
| C5 | Import sheet | subtitle + two paragraph descriptions + footnote to choose photos vs PDF |
| C6 | Trash | two explanatory paragraphs around an empty list |
| C7 | Settings | Wallpaper and Status line toggles — decoration, not document work |
| C8 | Document screen | big "Select" pill above a single page; drag handle covers page text; uppercase "UNSORTED" pill vs "Unsorted" elsewhere |
| C9 | Search results | each result ~500 px tall → two results per screen |

## What works and stays
Google FULL scanner (1.1 s to camera, auto-capture, crop/filters/retake); direct save 234 ms
(copy 74 + verified save 159); OCR "Search ready" within ~4 s; page-accurate search hits and
snippets; Quick sort sheet; Merge/Compress/Lock dialogs; Trash + Undo; PDF export verified
independently (pypdf + MuPDF).

## Fix plan (one pass, then full gates + device re-walk)
1. D1 search: whole-word prefix matching (numbers match whole numbers); every term must be
   found in the name, or all of them in the text — never split across the two.
2. D2/D3: Vault entry points appear only for someone who already has a legacy Vault PIN.
3. D4: Google Next → saved → opens the document screen (page overview, drag to reorder) with
   "Saved" + Undo; back goes Home with the new document on top.
4. C1: one palette. Window background ink everywhere; old hologram/glass colours and
   drawables re-pointed to the ink tokens in one place (no per-screen rewrite).
5. C2/C3/C9: cards show category only when one is set and text status only when it is not
   ready; sans titles; remove the "to organize" row (Quick sort stays in Tools).
6. C4: Tools = one even grid; no PRO band; Vault only per item 2.
7. C5/C6: one short line per Import option; Trash note one line, empty state one line.
8. C7: remove Wallpaper and Status line from Settings.
9. C8: "Select" only with 2+ pages; smaller drag handle; category pill in sentence case.
10. D5: not changed in this pass (needs page-spread splitting, which Google does not offer);
    recorded as a known OCR limit for book spreads.
