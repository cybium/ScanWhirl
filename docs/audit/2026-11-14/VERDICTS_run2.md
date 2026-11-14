# Lead verdicts: QA run2 (build 435e0f3), screenshots looked at by the lead

Source: `C:\Users\Cybium\Desktop\.sw-qa\run2\*.png` (55 shots, all viewed). FIX = sent to the named lane.

| Screen / sheet | Shot | Verdict |
|---|---|---|
| Library (Home) | 001, 051 | Brand mark, icons, quick row OK. FIX L1: last card sliced by nav/disc (RecyclerView bottom padding). |
| Library search | 002 | OK in this build (finds "swqa"). |
| Sort "Newest" | 003 | FIX L1: dead (no sheet). |
| Tools hub | 004, 060, 061 | Labels OK, Soon wins over PRO OK. FIX L1: PRO tag overlaps icon (Page numbers, To PowerPoint, Draw, Watermark, Stamp, QR). |
| Scanner (camera) | 005-008, 052 | Opens. Camera face down: capture not judged. |
| Book pages (empty) | 010-011 | OK, plain. |
| Import sheet | 010_05 | OK. |
| Passport | 010_06 | Opens gallery picker over its screen. OK. |
| Pick a document (chooser) | 011_bookpages_picker | FIX L1: plain text rows, no thumbnails/meta. FIX L4: stacked names "(compressed) (signed) (watermarked)" -> OutputNames. |
| Book pages preview | 014, 015 | FIX L3: line at ~63% (clutter), no "Keep as one page". |
| Book pages result / viewer | 016 | FIX L3: name too long, truncated. Page tiles show drag-handle boxes: L5 grid redesign. |
| Vault tab entry | 020 | FIX L1: "No ads" pill inconsistent; half screen empty, no status. |
| Vault unlock sheet | 021, 021b, 023 | FIX L2: passcode fails after upgrade (CRITICAL); fingerprint promised, no control. |
| Vault locked | 022, 024-025 | FIX L2: empty area under "Private files" when locked. |
| Recovery code sheet | 026-027 | Works. OK. |
| Vault unlocked | 028, 043 | FIX L2: "7 files protected" vs 1 document; "1 pages". |
| Document More sheet | 032, 032b | FIX L1: rows have no icons; "Lock PDF" -> "Protect PDF". |
| Protect PDF sheet | 033-036 | Look OK, strength meter OK. FIX L3: with IME the sheet cannot scroll, Remember + buttons hidden; "6 page". |
| Protect -> Vault save | 037, 038 | FIX L3: save action lost; FIX L2: toast over keypad. |
| Locked doc viewer | 039-042 | FIX L3: verify thumbnails vs no-plaintext-under-lock invariant. "Save changes? ... not in the original PDF yet": L5 PM-2.2c removes. |
| System Downloads (export check) | 044-045 | Exports present. OK. |
| Import password-protected PDF | 046 | FIX L3: dead end ("Got it" only). |
| (QA left the app into a browser) | 050 | QA instructed to stay in app and report the button. |
| ID card | 052 | FIX L4: camera only, no gallery choice. |
| To PowerPoint setup | 062 | Look OK. FIX L4: "1 pages". |
