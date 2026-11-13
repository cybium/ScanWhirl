# ScanWhirl test24 — phone checklist (Orbital Archive & Neural Vault Visual Design System)

APK: `ScanWhirl-1.1.1-test24.apk` (package `com.scanwhirl.app.test`, versionCode 24)  
Install over test23. All your scans, PINs, categories, and settings carry over untouched.

---

## A. Globe Home Screen (Refinement & Polish)
1. **Top Chrome:**
   - App title `"ScanWhirl"` rendered in crisp `#EDF9FF` with `+0.06` tracking.
   - Search button in the top right is now a rounded glass panel (12dp) with a thin cyan border (`#3800F0FF`) and `#00F0FF` magnifying glass glyph (the previous gold/brown border is removed).
2. **Bottom Camera FAB:**
   - Bottom scan button features a double cyan concentric ring (72dp outer ring in `#00F0FF`, transparent gap, centered glass FAB).
   - Subtitle below FAB reads `"TAP TO SCAN"` in 10sp electric cyan with tracking.
3. **Orbiting Document Cards:**
   - Document cards orbiting Earth feature semi-translucent glass bodies, `#00F0FF` rim, and HUD telemetry corner reticle ticks on the right edge.
   - Earth rotation, inertia drag, category rings, and far-side padlock privacy behave identically to test23.

---

## B. Shelf / List View (Major Visual Overhaul)
4. **Atmosphere & Header:**
   - Base window is deep cosmic void (`#050A14` to `#0B1426` subtle gradient).
   - App bar title `"ScanWhirl"` in `#EDF9FF`; greeting metadata in muted `#8EA8C4`.
   - Search bar sits inside a 14dp rounded glass well with 1dp cyan border that illuminates on focus.
5. **Tool Cards Grid (Resize, Compress, Edit, Merge):**
   - Flat dark Material cards replaced by translucent frosted glass panels (`#C20A162B`) with 1dp cyan stroke.
   - Vector icons sit inside glowing circular badges (32dp) with soft cyan backlight (`#2200F0FF`).
   - Tool titles in 13sp bold `#EDF9FF`; subtitles ("Scale PDF & canvas", "Lossless neural packing", "Filter, rotate & crop", "Combine orbital records") in 10sp `#8EA8C4`.
6. **Pro Suite Banner:**
   - Restyled with dark amber glass background (`#CC1A1505`) and warm gold stroke (`#80FFB800`).
   - Expanded Pro buttons (Vault, OCR, Lock, Long Image) feature gold icons and cyan-white labels.
7. **Document List Rows:**
   - Rounded 14dp glass cards with 1dp cyan border (`#3800F0FF`).
   - First-page thumbnail wrapped in an 8dp container with a 15% cyan HUD tint overlay.
   - Document title in `#EDF9FF`; metadata chip (`"X pages · Y KB · Date"`) in `#8EA8C4`.
   - Action icons (Eye, Edit) in electric cyan `#00F0FF`.
8. **Floating Action Button & Empty State:**
   - Bottom FAB in electric cyan `#00F0FF` with dark cosmic icon.
   - Empty state illustration tinted cyan with `#EDF9FF` typography.

---

## C. Dialogs (Resize, Compress, Options)
9. **Glass Dialog Scrim & Cards:**
   - Window dims with 85% cosmic scrim.
   - Dialog body has 20dp rounded corners with glass surface and cyan highlight.
   - Input wells (target size, custom dimensions) feature dark translucent backgrounds with cyan focus border.
   - Positive/Apply actions in bold `#00F0FF`; dismiss/cancel actions in `#8EA8C4`.

---

## D. Scan Review & Post-Scan Chrome
10. **Review Screen:**
    - Dark space backdrop with dark appbar and `#EDF9FF` title.
    - Title edit box uses dark glass input well with cyan focus stroke.
    - "SAVE PDF" button is a solid electric cyan pill (`#00F0FF` background with `#050A14` bold text).

---

## E. Core Functional Verification (Zero Regressions)
11. **Scan Flow:** Scan a new document → auto-classifies into category ring on globe and shows on Shelf.
12. **Navigation:** Pinch-in on Globe cross-fades into Shelf; back button returns to Globe; Settings toggle turns Globe on/off.
13. **Tools:** Resize, Compress, Edit, Merge, OCR, and Vault PIN lock all execute successfully.
