# 30 — Free and Power Pack

**Owner rule (2026-11-13): ScanWhirl never adds a watermark to anything a user creates, in Free or Power Pack.** People buy the Power Pack for features and loyalty, not because Free output is defaced.

"Purpose watermark" is a Power Pack **tool**: the user chooses to type their own text, for example "For HDFC KYC only", onto their own copy. It is never added automatically and never appears on Free output.

The product name is **ScanWhirl Power Pack**. The UI never shows "PRO", "VIP", a crown or gold. Free contains no ads, no watermark, no limits on documents or pages beyond the technical caps (500 pages, 256 MB), no time limits, and no account.

---

## 1. The boundary rule

> **Free:** everything you need to manage, organize, shrink, protect, read and share **your own** documents.
> **Power Pack:** finishing and privacy outputs you prepare **for someone else**: signed, stamped, redacted, split, or converted to Word.

**Test for any future feature:** if a Free user would lose access to, or control over, their own data without it, it is Free.

---

## 2. Exact matrix

| Capability | Free | Power Pack | Kind | Slice |
|---|:-:|:-:|---|---|
| Scan (ML Kit), import photos, import PDF (original kept) | ✓ | | — | existing / 2 |
| Library, search names and text, categories, Trash, Quick sort | ✓ | | — | existing |
| PDF Tools screen, Document picker, Reader dock, Recent | ✓ | | — | 1 |
| Share, Save to Documents (with Undo) | ✓ | | — | existing |
| Merge (ordered, up to 500 pages) | ✓ | | page-image | 2 |
| Organize pages: reorder, rotate, delete, insert, Discard, Save as new, Replace | ✓ | | page-image | 2 |
| Page editor: crop, filters | ✓ | | page-image | existing → 2 |
| Extract pages (one new document from a selection or range) | ✓ | | page-image | 2 |
| **Split into files** (every N pages / several ranges → several documents) | | ✓ | page-image | 4 |
| Compress (presets + exact size) | ✓ | | page-image | 2 |
| Resize photo (exact KB / pixels) | ✓ | | image | 2 |
| Export as images (JPEG, share / Pictures / ZIP) | ✓ | | page-image | 4 |
| Long image (one tall JPEG, or parts, for chats) | ✓ | | page-image | 4 (PM-4.4) |
| Extract text: OCR, correct, copy, save `.txt` | ✓ | | OCR | existing |
| Also read Hindi text (opt-in; in-app search and copy) | ✓ | | OCR | 6 (PM-6.3, only after spike) |
| Searchable PDF text layer (global setting) | ✓ | | PDF wrapper | existing |
| **To Word (text only, .docx)** | | ✓ | OCR + local writer | 6 (only after spike) |
| Password: protect, unlock, change; lock badges | ✓ | | PDF wrapper | 1 (ungate) / 3 |
| AES-256 (if spike PM-3.4 passes) | ✓ | | PDF wrapper | 3 |
| **Redact (verified) + number suggestions** | | ✓ | page-image | 5 |
| **Sign & self-attest** (saved signatures, text stamps, date) | | ✓ | page-image | 5 |
| **Purpose watermark** | | ✓ | page-image | 5 |
| Legacy Hidden Vault (existing PIN users only) | ✓ (open/remove only) | | — | unchanged |

**Never gated, in any future version:**
- opening, reading, searching or exporting your own documents
- Unlock with your password
- Trash restore
- removing a legacy Vault item
- Save to Documents
- Share
- removing anything a Power Pack tool made

---

## 3. Price and policy

- **₹100, one-time** (owner decision, 2026-11-13). The aim is brand growth over revenue, and a price this low makes modded APKs pointless.
  - A Google Play in-app product (non-consumable), product id `power_pack`. One tier. No subscription, trial timer, countdown or "founder" anchor price.
  - The price shown in the UI is always the localized string returned by Play, never hard-coded.
  - No anti-tamper, Play Integrity or license server.
- **Lifetime means:**
  - all Power Pack tools and every update to them, for as long as ScanWhirl is sold on Play;
  - restore on any device signed into the same Google account;
  - (No Family Library: Play does not share in-app purchases with family members; OQ-8 closed.)
- **Excluded from lifetime (stated at purchase):**
  1. An optional future add-on that requires a separately licensed third-party engine (for example, Office-layout conversion or PDF/A). It would be sold separately, and buying it never takes anything away from the Power Pack.
  2. Server features. There are none and none are planned, so the exclusion exists only to be explicit.
- **Refunds** follow Google Play policy.
- **Rejected:** a yearly "language model updates" fee. The models come free from Google Play services.

### What ₹100 buys (the three headline features)

1. **Verified redaction**, with Aadhaar, PAN, account and phone number suggestions.
2. **Sign & self-attest.**
3. **Purpose watermark.**

Supporting value: Split into files, and To Word (text only) once its spike passes.

**Anchor for value, not for the UI:**

| App | Price | Model |
|---|---|---|
| Genius Ultra | ₹4,250 / year | subscription |
| SwiftScan VIP | ₹2,350 / year, with ads in Free | subscription |
| **ScanWhirl Power Pack** | **₹100 once** | one-time, no ads in any tier |

The upgrade sheet makes **no competitor comparison claims**.

---

## 4. Visibility and gating rules (implemented in PM-4.1)

One gate, `PowerPack.state(context)`, has exactly three states. It wraps `ProGate`, whose test-build defaults stay as they are (`util/ProGate.kt:24–34`).

| State | When | Power Pack tiles | Tap behaviour |
|---|---|---|---|
| `OWNED` | Test build (default Pro, or override = Pro); production with a recorded purchase | Shown, no label | Runs the tool |
| `FOR_SALE` | Production, not owned, **and PM-5.5 has shipped** and Play Billing connects. Also a test build with override "Free + store", for review. | Shown with a small "Power Pack" label | Opens the Upgrade sheet (§5) |
| `HIDDEN` | Production, not owned, before PM-5.5, **or** Play Billing is unavailable (no Play Store, or the billing client fails to connect). Also the test-build override = Free. | **Not rendered at all.** No tile, pill, label, badge or panel. Within Extract & split, the Split mode is absent. | — |

**Removing the existing dead ends (PM-1.4):**

| Surface | Location | Change |
|---|---|---|
| Home proBadge | `MainActivity.kt:375` | Hidden in `HIDDEN` |
| More-sheet `pillLock` | `DocumentViewerActivity.kt:590` | Removed, since Protect is Free |
| `showProPanel` | `MainActivity`, `DocumentViewerActivity:550` | Unreachable in `HIDDEN` and `OWNED` |
| Tools Lock tile `pro=true` | `MainActivity.kt:1194` | Gone with the sheet |
| Protect gate | `DocumentViewerActivity.kt:615,700` | Removed |
| `pro_status_unavailable` "Not available yet" | `strings.xml:259` | Deleted |

**Tests (PM-4.1):**
- For each of the three states, render PDF Tools, the Document tools sheet and the Reader More sheet. Assert the exact visible tile set.
- In `HIDDEN`, assert that no string containing "Power Pack", "Pro" or "Not available" is on screen.
- Assert that no Power Pack setup screen can be launched by intent in `HIDDEN`: the activity finishes immediately and nothing is written.

---

## 5. Upgrade UX (card PM-5.5; Play Billing approved by the owner)

This sheet is built in **PM-5.5, after Slice 5**, so the first buyers get the full pack. Until then `FOR_SALE` never occurs.

```
ScanWhirl Power Pack                              [×]
One payment. Yours for good. Works offline.
──────────────────────────────────────────────────────
                         Free        Power Pack
Merge, organize, extract  ✓            ✓
Compress, resize          ✓            ✓
Password protect          ✓            ✓
Extract text (.txt)       ✓            ✓
Redact (verified)                      ✓
Sign & self-attest                     ✓
Purpose watermark                      ✓
Split into files                       ✓
──────────────────────────────────────────────────────
No ads, no watermark, no account in either version.
[ Get Power Pack · ₹100 ]
[ Restore purchase ]
```

**Rules:**
- The sheet is opened only by tapping a Power Pack tile. It never appears at launch, after a scan, or on a timer.
- One price, shown as returned by Play (localized). No strike-through price.
- After purchase, the tool the user tapped opens immediately.
- If Play is unavailable (no Play Store, offline at purchase time), the sheet says "Purchases need Google Play. Your free tools keep working." It offers Close only. This is the one acceptable "can't do it now" state, because it explains why and names the alternative.
- **Restore** queries Play purchases. "Nothing to restore" is shown inline.

---

## 6. What a Free user sees

- **PDF Tools:** 8 tiles in 4 visible groups:
  - Organize: Merge, Organize pages, Extract & split
  - Optimize: Compress, Resize photo
  - Convert: Export as images, Extract text
  - Secure: Password

  The Finish group heading is **hidden** when it has no visible tiles. Any group heading is hidden when its group is empty.
- **Reader dock:** identical for Free and Power Pack users.
- **Copy:** nowhere does Free copy say "upgrade", "unlock" (outside Password), "limited" or "trial".
