# ScanWhirl UI plan: from toolbox to document app (lead, 2026-11-18)

Input: docs/audit/2026-11-18/UI_CRITIQUE.md (A1-A8, S1, C, D), owner flaw list, owner decisions in 1.2.3_PLAN.md.
Rule: evolve THIS app (same brand, same code base, same users), not a new one. Every phase ships a test APK the owner
tries on the phone before the next phase starts.

## 0. Research rulings (2026-11-18; SUPERSEDE the text below where they conflict)
Source: R2 UX_PRINCIPLES -> CRITIC_R2_UX -> JUDGE_RESEARCH §2/§4 (next/docs/review/). Pro: docs/release/PRO_PLAN.md.
1. **Navigation:** four destinations (Library, Tools, Vault, Me) + a separate centre **Scan** action button (kept in
   the centre where people know it), not a nav-bar item. It opens Create.
2. **Motion:** no 250 ms cap. Short tap feedback, modest non-blocking surface motion, measured on the Infinix; plain
   Views animators, no new motion dependency; everything off under "Remove animations" (API 24 compatible).
3. **Result screen default:** always ask Replace original / Keep both (N3). Preselect **Replace** only for a
   one-in/one-out library operation whose original goes to Trash and can be restored; show that, plus a persistent
   Undo/restore on the result; changing the choice never reruns the tool. **Keep both** is the default for Merge,
   Split, multi-input/output, external sources, or anything without proven rollback. Never trash several inputs
   automatically. Back/Open/Share/process death never bypass rollback.
4. **Phase 3 before Phase 4:** the result screen ships its visual + Replace/Keep work in phase 3 but KEEPS "Save to
   Documents" until phase 4 makes the library real files. No "already saved as a file" wording before that is true.
5. **Numbers** (sizes) appear at once, no counting animation.
6. **Cards:** simple tonal separation, no blur shadows; colour roles defined apart from elevation; profile overdraw.
7. **Language:** one UI language at a time via A/अ ("EN + HI parity" below means this, not bilingual labels).
8. **Type:** Latin meta >= 12 sp; Hindi body/meta start at >= 14 sp with generous line height, no letter-spacing on
   Devanagari; at 200 % font size, unclipped text beats the "5-6 documents visible" goal.
9. **Folders on Home:** a few folder cards + "All folders" + "New folder" (count is a mockup choice, not a limit);
   folder model in phase 4, Home look in phase 6; no new Folders tab.
10. **Search:** the visible field stays (collapse on scroll optional after device test).
11. **Pro marking:** gold always with the word "Pro" or a lock; contrast checked on every surface.
13. **Phase 2 `SwAdjustCorners` must be accessible:** TalkBack can move each corner (accessibility actions /
    virtual nodes), with a spoken hint when no edge was found. Replaces the canvas-only CropView everywhere
    (ID card, Passport, Insert pages). Source: JUDGE_fixidcrop D9, lead ruling.
12. **Phase 4 open question (resolve before storage-a starts):** minSdk 24-28 needs storage permission to write public
    Documents, but the app's rule is "never prompt" for deletes. Design a compatible approach; no new consent flows
    (createDeleteRequest etc.) without a new owner decision; files we cannot touch are reported as kept.

## 1. The idea in one line
**Documents first, one way to start, one way to finish.** A person opens ScanWhirl and sees their documents; one
Scan button starts everything; every tool works the same way (pick, set, done); every result says what happened and
asks Replace or Keep; every file is a real file they can find.

## 2. Design language (what makes it feel new, not "another scanner")
- **Brand kept**: deep ink background with the cyan tint (now even on every screen), Manrope, cyan primary, gold only for Pro.
- **Depth instead of boxes**: documents are large thumbnail cards with a soft shadow; sheets rise from the button
  that opened them (the Scan sheet grows out of the Scan button). Fewer outlined pills, fewer uppercase labels.
- **One pill style, one card style, one button hierarchy**: 1 primary (cyan, filled) per screen, secondary = outline,
  tertiary = text. Sheets have an X only (no extra Cancel), except destructive ones (Cancel + red action).
- **Motion with purpose** (all under 250 ms, off when the phone's "remove animations" is on): document card -> viewer
  page grid (shared element), Scan button -> Create sheet, result counter ("824 KB -> 544 KB" counts down), a soft
  haptic on save. This is where ScanWhirl feels different from flat competitor apps.
- **Words**: say what happened ("Compressed: 824 KB to 544 KB"), never "Done"; plain words in English OR Hindi (one language at a time via A/अ, full parity; ruling 7).
- **Spacing scale** 4/8/12/16/24/32 dp, touch targets >= 48 dp, text >= 12 sp, contrast >= 4.5:1 (fixes D2, D3).

## 3. The future screens (before -> after)

### 3.1 Home (A1, A2, A3, D1)
```
BEFORE                                   AFTER
[logo ScanWhirl]   [Offline·No ads]      [logo ScanWhirl]        [A/अ] [⚙]
[ Search pages                    ]      [ Search documents and pages   ]
[All][+New folder]          [A/अ]        Folders  ─────────────────────────
[ID card][Photos][Import PDF]            [▣ Study 2] [▣ Receipts 3] [+ Folder]
Quick tools            All tools ›       Recent                  Newest ▾
(Merge)(Compress)(Sign)(Protect)...      ┌───────────────────────────────┐
Recent              Newest ▾             │[thumb] Rent receipt - Oct      │
[All 3][Study 2]                         │        1 page · 70 KB · Today ⋮│
3 documents                              ├───────────────────────────────┤
[one document card visible]              │[thumb] Physics notes - Ch 4    │
                                         │        6 pages · 2 MB · Yest. ⋮│
[Library][Tools](SCAN)[Vault][Me]        │ ... 5-6 documents visible ...  │
                                         [Library][Tools](SCAN)[Vault][Me]
```
- Removed from Home: the 3 tiles (into the Scan sheet), quick tools (Tools tab), the "Offline · No ads" pill
  (moves to About and the Pro screen; it still appears on the empty state and the store).
- Folders are real folders (phase 4) shown as a row of folder cards; "+ Folder" ends the row.
- 5-6 documents visible instead of 1.

### 3.2 The Scan button opens "Create" (A3)
```
            ┌──────────────── Create ────────────────┐
            │  [  Scan a document  ]  (big, primary)  │
            │  [ID card] [Passport] [QR code]         │
            │  [Import photos]      [Import PDF]      │
            └────────────────────────────────────────┘
                              (SCAN)
```
- One place to start anything. Tools > Create keeps the same entries for people who look there.
- Every photo path (Import photos, ID card from gallery, Insert pages > Photos) goes through **Adjust corners**
  (the existing Passport crop screen, auto-cropped first) (A8).

### 3.3 Every tool: Pick -> Set -> Result (A6)
```
1 PICK (one sheet, everywhere)        2 SET (one page template)          3 RESULT
┌ Choose a document ──── X ┐          ← Compress                         (see 3.4)
│ [Search                ] │          ┌ Rent receipt - Oct ─────────┐
│ [thumb] Rent receipt  ✓  │          │ 1 page · 824 KB   [Change]  │
│ [thumb] Physics notes    │          └─────────────────────────────┘
│ [thumb] Offer letter     │          Size
│      (multi-select for   │          ( ) Best quality   761 KB
│       Merge/Batch, with  │          (•) Balanced       555 KB
│       order numbers)     │          ( ) Smallest       283 KB
│ From phone storage ›     │          Custom size ›
└──────────────────────────┘          [        Compress        ]  (pinned)
```
- The picker is the thumbnail sheet with search (owner's decision); "From phone storage" opens Android's picker.
- The SET page has the same 3 parts in every tool: what (selected document card) / how (options) / one button.

### 3.4 Result screen (A5, A7, N3)
```
BEFORE  "Done"                         AFTER
[white page on white box]              ← Compressed
Open                                   [page on dark backdrop, edge visible]
Share  Save                            824 KB → 544 KB  (34% smaller)
Show in library  Undo                  ┌ Keep the original? ──────────────┐
                                       │ (•) Replace it (old one to Trash) │
                                       │ ( ) Keep both                    │
                                       └──────────────────────────────────┘
                                       [        Open        ]  [ Share ]
```
- No "Save" (it is already a real file), no "Show in library" (Open goes there), Undo = the Replace/Keep choice
  can be changed until you leave; afterwards Trash restores.

### 3.5 Document view
- Title wraps to 2 lines (no "SW_TEST_3pages - Compre..."); meta line: 3 pages · 544 KB · Study.
- Dock stays (Share / Edit / Text / More); Edit gains "Add pages" (F21); Share gains "Remove hidden info" (F10).

### 3.6 Smaller screens
- Settings: remove "Text language" (F18), "Also save a copy to Documents" (A4); clearer summaries (C).
- Vault recovery: "Save as file" instead of Copy (S1).
- ID card: templates (F2), Keep/Discard (done), crop (A8), page on dark backdrop.
- Edit & sign: colour palette (F12), signature photo background removed (F13), sheet locked while drawing (done).

## 4. Seamless integration: phases
Each phase = lanes (DeepSeek, max 2 at once, files never shared between running lanes) -> lead review -> cloud
gate -> one test APK -> owner tries it. Phases that change what people see first get a rendered mockup approved by
the owner BEFORE coding (marked ✎).

| Phase | Ships | Lanes | Why this order |
|---|---|---|---|
| 1 Safe fixes (running) | S1 recovery code file; wording (C); crop on every photo path (A8); Home background (D1, merged) | fixcopy, fixidcrop, then fixinsertcrop | no layout change; removes trust and security problems first |
| 2 Kit components | `SwPickerSheet`, `SwToolPage` (what/how/button), `SwResultHeader`, `SwAdjustCorners`, pill/button/sheet tokens; no screen changes yet | kit-a, kit-b | later phases reuse them: one change, every screen |
| 3 ✎ Result screen + Replace/Keep | 3.4 on every tool result | result-ui | biggest daily annoyance (copies everywhere) |
| 4 Real files | library = PDFs in Documents/ScanWhirl/<folder>; migration of existing documents with a progress screen and a full backup first; folders = directories; Folders & tags works; "Save to Documents" removed | storage-a (model + migration), storage-b (UI) | the ground everything else stands on; done before Home shows folders |
| 5 One picker + tool template | 3.3 rolled out tool group by group (Organize, Convert, Edit & sign, Secure) | tools-1..4 | consistency; built on phase 2 kit |
| 6 ✎ Home + Create sheet | 3.1 + 3.2, motion | home-ui, create-ui | the first screen changes last, when all it links to is already clean |
| 7 Owner's feature decisions | ID templates (F2), Share + Remove hidden info (F10), palette + signature (F12/F13), auto OCR language (F18) | run in parallel with phases 3-5 where files don't clash (stamp editor, OCR editor, ID card are separate) | independent features |

Parallel map (no two running lanes share a file): ID card (IdCardActivity) / stamp editor (StampEditorActivity,
StampPrompts, InkExtract) / OCR (OcrEditorActivity, OcrLanguage, Settings) / result (ToolResultActivity) /
storage (ScanRepository, AutoExport, LibraryScreen) / tools (ToolSetupActivity + tool screens) / home (LibraryScreen,
ShellActivity). Storage and Home both touch LibraryScreen: never at the same time.

## 5. How we know each phase is good (acceptance)
- Unit/Robolectric tests for every behaviour; cloud gate green; no data-safety test weakened.
- Device pass on the Infinix by the lead: every changed screen captured and looked at, EN + HI, before the owner gets it.
- Phase 4 extra: migration tested on a copy of real data (owner's test copy), with a backup restore drill.
- Owner test on the phone; their notes become the next lanes.
- Release candidate (1.3.0) only after phases 1-6: store screenshots redone from the new screens.

## 6. Risks
- Real-files migration can lose data if wrong: backup first, migrate by copy-then-verify, keep the old folder until
  the owner confirms, a test for every step.
- Big UI changes confuse existing testers: release notes with 3 before/after pictures; the Scan button and the
  document dock stay where they are.
- Hindi text length: every new screen checked in Hindi at the largest font size.
