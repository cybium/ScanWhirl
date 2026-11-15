# Play Console: store listing + Pro billing (copy-paste)

## 1. Google Play billing — what to set up (Monetize with Play)
Do these in order. The in-app product can only be created after you upload a build that contains Play
billing (the next release AAB does).

1. **Payments profile**: Play Console > Settings > Payments profile — must be set up (merchant account),
   with your bank account and tax details (India: GST if registered). Without it you cannot sell.
2. **Upload the release AAB** to the **Closed testing** track (see section 4) — this unlocks product creation.
3. **Create the product**: Monetize with Play > Products > **One-time products** > Create product
   - Product ID: **`scanwhirl_pro`**   (must be exactly this — the app asks Play for this ID; cannot be changed later)
   - Name: **ScanWhirl Pro**
   - Description: **Unlock every Pro tool forever. One-time purchase, no subscription.**
   - Purchase option: **Buy** (one-time, non-consumable)
   - Price: **₹100** (India). Click "Set prices" > let Play convert for other countries, then round as you like.
   - Tax category: **Digital app / software** (default).
   - **Activate** the product.
4. **Family sharing**: Monetize with Play > Monetization setup > **Google Play Family Library** > opt in
   for in-app products (the Pro screen says "Family sharing included" — keep this ON or change that line).
5. **License testers** (free test purchases, no real charge): Settings > **License testing** > add the Gmail
   accounts of you and your testers; set response to RESPOND_NORMALLY. Testers see "Test card, always approves".
6. Testers must install from the **closed-testing opt-in link** (not a sideloaded APK) for purchases to work.

What the app does: Unlock Pro opens Google's payment sheet; after payment the purchase is acknowledged and
Pro turns on. "Restore purchase" and every app start re-check Play, so reinstalls, a second phone and Family
Library members get Pro automatically. Refunded purchases turn Pro off. No internet permission is needed —
payments go through the Play Store app.

## 2. Store listing text

**App name** (30): `ScanWhirl: PDF Scanner Offline`

**Short description** (80):
`Scan to PDF, edit, sign & protect — fully offline. No ads, no account.`

**Full description** (≤ 4000):

```
ScanWhirl is a document scanner and PDF tool kit that works completely offline. Scan paper to clean PDFs, turn photos into PDFs, read text in English and Hindi, and edit, sign and protect your files — on your phone, with no ads, no account and no internet permission.

SCAN AND CREATE — FREE
• Document scanner with automatic edge detection, crop and enhance
• ID card scan: both sides on one A4 or Letter page
• Passport scan, photos to PDF, import any PDF
• Scan Enhance: Auto, Colour boost, Grey and Black & white that keeps photos readable
• Read text (OCR) in English and Hindi, copy it or export it
• Make PDFs searchable so any PDF app can find words in them

ORGANISE AND OPTIMISE — FREE
• Merge PDFs, extract, reorder, insert and duplicate pages
• Compress PDF to fit email and upload limits, resize pages
• PDF to images, one long image, text export, ZIP export
• Protect PDF with a password (AES-256) and unlock PDFs you own
• Remove hidden metadata before you share

PRO — ONE-TIME PURCHASE, NO SUBSCRIPTION
• Sign PDFs: draw your signature once, place it anywhere
• Add text, draw, highlight, stamps, watermark and page numbers
• Hide private info: find and cover phone numbers, emails, ID numbers and card numbers before you share
• Convert to Word, Excel and PowerPoint
• Split by page range, batch tools and batch rename for many files at once
• Folders and tags, business card scan, QR code scan, archival PDF/A
Pay once, keep it forever. Family sharing included.

PRIVATE BY DESIGN
• No internet permission: your documents never leave your phone
• No ads, no account, no tracking
• Vault: lock sensitive documents behind your fingerprint or PIN
• Encrypted backup: one file with all your scans, saved where you choose

WAYS PEOPLE USE SCANWHIRL
• Students: scan notes and textbooks, read the text, share as one PDF
• Job seekers: merge certificates and ID into one compressed PDF for HR portals
• Shop owners: scan bills and invoices for your accountant
• Everyone: sign and return forms, hide your phone number or ID before sharing a document

QUICK ANSWERS
Is ScanWhirl free? Yes. Scanning, PDF creation, merging, compressing, text reading and password protection are free. Pro tools are a one-time purchase.
Does it need internet? No. Everything runs on your phone.
Where are my files? In ScanWhirl's library on your phone. Save or share any file to your Downloads, Drive or any app.
```

**Category**: Productivity. **Tags**: Document scanner, PDF editor, Productivity.
**Contact email**: your support email. **Privacy policy URL**: host `PRIVACY_POLICY.md` (e.g. a GitHub Pages
page or Google Sites) and paste the link — required.

## 3. Graphics
- App icon 512x512 PNG: `C:\Users\Cybium\Desktop\.sw-qa\store\icon_512.png`
- Feature graphic 1024x500 PNG: `C:\Users\Cybium\Desktop\.sw-qa\store\feature_1024x500.png`
- Phone screenshots (2–8, 1080x1920, 9:16): `C:\Users\Cybium\Desktop\.sw-qa\store\screenshots\`

## 4. Click by click (about 20 minutes)
1. **Upload**: Play Console > ScanWhirl > Test and release > Testing > **Closed testing** > (create a track
   if none, e.g. "Testers") > Create new release > upload `ScanWhirl-1.2.0.aab` > paste the release notes
   from `RELEASE_NOTES_PLAY.txt` (both languages) > Next > Save > **Send for review**.
   Testers: add a Google Group or email list with at least 12 people, share the opt-in link, they must opt in
   and keep the app installed for 14 days.
2. **Store listing**: Grow users > Store presence > Main store listing > paste name, short and full description
   (section 2), upload icon, feature graphic and screenshots (section 3) > Save.
3. **Pro product**: after step 1 is processed, Monetize with Play > Products > One-time products > Create
   (section 1) > Activate.
4. **App content**: Policy > App content — Data safety (from DATA_SAFETY.md: no data collected, no data
   shared, no network access), Privacy policy URL, Ads: "No ads", Target audience: 18+ (or 13+), Content
   rating questionnaire (productivity tool, no user content shared).

## 5. The release
- AAB: `builds\<date>\ScanWhirl-<version>.aab` (signed with your upload key).
- Track: **Closed testing** (personal accounts need 12+ testers opted in for 14 days before production).
- Release notes: see `RELEASE_NOTES_PLAY.txt` next to the AAB.
- Data safety form: answers in `docs/release/DATA_SAFETY.md` (no data collected, no data shared).
