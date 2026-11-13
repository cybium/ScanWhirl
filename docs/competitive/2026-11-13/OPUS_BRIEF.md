# Opus brief — competitive context

Read this after `docs/audit/2026-11-13/HANDOFF.md` and `DEVICE_FINDINGS.md`.

## What the benchmark says

1. **Genius Scan** is the closest strategic peer: free core, no ads, no forced account, clean document flow; Ultra at ₹4,250/year sells OCR, PDF/A, password, tags and cloud.
2. **Adobe Scan** is the feature ceiling and the best Home/tool architecture: Home/Files, a scan-options sheet, document quick actions, a real reader dock, naming templates, preferences and Office export. It is cloud-first and has ads in free.
3. **SwiftScan** proves the value of a visible post-save export row and full-page review, but its watermark/ads/filter gates are hostile.
4. **vFlat** has the best capture feedback and permission explainer.
5. **ACE Scanner** has the strongest “choose which tools go on Home” onboarding—the right answer to an uncustomisable ML Kit scanner.
6. **TapScanner** shows scan/gallery/import plus a tool shelf and optional account, but feature sprawl.
7. **Notebloc** proves the lifetime price anchor and ad-removal-only model.
8. **Lufick** shows folders/tabs but a thin Pro offer.
9. **Tiny Scanner** and **iScanner** are the marketing/paywall-first anti-patterns.

## Non-negotiable ScanWhirl constraints

- 100% offline; no accounts, no server, no cloud OCR, no analytics, no ads, no watermark.
- No custom camera; ML Kit stays the scanner.
- No “PRO” badge or purchase UI until a real Pro feature set and billing exist.
- Free core must remain genuinely useful: scan, import, OCR, search, page edit, merge, compress, PDF.

## What to copy, in order

1. **Home tools shelf** (ACE): user selects 4–8 tools; defaults Read text, Merge, Compress, Resize, Quick sort, Lock. New document sheet: Scan / Import photos / Import PDF / Merge.
2. **Document action dock** (Adobe): Share / Edit / Text / Export / More on the reader, always visible; lock badge on rows.
3. **Document quick actions** (Adobe): Share / More / optional format action directly from Home rows.
4. **Naming templates** (Adobe): type + date + time + category, live example.
5. **Plan matrix** (Genius): Free vs Power Pack, concrete benefits, no mystery.
6. **Permission explainer** (vFlat): required vs optional with reasons, if a new permission is ever unavoidable.
7. **Post-save export row** (SwiftScan) only with formats ScanWhirl can produce locally.

## What to avoid

- Full-screen ads, persistent banner ads, watermarks, weekly trial traps, testimonial carousels, forced accounts, cloud-first storage, “VIP Support” as a benefit.
- Feature sprawl such as plant scan, calorie counter, Ask AI, fax. ScanWhirl is a document utility.

## Pricing direction

Recommended: **one-time Founder lifetime ₹1,499** (regular ₹1,999), or annual ₹599–₹899 only if it funds ongoing offline OCR language packs. Do not copy ₹4,250/year without cloud/AI.

Pro should include: advanced OCR (layout/tables/languages), Word/Excel/TXT export, PDF/A, searchable-PDF controls, signature, secure redaction, ID front/back, book split/dewarp, batch operations, naming/folder automation, folders/tags, working app lock/Vault, local backup/export.

## Questions to answer before proposing UI

1. Which 6 tools belong on the default Home shelf, and which are hidden behind “All tools”?
2. Should “New document” be a sheet on Home, a screen, or both?
3. Should categories become folders + tags, or should folders be separate from the existing chips?
4. Which three Pro features can be built fully offline and verified at high quality?
5. What is the one-time price and what is explicitly excluded from lifetime?
6. How will the app prove “offline only” in the UI beyond a sentence in About?
7. Which competitor pattern will be deliberately rejected to keep ScanWhirl calm?
8. What does the user see after the first scan: Home, document reader, or review?

## Deliverable for the next product session

A one-page proposal, no code: Home IA, new-document sheet, document dock, default tool shelf, Pro bundle and price, and the three things ScanWhirl will deliberately not build.
