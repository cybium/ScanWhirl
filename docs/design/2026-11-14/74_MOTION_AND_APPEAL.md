# 74 — Motion and appeal (lead critique + strategy, 2026-11-15)

## Critique (from code + every screenshot seen in runs 1-2)

- **Everything snaps.** No tab transition, no screen transition in the v2 shell (`overridePendingTransition`, `ActivityOptions`, `TransitionManager`: 0 uses). Old `res/anim/screen_rise_*`, `sheet_*`, `item_fade_up` exist but the v2 screens do not use them.
- **No success moments.** A finished export, merge or protect ends in a plain snackbar. The user never *feels* the job completed.
- **Loading looks old.** `SwDialog.progress` is an AlertDialog spinner, not the kit, and shows no page progress.
- **Flat depth.** Every screen is the same dark slab with hairline cards; no hero moment on Home; empty states are plain text (Book pages empty screen is a void).
- **Feedback gaps.** Few haptics (4 calls), press states uneven (tiles yes, cards and chips mostly no), no feedback on long-press/selection/drag drop.
- **The brand is static.** The rings + page mark and the scan disc never move, though the name is literally "Whirl".

## Strategy (kit-wide, built once in `ui/kit/SwMotion.kt`, used everywhere)

1. **Motion tokens:** short 120 ms (press, chips), medium 220 ms (tabs, sheets, list items), long 320 ms (screen drill-in, success). Easing: emphasized-decelerate for entries, accelerate for exits. All durations multiplied by the system animator scale; **0 when the user turned animations off** (Settings > Accessibility > Remove animations).
2. **Tabs:** fade-through (old out 90 ms, new in 210 ms with 0.92→1 scale).
3. **Screens:** drill-in = shared-axis Z (tool tile → setup → result); back reverses. Sheets: slide up 220 ms with scrim fade; content items stagger 20 ms.
4. **Press:** every tappable card, tile, chip and row gets the same press: scale 0.97 + ripple (`animator/sw_press.xml` exists, apply it everywhere via the kit).
5. **Haptics:** tick on long-press entering selection, on drag pickup and drop, on toggles; confirm pattern on success; reject pattern on wrong password. One helper `SwHaptics`.
6. **Lists:** first load only, staggered fade-up (30 ms per item, max 8 items); add/remove/move use a tuned ItemAnimator (grid drag drops with a small settle).
7. **Brand motion:** the scan disc "breathes" (soft glow pulse, 3 s loop, very subtle; off when animations are off); on press, rings expand out from the disc (the whirl) before the scanner opens. Header mark rotates its rings once on cold start (600 ms).
8. **Success moment:** result screen draws a check mark (AnimatedVectorDrawable) inside expanding rings, 600 ms, plus confirm haptic. "Copied" chips morph to a check for 1.2 s.
9. **Progress:** kit progress sheet: whirl spinner (`animator/sw_whirl_turn.xml`) + determinate bar + "Page k of N" + Cancel. Replaces every `SwDialog.progress`.
10. **Loading placeholders:** thumbnails show a soft pulsing placeholder (alpha 0.35↔0.6), never a blank box.
11. **Empty states:** brand line-art (rings + page) at 30% opacity, one short line, one primary action. Applies to Library, Vault, Trash, Book pages, QR history, Folders.
12. **Vault:** shield closes on lock, opens on unlock (200 ms); relock countdown shown as a thin ring around the lock icon.

## Acceptance

- Robolectric: every kit entry point applies `SwMotion` (durations 0 when animator scale is 0); no `SwDialog.progress` left.
- Phone: the lead records the screen (adb screenrecord) and reviews frames for tab switch, drill-in, sheet, press, success, progress, empty states. 60 fps target on the Infinix (no dropped frames visible in `dumpsys gfxinfo` jank % < 5 on the Home scroll and tab switch).
