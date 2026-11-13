# M08 handoff — stopped mid fix-pass on 2026-11-13 (owner request)

## State of the repo (`/home/cybium/ScanWhirl`, branch `test26-redesign`)
- **Committed:** `d69a304` M08 edit safety (non-destructive Rotate/Crop/Filter). PageEditSafetyTest was red on f196cf4 and is green now; full suite 797/0 at that commit.
- **Parked, never merge:** local branch `m08-whirl-capture-parked` @ `750b2eb` holds the dropped Whirl Capture (CameraX) experiment. Owner decision: Google ML Kit scanner only, no custom camera.
- **Uncommitted working tree:** the UX fix pass below. It is **not compiled or tested yet**.
- Nothing pushed. Phone (Infinix X6711) has candidate D installed (`ScanWhirl-M08-candidate-D.apk`, sha256 `ed3055d1…`, 6,953,348 B). It contains the direct save but not the UX fix pass.

## Done in the working tree (uncommitted)
1. Google scanner: JPEG-only results (no unused Google PDF); FULL mode is the default (`Prefs.autoEnhanceScan` default true).
2. Direct save after Google's Next: `ScanLauncher.saveScanned` does a byte copy into durable staging, then the same verified `saveScan`. On failure the pages are kept and Review opens. Measured on the phone: 234 ms total. **Changed last:** it now opens the document screen (`MainActivity.scanSaved`) instead of staying on Home, because the owner wants the page overview and reordering.
3. `store/AutoExport.kt`: the opt-in Documents copy, shared by Review and the direct save.
4. `PendingScans.lastPageNumber`: an appended page can no longer collide with a page waiting on Undo (`.del`).
5. **Search fix (owner's bug):**
   - `SearchMatcher.findWord`/`containsWord` match at word starts, and numbers match whole numbers.
   - All terms must be in the name, or all in the text; no mixing.
   - `PageTextMatches` uses the same rule.
   - New `SearchWordMatchTest`; 3 old tests in SearchMatcherTest and LockedDocumentSearchPrivacyTest were rewritten to the new rule.
6. Vault dead ends removed:
   - The More-sheet "Hide in Vault" row shows only for docs already in the Vault.
   - The Tools Vault tile shows only if a legacy PIN exists.
7. Tools: one even grid. Empty slots pad a short row, so Quick sort is no longer full width. No PRO band (`proDivider` gone, sheetPro hidden). Lock keeps its gate.
8. One palette: navy/glass/hologram colours re-pointed to ink in `values/colors.xml`; `BaseActivity` background is plain `rv_bg` (no star-field).
9. Home cards: the "Unsorted" chip is hidden until a category is set; "Search ready" is spoken only (waiting/partial stay visible); titles are sans. The "N to organize · Quick sort" row is retired (`updateNudge`).
10. Import sheet: one short line per option; subtitle and footnote hidden.
11. Trash copy shortened (en + hi).
12. Settings: Status line and Wallpaper rows hidden. **Not done:** making `Prefs.showGreeting`/`Wallpaper` inert for people who had them on. The edit was declined at the stop.

## Next steps
1. Compile, then fix the tests that encode the old UI:
   - `DirectScanSaveTest`: it expects no started activity; it should now expect DocumentViewerActivity.
   - `ImportSheetTest`: importOrderNote is gone.
   - `HomeScreenTest`: Tools PRO section.
   - `FreeLegacyVaultEntryPointTest`: Vault tile without a PIN.
   - Any nudge or QuickSort tests.
2. Decide whether retired Status line and Wallpaper should be force-off (ask the owner).
3. Still open from `UX_AUDIT.md`:
   - C8, document screen: show "Select" only with 2+ pages, a smaller drag handle, and the category pill in sentence case.
   - C9: shorter search result cards.
4. Full gates, run one at a time: `testDebugUnitTest`, `lintDebug`, `assembleRelease -PtestBuild`. Low RAM: the background run was killed once by memory pressure, so run in the foreground.
5. Re-walk on the phone over ADB, guarded by `scratchpad tap.ps1`, which refuses to tap unless ScanWhirl is in the foreground. Then commit and write `RESULT.md`.

## Known limits recorded
- A book spread scanned by Google is one image, so its OCR has interleaved columns. Splitting the spread is not available in the Google scanner.
- The Infinix preview shows vertical flicker lines in Google's scanner too. This is hardware; saved JPEGs are clean at 100%.

## Incident to report
During the audit a mis-sequenced "back" left the app, and two taps opened the Phone dialer and typed a digit. **No call was placed:** call state idle, call log empty. Every tap now goes through the foreground guard.

Evidence and screens: `CAPTURE_EXCELLENCE/evidence/` and `UX_AUDIT.md`.
