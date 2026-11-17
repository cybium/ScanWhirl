# CamScanner deep dive — sideloaded Pro build

**Package:** `com.intsig.camscanner`  
**Version:** 7.28.5.2611020000 (versionCode 72852)  
**Installed:** 2026-11-13 18:39  
**Status:** Pro unlocked by the owner. Pricing is deliberately ignored.  
**APK:** 194.47 MB, SHA-256 captured in `C:\Users\Cybium\Desktop\ScanWhirl-HQ\research\Research_2026-11-13\apk-analysis\camscanner\`  
**Method:** ADB screenshots, UIAutomator, actual gallery import, APK manifest/dex/resource inspection. No camera due the phone facing down.

## The core answer: why it feels so feature-packed

CamScanner is not a single scanner screen. It is a document suite with a custom native imaging stack and a remote-configurable tool catalogue.

- **194.47 MB APK**, 12,695 files, 330.8 MB uncompressed.
- **14 dex files**: 84,681 classes, 522,684 methods, 4,921 packages.
- **340 activities, 41 services, 24 providers, 38 receivers, 46 permissions.**
- Native libraries for OpenCV, MNN, ncnn, a custom OCR engine, page scanner, magic enhancer, octopus classifier, PDFium, PDF engine, digital ink, encryption, JBIG2, barcode/QR, ads and crash reporting.
- Remote-config tool catalogue: the APK contains a `tool_page_v2.json` with **41 tool entries**, more than the 19 visible in this build. Home navigation itself is remote-configured (`main_page_scan_func_distribute_data.json`).
- Ad/cloud/account SDKs: ByteDance, Facebook, PubMatic, Vungle, AppsFlyer, Sentry, Tencent, Netty, Flutter, BouncyCastle and others are visible in package names.

That combination is why it can offer modes, conversion, annotation, ID layouts, cloud, widgets and account features at once. It also explains the cost: a 194 MB download, 46 permissions, ads, account prompts and a large native/third-party surface.

## UI map

```
Home   Smart Scan | PDF Tools | Import Images | Import Files
       ID Cards   | ID Photo Maker | To Word | All
       Recents
Files  Import Files | Import Images | Create Folder | All/sort/grid/multi-select
Tools  Convert | Import | Edit | AI Tools
Me     Sign-in | Premium | Cloud/Tasks/C-Points | Account | Sync | Scan
       Manage Documents | Add Widgets | More Settings
```

### Home

- Search, cloud reward icon, gold VIP icon.
- Eight shortcuts: **Smart Scan, PDF Tools, Import Images, Import Files, ID Cards, ID Photo Maker, To Word, All**.
- “Import Files” has a red **HOT** badge.
- Recents with View All, empty state, “Scan new docs”.
- Bottom nav: Home, Files, Tools, Me; a floating camera FAB.
- The “EDU” red dot appears on Me.
- The home is less cluttered than ScanWhirl’s current Home in one sense (tools are the focus), but it is still a marketing/product surface (cloud, VIP, HOT, EDU).

`cam_home.png`

### All Tools / Tools tab

Visible groups and 19 tiles:

**Convert**
- To Word
- To Excel
- To PPT
- CountCam
- PDF to Images
- PDF to Long Image
- CamScanner AI

**Import**
- Import Images
- Import Files

**Edit**
- Sign
- Add Watermark
- Merge Files
- Extract PDF Pages
- Reorder Pages
- Lock

**AI Tools**
- Erase Marks
- Smart Erase
- Solver AI
- Enhance Documents

`cam_tools_all.png`

The asset catalogue contains **41 entries**, including hidden/remote candidates: ID cards, OCR, ID photo, formula recognition, scan book, scan PPT, scan whiteboard, scan handwriting tablet, scan table, scan translate, document backup, to Word/Excel/PPT, PDF to images/long image, signature, watermark, merge, extract, reorder, encrypt, resume templates, student ID, question book, topic paper, QR code, print, buy printer, innovation lab. Some are regional/remote-gated.

### Import Images flow

Import Images asks for full photo access, then opens a custom gallery. Selecting an image goes to **MultiImageEditPreviewActivity**:

- Title auto-generated: **CamScanner 13-11-2026 18.46**
- 1/1 pager, Compare.
- Horizontal filter strip: **Magic Color, Magic Pro (star), No Watermark, No Shadow, No Handwriting, B&W**. The strip is clipped at the right.
- Bottom: **Import, Left (rotate), Crop, Extract Text, Sign, Finish**.
- The page preview has a green processing overlay in the captured state.
- Filter names are task-based, not generic. “No Watermark” removes watermark artifacts, “No Shadow” removes shadows, “No Handwriting” removes handwriting. This is a much richer filter vocabulary than ScanWhirl’s Original/Enhance/Clean/B&W.

`cam_gallery.png`, `cam_import_review2.png`

### Document view

- Title truncated in the toolbar; Tags +; grid/mode icon; overflow.
- Full page; Add card; floating **Ask AI** card.
- Bottom dock: **Add, Edit, Share, To Word (red dot), Sign**.
- This is the document action model ScanWhirl should have, though the title truncation and the floating AI card add noise.

`cam_document2.png`

### Page editor

Three tabs:

**Images**
- CS PDF (convert current document to PDF)
- Crop
- Filter
- Edit Text
- Smart Erase
- Retake

**Markup**
- Sign
- Add Text
- Brush
- Add Watermark
- Page Title

**Page**
- Page Title (observed as the only visible page-tab entry in this state)

Top: close, Share, Done. The page has a delete icon and a draggable page. This is a three-toolbar editor: image correction, markup, page management. The tabs make it understandable despite the density.

`cam_editor.png`, `cam_editor_images.png`, `cam_editor_markup.png`, `cam_editor_page.png`

### Smart Scan camera

Black preview because the phone is face-down. Top: close, flash, HD/pixel, more. Quick strip: **Extract Text, To Word, Sign, Single, Batch, Smart Erase**. Bottom: shutter, Import Images, Import Files, All Features. This is a custom camera, not ML Kit, and the actions sit directly around the shutter. It is the feature-packed camera model.

`cam_smartscan.png`

### ID Cards mode

A dedicated camera mode with a full A4 example preview, an Indian ID card sample, and templates **General / ID card / Passport**. CTA **Make it now**. It explains the use case (“banking, administration, and more”). Bottom mode strip: Batch, Smart Erase, ID Cards, Question Set, Translate.

`cam_idcards.png`

This is the true “ID mode” ScanWhirl lacks. A crop preset is not this.

### ID Photo Maker

A separate passport-photo studio: 25 × 35 mm sample, a 2 × 4 photo sheet, Face Camera, Remove Obstructions, Optimize Lighting, Use Solid Background, and Capture. This is a different product, not document scanning.

`cam_idphoto.png`

### Capture to Word

A camera mode with the explanation “Convert files to Word format. For best results, avoid image-only files or complex shading.” Single/Batch, Timestamp, Extract Text, To Word, Sign. This is an output-first capture mode.

`cam_toword.png`

### PDF Tools

11 tools:

**Convert:** To Word, To Excel, To PPT, PDF to Images, PDF to Long Image
**Edit:** Sign, Add Watermark, PDF Compress, Merge Files, PDF Extract, Reorder Pages, Protect PDF

`cam_pdf_tools.png`

This is the full PDF utility suite ScanWhirl lacks. It also has a banner about managing documents from other apps.

### Files

- Cloud “Get Benefits,” search.
- Large Import Files / Import Images / Create Folder buttons.
- All (1) with sort, grid/list, multi-select.
- Document row with name, timestamp, page count, checkbox.
- Tag FAB.
- Empty area after one row.
- Bottom Home/Files/Tools/Me.

`cam_files3.png`

### Me

- Sign in with Google/mobile/email.
- “CamScanner Premium — 20+ Premium privileges unlocked” (Pro sideloaded).
- Cloud, Tasks, C-points.
- Account, Sync, Scan, Manage Documents, Add Widgets, More Settings.
- EDU badge on the tab.
- Warm yellow gradient separate from the document UI.

`cam_me2.png`

### More Settings

Share & Export: Export PDFs to Download/CamScanner, Email to Myself, Upload/Fax History, Email Signature.
Security: Lock Folder, Folder Password, Two-Factor Authentication.
Free Up Space, Extract Text, Notification Settings, Permission Manager.

`cam_settings.png`, `cam_settings_export.png`, `cam_settings_security.png`

## What this proves about feature breadth

The breadth is architectural, not a single clever screen:

1. **Custom native capture and enhancement** (page scanner, magic enhancer, OpenCV, MNN/ncnn).
2. **A remote tool catalogue** so the Home/Tools set can change without an APK release.
3. **A custom camera with modes and quick actions**, unlike ML Kit.
4. **A first-class document suite**: PDF tools, conversion, signature, watermark, lock, tags, folders, widgets.
5. **An account/cloud/ads ecosystem** that funds and expands the surface.

The trade-off is severe: 194 MB, 46 permissions, 340 activities, ads, cloud prompts, account prompts, a crowded Home, and remote configuration. It is not a model for ScanWhirl’s offline, no-account, no-ads promise.

## What ScanWhirl should borrow

- **A dedicated Home tool shelf** with user-selected tools. Not all 19; 4–8.
- **Three editor tabs** (Images / Markup / Page). This is cleaner than a single overflowing dock.
- **Task-named filters**: No Shadow, No Handwriting, Magic Color. These describe outcomes.
- **An ID Card mode** with General / ID card / Passport and a composite A4 preview.
- **PDF Tools as a group**: merge, compress, extract, reorder, protect, convert.
- **Document action dock** (Add / Edit / Share / To Word / Sign).
- **Date/time auto-naming** and tags.
- **Files/Folders and multi-select.**
- **Widgets and import files/images.**

## What to avoid

- 194 MB and 46 permissions.
- Ads/cloud/premium/account prompts before the document.
- Remote-config-driven Home bloat (41 tool entries, region/HOT/VIP/EDU badges).
- Adjacent products: ID Photo Maker, Calorie Counter, Plant Scan, Solver AI, C-points, resume templates, print/buy printer.
- Full-screen upsell and account-first surfaces.
- Reusing or copying any proprietary code. APK inspection is for feature/architecture understanding only, not code reuse.

## The specific ScanWhirl gap

CamScanner shows that the offline competitor opportunity is **not** to match the 41-tool list. It is to build the **10 tools that matter** with better clarity and no ads/account/cloud:

1. Scan / Import images / Import PDF.
2. PDF Tools (merge/compress/extract/reorder/protect).
3. OCR / text extraction with language selection.
4. To Word / Excel / TXT (local, with fidelity labels).
5. Sign / annotate.
6. ID card (front/back A4).
7. Book/spread capture.
8. Smart cleanup (shadow, finger, handwriting removal).
9. Naming/folders/tags.
10. Searchable PDF/PDF-A.

That is enough to feel “feature packed” without becoming a 194 MB ad/cloud suite.
