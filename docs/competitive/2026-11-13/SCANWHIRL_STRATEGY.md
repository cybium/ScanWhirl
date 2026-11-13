# What ScanWhirl should do with this research

## Constraints

- 100% offline. No accounts, no server processing, no cloud OCR, no analytics, no ads, no watermark.
- Google ML Kit supplies the scanner and OCR. Do not try to rebuild a custom camera.
- The app already has strong local search, page snippets, page editing, merge/compress/resize and Quick sort.
- Do not ship a Pro badge or purchase promise until there is a real Pro product and a working billing path.
- Any “Drive backup” idea must be a separate product decision. It is not part of this competitive pass.

## The structural lesson: put tools where ML Kit cannot stop you

The competitors with strong tool models do not rely on their scanner for everything:

- **Adobe** puts modes and actions in a Home scan sheet, then a document dock.
- **CamScanner** uses 8 Home shortcuts, 19 All-Tools entries, 11 PDF Tools and a tabbed editor; its 194 MB/46-permission APK shows the cost of that breadth.
- **ACE** asks users which tools to put on Home.
- **TapScanner** puts Scan Doc / Gallery / Import File and Popular Tools on Home.
- **Lufick** separates Home, PDF Tools and Import ID Photo.
- **SwiftScan** puts the post-save export row in front of the user.

ScanWhirl currently puts its tools in a bottom-sheet list and hides saved-document actions in a row overflow. That is the core IA problem to fix.

## Recommended Home architecture

```
ScanWhirl                                  [PRO / Settings]
[ Search names and text                         ]
[ Scan ]   [ Import ]   [ New document v ]

Quick tools (user-selected, max 6)
[ Read text ] [ Merge ] [ Compress ]
[ Resize ]    [ Quick sort ] [ Lock ]

[ All tools ]                         [ Folders ]
----------------------------------------------------------------
Recent documents
[ thumb ] Title                        1 page · 480 KB
          Study · Search ready          [Share] [More]
```

Specific rules:

1. **Scan and Import are both first-class.** No corner-only Import.
2. **A “New document” sheet** offers Scan pages / Import photos / Import PDF / Merge. This is the Adobe scan-sheet pattern, not a custom camera.
3. **Quick tools live on Home.** Default to six: Read text, Merge, Compress, Resize, Quick sort, Lock. Offer “Customize Home tools” and an “All tools” grid. This is the ACE pattern.
4. **Quick sort** can remain a banner/shortcut, not a full-width hero tile every time.
5. **Folders** belong in the library, not only a fixed category chip. Categories are quick filters; folders are hierarchy.
6. **Document row actions** should expose at least Open, Share and More. Do not require opening a reader that has no action dock.

## Recommended document reader

```
[Back]  Title                          [Lock] [Search] [Share]
──────────────────────────────────────────────────────────────
                    page / page stack
──────────────────────────────────────────────────────────────
[ Share ] [ Edit ] [ Text ] [ Export ] [ More ]
```

- The dock is always visible.
- `Edit` opens the page grid; `Text` opens OCR; `Export` shows PDF/JPEG/TXT/Word/Excel only if implemented; `More` holds lock, rename, folders, duplicate, trash.
- A lock badge appears on Home rows and in the reader when a PDF is protected.
- Delete/rotate/crop/filter remain page-level actions in the grid.
- “Select” appears only when there are 2+ pages.

## Naming and retrieval

Implement the Adobe naming pattern locally:

- Tokens: scan type, date, time, category, page count.
- Show a live example before applying.
- Default: “Scan 13-Nov-2026”.
- Allow rename at save and from the success state.
- Add folders and tags; keep the existing category chips as a fast filter.
- Keep search across names and OCR text, including page-level snippets. This is already a strength.

## What Pro should sell

The current free tier gives away the things competitors charge for: OCR, search, merge, compress. The current Pro gives only Lock and Vault, and Vault is broken. That is why Pro feels worthless.

### Free forever

- Scan and import.
- Basic OCR and in-app search.
- Page edit, crop, rotate, filter.
- Merge, compress, resize.
- Basic PDF export/share.
- No ads, no watermark, no account.

### Pro — the offline “Power Pack”

1. **Advanced OCR**: language packs, layout/table-aware text, headings/columns, Word/Excel/TXT export.
2. **PDF finishing**: PDF/A, searchable-PDF controls, metadata, page numbers.
3. **Document signing**: saved signature, date, initials, text annotations.
4. **Secure redaction**: flatten and verify removal, not a black brush over the image.
5. **ID card**: front/back guided capture into one page.
6. **Book mode**: split two-page spread, dewarp/straighten, margin cleanup.
7. **Batch operations**: apply filter/crop/rotate/OCR to selected pages or documents.
8. **Naming and filing automation**: templates, default folder, auto-export to local Documents.
9. **Folders/tags and app lock/Vault** (only when Vault is real).
10. **Optional local backup** via the Android file picker or a user-selected folder.

Do not put “remove ads,” “premium themes,” or “VIP support” in a no-ads app.

## What not to copy

- **No full-screen ads.** SwiftScan and ACE lose trust immediately; Adobe shows an ad strip in free.
- **No watermark.** SwiftScan’s “Scanned with SwiftScan” is a visible tax on the document.
- **No testimonial carousel before the scanner.** iScanner and Tiny Scanner spend the first minutes selling.
- **No forced account.** TapScanner allows “Continue without account”; that is the only acceptable account model if one is ever added.
- **No opaque weekly trial.** iScanner’s funnel is the anti-pattern.
- **No vague Pro features.** Lufick’s “Premium Features” and “VIP Support” say nothing.
- **No cloud dependency.** Adobe’s “processed in and saved to Adobe cloud” is the exact thing ScanWhirl promises not to do.
- **No feature-suite bloat.** CamScanner’s 41-tool catalogue, 340 activities, 46 permissions and 194 MB APK are evidence of what not to ship. Build the 10 offline tools that matter.

## Pricing recommendation

Observed India market:

- Notebloc: ₹750/year or **₹1,350 lifetime**, ad removal only.
- Lufick: **₹799/year** for ad removal/themes/support.
- SwiftScan: **₹2,350/year** for OCR/signature/cloud/AI.
- vFlat: **₹2,999/year** for book cleanup/OCR/Word.
- Genius: **₹4,250/year** for OCR/PDF-A/security/tags/cloud.
- Adobe: feature ceiling, price not exposed in the app.

**Recommended ScanWhirl offer:**

- **Free core** as above.
- **Founder lifetime: ₹1,499** (regular ₹1,999), one-time.
- Optional **annual updates/maintenance: ₹599–₹899/year** only if it funds ongoing language packs and support.
- No weekly plan. No trial that silently converts. No ads/watermarks.

Why: no server cost, strong privacy positioning, and a lifetime anchor already exists at ₹1,350 (Notebloc, ad removal only). A one-time Power Pack at ₹1,499 is credible if it includes OCR formats, signature, redaction, ID/book and folders. A ₹4,250 subscription without cloud/AI would be hard to defend.

## Priority order

1. Fix trust/data integrity: page-edit discard and PDF/page-set consistency.
2. Make Home and document actions visible: Scan/Import/New document, quick tools, reader dock.
3. Replace the PRO dead end with either a real plan or no PRO badge.
4. Add naming templates, folders/tags and lock badges.
5. Build the Pro Power Pack: advanced OCR, PDF/A, signature, redaction, ID, book, batch.
6. Add optional local backup/export through Android’s picker; leave cloud/account out of scope.
7. Revisit AI only if it can run fully on-device and is clearly optional. Do not add server AI.

## PDF Manager follow-up

The detailed PDF Manager product spec is in `../../product/pdf-manager-2026-11-13/`. It turns the PDF-tool gap above into a concrete hub, page organizer, tool matrix, Free/PRO split and one-time pricing proposal.

## The one-sentence strategy

**Be the offline, no-ads, no-account scanner with the best document retrieval and the most useful local finishing tools — not a weaker clone of Adobe’s cloud or SwiftScan’s ad-supported tool chest.**
