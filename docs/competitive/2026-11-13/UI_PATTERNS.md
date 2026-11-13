# UI patterns worth analysing

These are interaction patterns, not visual copies. The target is an offline, no-ads, no-account ScanWhirl. A pattern can be good even if its host app’s business model is wrong.

## 1. Adobe’s scan-options sheet is the best “what do I want to make?” entry

**Evidence:** tapping the blue camera FAB on Adobe Home opens a large sheet with:
- Combine files (premium star)
- Create from photos
- AI mode (Beta)
- High-speed scan (premium star)
- Create scan

`01_adobe_camera4.png`

**Why it works:** it separates “new capture”, “from photos”, “combine” and “AI” before the camera. It explains the premium marks inline. It also gives a natural home for tools that Google’s ML Kit camera cannot provide.

**For ScanWhirl:** the Home scan button should not immediately fire one fixed action. It should open a compact, offline “New document” sheet: Scan pages / Import photos / Import PDF / Merge. Keep AI and high-speed out unless they are local and real. This is the best place to expose document modes without touching the ML Kit camera.

## 2. Adobe’s document card exposes actions without opening the document

**Evidence:** saved scan card on Home:
- Share
- Save as Word doc (premium star)
- Digitize (Beta)
- More

`01_adobe_home_saved.png`

**Why it works:** the user can act on the document from the library. ScanWhirl’s saved reader has only Find; Share/Edit/Text/More are reached through Home → row overflow → Edit pages.

**For ScanWhirl:** a Home document row should expose Open, Share, and a compact More; a “Save as…” action should exist if formats are added. The reader should also own a visible action dock. Do not force a trip to Home to reach Text or Share.

## 3. Adobe’s document viewer gives the document a real dock

**Evidence:** Adobe Preview:
- Rename, Search, Share top
- Generative summary strip
- Full page
- Digitize / Modify / Edit text / Acrobat / More bottom
- Floating AI orb

`01_adobe_document.png`

**Why it works:** the document is the centre; tools are at the bottom within thumb reach. The top is identity and retrieval.

**For ScanWhirl:** adopt the bottom dock pattern with **Share / Edit / Text / More** on the reader itself, not only page grid. Keep the page count visible. Move lock state into a top badge and a document action.

## 4. ACE’s “choose the tools that go on Home” is the strongest answer to ML Kit

**Evidence:** onboarding screen:
“Which tools are you interested in? We've curated popular tools for you. You can select which ones to add to your Home page for quick access.”
A 15-tool grid: Scan documents, Scan ID cards, Scan passports, Import files, Extract text, Import images, Share as PDF/JPG/Text, Word to PDF, PDF to Word, Edit PDF/Word, Sign, Smart erase.

`09_ace_home3.png` and `09_ace_after_tools.png`

**Why it works:** it turns Home into a personal tool launcher and solves the “our scanner is ML Kit, so we cannot add custom modes to the camera” constraint. It is exactly the right layer for ScanWhirl.

**For ScanWhirl:** after first scan, ask which 4–8 tools the user wants on Home. Provide a fixed default set (Read text, Merge, Compress, Resize, Quick sort, Lock) and an “All tools” entry. Do not build a second camera.

## 5. TapScanner’s first row is scan / gallery / import, not an undifferentiated FAB

**Evidence:** TapScanner Home:
- Scan Doc
- Gallery
- Import File
- Popular Tools
- Scan Now card
- Home / Docs / Ask AI / All Tools bottom nav

`11_tapscanner_account3.png`

**Why it works:** the three ways to create a document are explicit. There is no guessing whether the camera FAB can import.

**For ScanWhirl:** keep Scan and Import visible, but give Import equal visual status. A single center scan disc plus a small corner import is not enough.

## 6. vFlat’s camera feedback is the best in the set

**Evidence:** live camera:
- Page outline
- “Too close to the document” warning
- Page count
- Flash, timer, auto mode
- Shutter, Library, Default Folder

`04_vflat_camera.png`

**Why it works:** it tells the user what is wrong before the capture. It gives a destination and a way back to the library. It is document-first.

**For ScanWhirl:** ML Kit already handles detection/capture. Borrow the *destination* and *status* ideas outside the scanner: show page count, destination folder/category, and a clear “return to library”. Do not attempt to recreate the vFlat camera.

## 7. Adobe’s naming template is the benchmark for file identity

**Evidence:** Preferences → Default file name:
- Tokens: Scan type, Year, Month, Day, Hours, Minutes, Date
- Example: “Adobe Scan 13-Nov-2026”
- Editable base name

`01_adobe_filename.png`

**Why it works:** it produces a useful name once and applies it consistently. The user never has to type a date.

**For ScanWhirl:** implement tokens (type, date, time, category, page count) and show a live example. Offer a per-save name suggestion, not a mandatory modal. Include “Rename” in the success state.

## 8. Genius’s plan matrix is the clearest paid boundary

**Evidence:** Basic vs Ultra columns:
- Free: high-res, unlimited, perspective, multipage PDF
- Ultra: password, OCR, PDF/A-1b, tags/search, cloud, auto-export
- Annual ₹4,250

`02_genius_compare_plans.png`

**Why it works:** it explains exactly what is paid and what remains free. The user can self-select. It does not pretend a core scan is premium.

**For ScanWhirl:** use a compact two-column matrix inside the app. Free vs Power Pack. Do not use a vague gold “PRO” band with two tiles.

## 9. SwiftScan’s paywall is well-structured; its ad model is not

**Evidence:** VIP sheet:
- Benefits with icons: Unlimited scans/uploads, Advanced AI, Cloud sync/auto-save, editing, signature, OCR
- Annual ₹2,350 (58% off), Monthly ₹470
- Start 3-Day Free Trial
- Restore purchase, Terms, Privacy

`03_swiftscan_paywall.png`

**Why it works:** the benefit stack is scannable, the price is visible, and the trial is explicit. The dark red/white system is consistent.

**What not to copy:** free output is watermarked, filter icons carry a crown, and Save triggered a full-screen ad (`03_swiftscan_library.png`). The library itself carries a banner ad (`03_swiftscan_library4.png`). This trains users to distrust the free tier.

## 10. Notebloc’s lifetime option is a useful price anchor

**Evidence:** ad-removal Pro:
- Monthly ₹100
- Annual ₹750 (37% discount)
- Lifetime ₹1,350
- “Remove ads now!” and “Continue with ads”

`05_notebloc_pro.png`

**Why it works:** the three price shapes are immediately comparable. The lifetime option is honest about what the product is.

**For ScanWhirl:** if you sell a one-time Power Pack, use the same three-shape clarity, but the middle card should be the recommended lifetime (₹1,499) and there must be a “Continue free” escape. No ads means no “remove ads” tier.

## 11. Lufick’s Premium page is a warning, not a model

**Evidence:**
- Header “GO PREMIUM” on loud orange
- Premium Features / Premium Themes / Remove Ads / VIP Support
- ₹399/3 months, ₹799/year (50% off)
- Tabs Plans / Details / Reviews / FAQ
- Ad Space at bottom of home

`08_lufick_pro.png`

**Why it is weak:** “Premium Features” and “VIP Support” say nothing. The page sells a mood, not a workflow. The price is cheap, but the value is undefined.

**For ScanWhirl:** never ship a Pro page whose feature names are “Premium Features,” “Powerful Tools,” or “VIP Support.” Name concrete outputs and workflows.

## 12. iScanner is the high-pressure funnel to avoid

**Evidence:** WebView onboarding with:
- 120M users and rotating testimonials
- Terms embedded in the flow
- “All Features. No Limits.”
- A 3-day trial followed by a weekly plan
- Multiple legal/privacy services, cloud, OpenAI, Mathpix

`07_iscanner_onboarding.png`, `07_iscanner_onboarding3.png`

**Why it is bad for this audience:** it asks for attention, trust and subscription before showing a working scanner. It is server-dependent and opaque. It is the opposite of “offline, no accounts, no servers.”

**For ScanWhirl:** show the scanner first. No testimonial carousel. No trial timer. No account before value. No hidden weekly plan.

## 13. Library models: folders, not one long card list

**Evidence:**
- Adobe Files: All scans, select multiple, create folder, sort, document row with size
- Lufick Home: Home / PDF Tools / Import ID Photo; folders New Folder, ID Cards, Bills; All docs / Recent / Favourite / Business Card
- Notebloc: My Docs, folders, Search, Trash, Backup/Restore

`01_adobe_files.png`, `08_lufick_home.png`, `05_notebloc_onboarding2.png`

**Why it matters:** the library becomes a filing system, not a feed. ScanWhirl’s chips are a category filter, not a folder tree, and there is no tag/search scope.

**For ScanWhirl:** add folders and tags behind the existing categories. Keep categories as quick filters; folders are the hierarchy. Use a compact row with title, page count, date, size and lock badge; put actions in a visible dock or one clear More.

## 14. What the ScanWhirl reader should learn from the set

The document viewer should not be a dead-end image list. The strongest pattern is:

```
[Back]  Title                      [Lock badge] [Search] [Share]
────────────────────────────────────────────────────────────────
                 full page / page stack
────────────────────────────────────────────────────────────────
[ Share ]   [ Edit ]   [ Text ]   [ Export ]   [ More ]
```

The bottom row should always be visible. Page grid remains the place for reorder/rotate/crop/filter. Home remains the place for folders/search/quick actions. The same document is reachable from all three without a hidden overflow chain.

## Scanner boundary

Google ML Kit gives ScanWhirl a capable, consistent scanner but not a custom camera. Do not try to reproduce Adobe’s Whiteboard/Book/ID/Business modes in the ML Kit view. Instead:

- **New document** sheet: Scan pages / Import photos / Import PDF / Merge.
- **After capture** review: page count, retake, crop, rotate, filter, delete, OCR.
- **Home tools**: user-selected tools that operate on saved documents.
- **Document viewer**: Share/Edit/Text/Export/More.

This is the offline equivalent of Adobe’s modes without fighting the scanner SDK.
