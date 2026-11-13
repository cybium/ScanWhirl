# Per-app notes — deep walk

## CamScanner — `com.intsig.camscanner` 7.28.5.2611020000

A full deep dive lives in `CAMSCANNER_DEEP_DIVE.md`, including the APK analysis. In short: Home has 8 shortcuts and Recents; Files has import/create folder/multi-select; All has 19 visible tools; PDF Tools has 11; the editor has Images/Markup/Page tabs; ID Cards has General/ID card/Passport and an A4 composite preview; ID Photo Maker and To Word are separate camera modes; Me has premium/cloud/tasks/points/widgets/settings. The APK is 194.47 MB, 12,695 files, 14 dex, 84,681 classes, 522,684 methods, 340 activities and 46 permissions. This is why it is feature-packed, and why it is not a model for an offline app.

## Adobe Scan — `com.adobe.scan.android` 26.10.29

**First run:** Adobe data-usage notice, then “Your scans will be processed in and saved to Adobe cloud storage.” Google sign-in was already done by the owner. Camera and notification permissions accepted. A Google Pay ad appeared on Home; a “Go ad-free and unlock premium tools — Free trial” strip sits above it.

**Home:** two tabs, Home and Files, plus a central camera FAB. Empty state “You don’t have any scans.” The bottom camera is not a single action: it opens a sheet with **Combine files** (premium star), **Create from photos**, **AI mode (Beta)**, **High-speed scan** (premium star), **Create scan**. This is the strongest “new document” chooser in the set. `01_adobe_filebrowser.png`, `01_adobe_camera4.png`.

**Import from image:** selecting Create from photos first asks for full photo/video access (not the Android picker). It then shows its own picker with “Show only documents” and “Auto-crop” toggles, a small recent-photo grid and “Show all photos”. After selecting a page, it opens a full-page crop review: Adjust/Crop/Rotate, **Ask AI**, **Digitize Beta**, **Retake**, **Edit text** (clipped off the right edge), **Keep scanning**, **Save PDF** with a save-options arrow. `01_adobe_photo_picker2.png`, `01_adobe_import_review.png`.

**Saved state:** saving returns to Home with the card **Adobe Scan 13-Nov-2026**, “Just now”, thumbnail, **Uploading…**, and a horizontal action row: **Share / Save as Word doc (premium star) / Digitize Beta / More**. The Home header keeps the premium banner and an ad. `01_adobe_saved.png`.

**Document viewer:** title truncated, pencil rename, search, share; a **Generative summary** strip; the page; bottom dock **Digitize / Modify / Edit text (premium star) / Acrobat / More**; floating AI orb. `01_adobe_document.png`.

**Modify:** opens a page with **Ask AI, Digitize, Retake, Crop, Rotate, Edit text** along a horizontally scrolling row, then **Keep scanning** and **Save PDF**. The right-most tool is clipped. It did not expose a separate filter in this flow. `01_adobe_modify.png`.

**Preferences:** App theme; “Let me adjust borders after each scan” ON; “Automatically straighten each page” OFF; “Enable generative AI features” ON; “Run text recognition on saved PDF” ON; OCR language English; **Default file name**; **Default filter** app-defined. `01_adobe_preferences.png`.

**Default file name:** tokens for Scan type / Year / Month / Day / Hours / Minutes / Date; live example “Adobe Scan 13-Nov-2026”. This is the best naming UX in the set. `01_adobe_filename.png`.

**Subscriptions:** Premium list is concrete: **Magic eraser, Edit text, High-speed scan, Export to Word/Excel/PowerPoint, Combine, Compress, Protect with password, 100-page OCR, 20 GB Adobe cloud storage**. Price was not shown; the screen pushes “Subscribe to premium” and then required another step. `01_adobe_plans.png`, `01_adobe_premium.png`.

**Verdict:** the feature ceiling and the best Home/tool architecture. Cloud-first, ads in free, full photo permission, clipped tool row and truncated title are the cautions.

## Genius Scan — `com.thegrizzlylabs.geniusscan.free` 7.44.1

**First run:** a paywall appears before the welcome screen: “Genius Scan is free to use,” cloud backup, faster workflows, enhanced security, all advanced features; “Get 1 week free, then ₹4,250.00/yr”; **Continue with Genius Scan Basic**; Restore purchases; Compare plans. There is no forced account. `02_genius_onboarding.png`, `02_genius_ultra_paywall.png`.

**Compare plans:** two-column Basic vs Ultra. Basic includes high-resolution scanning, unlimited scans, perspective correction and multipage PDF. Ultra adds password encryption, OCR, PDF/A-1b, tags/search and cloud workflows. Annual ₹4,250 (21% off). This is the clearest free/paid boundary observed. `02_genius_compare_plans.png`.

**Home:** “Welcome on board!” with Import photos / Scan your first document, bottom “Upgrade to Genius Scan Ultra” strip. After import, the document page shows a date/time title (2026-11-13 17:23), Add tag, Text recognition, Document history, Export, More, and a page thumbnail. The page is small in a large black canvas. `02_genius_welcome.png`, `02_genius_editor.png`.

**OCR:** tapping Text recognition opens the Ultra paywall; OCR is not free. `02_genius_ocr_gate.png`.

**Verdict:** the best strategic model for ScanWhirl: offline-ish, no ads, free core, concrete Ultra matrix. Weak spots: OCR paywall, small page preview, generic date title.

## SwiftScan — `net.doo.snap` 11.0.1(721)

**First run:** Terms of Service/Privacy dialog, then a four-screen onboarding carousel (Scan anything, Professional Looking PDFs, Send/Upload/Annotate or Fax). Then **SwiftScan VIP**: Unlimited Scans & Uploads, Advanced AI Tools, Cloud Sync & Auto-Save, Powerful Editing Tools, Electronic Signature, Text Recognition (OCR). Annual **₹2,350/year** (58% off) or **₹470/month**, 3-day trial. `03_swiftscan_onboarding.png`, `03_swiftscan_paywall.png`.

**Home:** SwiftScan red/white, Search and New Folder, empty illustration, AI button and + FAB; a banner ad at the bottom. `03_swiftscan_main.png`, `03_swiftscan_library4.png`.

**Camera:** Multi-Page, Light, Auto Focus, Automatic, Auto Size, Cancel, shutter, Import, and a persistent ad. A real scan produced a green page boundary and “1 page”. `03_swiftscan_camera.png`.

**Review:** full-width page, date/time title, **Add pages / Filter (crown) / Crop / Rotate / Delete**, watermark “Scanned with SwiftScan”, plus a banner ad. `03_swiftscan_review.png`.

**Save:** saving triggered a **full-screen Google Gemini ad**; after back, a save-success/share screen with PDF title, timestamp, watermark, **Edit / Remove Watermark / AI Tools** and **Share / Email / JPG / Fax / Drive / OneDrive / Dropbox / Print**. `03_swiftscan_library.png`, `03_swiftscan_library2.png`.

**Library:** Search, New Folder, Recent, document rows, Share/Email/Fax/Move/More, AI Tools and Scan buttons, persistent ad. `03_swiftscan_library4.png`.

**Verdict:** strongest document export utility and a good paywall layout, but the free experience is hostile (watermark, full-screen ad, banner, gated filters).

## vFlat Scan — `com.voyagerx.scanner` 1.16.10

**First run:** “Preparing your device”, then the best permission screen in the set: Required camera “Used to capture and scan documents”; Optional notifications “Used to notify you when tasks such as text recognition are complete”; note that optional features can be skipped. `04_vflat_onboarding2.png`.

**Terms:** updated terms and privacy, Accept and begin. Then a seven-dot onboarding carousel.

**Premium:** ₹2,999/year (save 16%; ₹3,588 crossed out) or ₹299/month. Benefits: clean scans, fingertip removal, AI note/annotation removal, curved-page flattening, PDF/Word/images, advanced OCR. `04_vflat_home4.png`.

**Camera:** live page outline and **“Too close to the document”** warning; page count, flash, timer, Auto (crown), more; shutter, Library, Default Folder. This is the best capture feedback observed. `04_vflat_camera.png`.

**Library:** “vFlat Library,” search, referral card (“Create 50 more PDF pages / For every friend that joins…”), one scanned page, Default Folder, create folder, Import/Create PDF/Create ZIP/Delete/More. Large empty area. `04_vflat_library.png`.

**Verdict:** best camera and book/document capture feedback; strongest permission explainer. Library is referral-heavy and less refined. Premium is expensive but explains real cleanup work.

## TapScanner — `pdf.tap.scanner` 3.1.24

**First run:** polished “The Everything Scanner” onboarding, then pages for Document Scanner, AI Plant Scan, AI Count objects and Ask AI. Ends at a Google/email account screen with **Continue without account** available. No forced login. `11_tapscanner_onboarding.png`, `11_tapscanner_account.png`.

**Home:** Scan Doc / Gallery / Import File row; Popular Tools (IMG to PDF, Scan ID [Pro], Add Folder, Calorie Counter, Plant Scan); Scan Now card; bottom Home / Docs / Ask AI / All Tools. `11_tapscanner_account3.png`.

**Premium:** top-right “Upgrade to Premium” opens no readable pricing in this walk. `11_tapscanner_premium.png`.

**Verdict:** good scan/gallery/import entry and optional account. Feature sprawl (plant scan, calorie counter, Ask AI) dilutes the document promise.

## ACE Scanner — `pdfscanner.scan.pdf.scanner.free` 1.5.2

**First run:** full-screen ad; language picker (13 languages incl. Arabic, Hindi); onboarding; then the key screen: **“Which tools are you interested in? … add to Home page”** with 15 selectable tools: Scan documents, ID cards, passports, Import files, Extract text, Import images, Share as PDF/JPG/Text, Word to PDF, PDF to Word, Edit PDF/Word, Sign, Smart erase. `09_ace_home3.png`.

**Home:** selected tool grid (Smart scan, ID card, Import files, Import images, Share as PDF, Edit PDF, Sign, More tools), My Scans, example file, Tap to start scanning, Home/All files nav, ad area. `09_ace_after_tools.png`.

**Pricing:** not captured. The app is ad-heavy at launch and during onboarding.

**Verdict:** the best Home-tools architecture and a clear list of tools ScanWhirl could expose. The ad model and lack of a clear Pro story prevent it from ranking higher.

## Notebloc — `com.notebloc.app` 6.0.4

**Home:** My Docs, search, Sync to Google Drive, More; empty state; bottom Gallery/Camera; banner ad. `05_notebloc_onboarding2.png`.

**Drawer:** “Free version. Supported by advertisers.” Remove ads card, Settings, Sync to Cloud, Backup/Restore, Trash, Shop, Tutorial, Pro Version, Contact. `05_notebloc_menu.png`.

**Pro:** **₹100/month, ₹750/year (37% discount), ₹1,350 lifetime**; removes ads only; “Continue with ads”/“Restore Purchase.” `05_notebloc_pro.png`.

**Verdict:** clean, honest ad-removal model and best lifetime price. Feature depth is low; no visible OCR/signature. Folder/backup language is relevant to ScanWhirl’s organisation gap.

## Lufick Document Scanner — `com.cv.docscanner` 6.9.9

**Home:** Home / PDF Tools / Import ID Photo tabs; All docs / Recent / Favourite / Business Card filters; folders New Folder, ID Cards, Bills; bottom Ad Space; camera/gallery FAB. `08_lufick_home.png`.

**Pro:** Remove Ads, Premium Themes, VIP Support; **₹399/3 months**, **₹799/year** (50% off). The page is loud orange and uses “Premium Features” without specifics. `08_lufick_pro.png`.

**Verdict:** better library structure than ScanWhirl (tabs, folders, ID), but a weak premium offer and an ad-shaped UI.

## Tiny Scanner — `com.appxy.tinyscanner` 8.16.7

**First run:** full-bleed stock photo, 4.6 stars/480k reviews, Editor’s Choice, testimonial carousel, Continue, terms. Then an empty Recents screen with Tap to Scan, AI button and Recents/Library/Actions/Me nav. `06_tinyscanner_home.png`.

**Pricing:** not captured. The subscription page was not readable in this walk.

**Verdict:** recognisable brand, generic 2019-era execution, large download and marketing-heavy onboarding. Not a design leader.

## iScanner — `com.bpmobile.iscanner.free` 6.11.3

**First run:** a WebView onboarding with a large stock photo, “Loved by 120M users,” rotating testimonials, Continue, and terms. The WebView loads a deep legal/privacy stack and subscription plans. It advertises cloud, OpenAI, Mathpix OCR, Facebook, Firebase and other services. A 3-day trial/weekly plan is present, but the exact readable price was unstable. `07_iscanner_onboarding.png`, `07_iscanner_onboarding3.png`.

**Verdict:** the paywall-first, server-dependent, testimonial-heavy model to avoid. The scanner is not reached quickly.

## Excluded

- **Microsoft Lens:** retired; new scans ended March 2026. Current Microsoft recommendation is OneDrive.
- **Microsoft OneDrive:** requires a Microsoft account; skipped.
- **Google Drive scanner:** installed package had no launchable activity on this test device; not walked live.
- **CamScanner official:** not found on Play Store in this region; the sponsored “OKEN — CamScanner” listing is a clone and was not installed.
- **Apple Notes/Files:** iOS only, cannot install on the Infinix; use only as a documented UX benchmark.
