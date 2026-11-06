# ScanWhirl test23 — phone checklist (holographic globe home)

APK: `ScanWhirl-1.1.1-test23.apk` (package `com.scanwhirl.app.test`, versionCode 23)
Install over test22. All your scans, PINs and settings carry over.

## A. Globe renders and rotates
1. Open the app → dark space screen with stars and a **dotted Earth** (green/cyan
   continents over a dark globe, latitude rings, slow shimmer sweep).
2. Earth rotates slowly on its own (1 turn per minute). Drag it → it spins with
   your finger and keeps a little inertia after release.
3. Recent documents orbit the globe as small cards (front cards pass in front of
   Earth, back cards go behind it).

## B. Tap Earth → five category rings
4. Tap the Earth → it shrinks upward and **five rings** expand below it:
   Study, Receipts, IDs, Forms, Other (inner → outer).
5. Each ring carries document cards spinning along it with **real first-page
   thumbnails**, a category tag and the document name.
6. Drag a ring → only that ring spins.
7. New scans auto-sort: "Receipt…" → Receipts ring, "notes/exam" → Study ring,
   "passport/ID" → IDs ring, "form/application" → Forms ring, anything else →
   Other ring. (Classification uses the document NAME only — check Shelf names.)

## C. Pull-down search
8. From the top of the globe screen, pull down → search panel slides in.
9. Type a name → matching cards stay bright (cyan border + dot), non-matching
   cards dim to ~12%. Counter shows "Found x of y matching".
10. Vaulted documents never appear in the count or results (as on the Shelf).

## D. Locked / vaulted privacy (far side)
11. Lock a PDF (viewer → Protect PDF) or vault a doc → back on the globe its
    card is a **padlock glyph on the far side of the rings** — no thumbnail,
    no document name, ever. It never lights up in search.
12. Tap the glyph (after it rotates to a tappable spot) → the existing
    password / vault-PIN prompt appears; correct entry opens the editor.

## E. Pinch → Shelf (list)
13. Pinch inward on the globe → cross-fades into the familiar **Shelf list**
    (all your test22 list behaviour: search, sort, select, tools row).
14. Shelf behaves exactly as before: filters, merge, compress, OCR, vault.
15. Back from the Shelf → globe home again, still rotating.

## F. Settings toggle
16. Settings → "Globe home screen" switch (default ON).
17. Turn it OFF → next app open goes straight to the Shelf (no globe).
18. Turn it ON → globe home is back. (Also: Shelf → gear → Settings.)

## G. Scan flow
19. Camera FAB at the bottom of the globe → Shelf opens **directly on the
    Camera/Gallery chooser** (same chooser as before).
20. Complete a scan → it lands in the correct ring when you return.

## H. Category override (editor)
21. Open any document → overflow menu (⋮) → **Categorize** → pick a category
    → reopen the globe: the card moved to the new ring. Rename the doc →
    category sticks.

## I. Empty state
22. On a fresh install (or with all docs deleted): globe + stars with
    "No documents yet" hint; scan FAB works.

## J. No regressions (test22 items that must still pass)
23. Tilt/rotate pages in the editor still works; export writes real files.
24. Device PDFs open in the in-app viewer (seekable — no freeze on big PDFs).
25. OCR text extraction still copies real text (Pro preview).
26. Gallery import still lands in review (no scanner crop overlay).
27. Free UI shows no Pro-only tools; Pro preview switch still works.
28. Vault PIN / lock password prompts unchanged and working.

## Performance
29. Globe should stay smooth (30 fps cap) with 0 and with 100+ documents;
    no battery-draining loop when the screen is off or you're in the Shelf.
