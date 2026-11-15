# Scan Enhance 2.0 — evaluation (L4)

Session 2026-11-15. Filters: `util/ScanEnhance.kt`; list/labels: `util/ScanFilter.kt`,
`PageViewerActivity`/`ScanReviewActivity` (list only), `strings.xml`.

## What the filters do

- **Auto** (default filter, `enhance` id): per-channel paper illumination estimate on a 320 px
  backdrop (grayscale closing + wide blur), divided out with a saturation guard so coloured ink,
  stamps and photos keep their colour; gentle luma stretch; 3×3 unsharp mask. Removes shadows,
  uneven light and pink/yellow paper tint toward white.
- **Colour boost**: Auto plus a vibrance ramp (paper stays neutral; colours get noticeably
  stronger).
- **Grey**: Auto rendered as neutral luminance.
- **Black & white**: Auto, thin ruling lines lightened/removed (horizontal and vertical, including
  slanted lines under perspective), tiled Sauvola threshold (32 px tiles, bilinear threshold),
  large border-connected black blobs removed, and continuous-tone **picture regions rendered as
  grey** instead of black blotches.
- **Original**: unchanged.
- Every filter is a pure function of the source bitmap and always runs from the original page
  (no stacking; I9 groundwork). Long side is capped at 3000 px; one page at a time.

## Evaluation (owner photos only, page-cropped)

`ScanEnhancePreviewEvalTest` reads the page/card boxes in `owner/enh_crops.txt` (whole spread for
rotated book pages, card only for ID cards) and writes one labelled side-by-side plus a 100% zoom
strip per photo to `…\.sw-qa\owner\out\L4enh\`:

`original-crop | Auto | Colour boost | B&W`, each panel 900 px wide.

Preview timestamps (regenerated after the final code change; **L4_EVAL_ON** deleted after the run):

| file | written (local) |
|---|---|
| IMG_20261115_005907_312_compare.jpg / _zoom.jpg | 2026-11-15 15:17:49 |
| IMG_20261115_005913_843_compare.jpg / _zoom.jpg | 2026-11-15 15:17:56 |
| IMG_20261115_005924_273_compare.jpg / _zoom.jpg | 2026-11-15 15:18:05 |
| IMG_20261115_010022_505_compare.jpg / _zoom.jpg | 2026-11-15 15:18:17 |
| IMG_20261115_010034_806_compare.jpg / _zoom.jpg | 2026-11-15 15:18:22 |
| IMG_20261115_010054_411_compare.jpg / _zoom.jpg | 2026-11-15 15:18:27 |
| 1_1__compare.jpg / _zoom.jpg | 2026-11-15 15:18:28 |
| 1_compare.jpg / _zoom.jpg | 2026-11-15 15:18:28 |
| Aadhaar-card-pdf-1-2048.jpg_compare.jpg / _zoom.jpg | 2026-11-15 15:18:29 |
| images_compare.jpg / _zoom.jpg | 2026-11-15 15:18:29 |
| signature-before_compare.jpg / _zoom.jpg | 2026-11-15 15:18:30 |

Notes/`speed.txt`: 2026-11-15 15:18:30 / 15:18:45.

Observations:
- Auto: Aadhaar glare removed and card white with crisp text; notebook paper white with blue ink
  kept; textbook pages whiter, text sharp. Pink book cover / coloured stamps and photos keep
  colour.
- Colour boost: visibly more vivid on the green/orange Aadhaar strip, blue notebook ink, red
  headings and colour photos, while paper stays white — kept (not removed).
- B&W: text pure black on white, notebook ruling largely removed, Aadhaar portrait and other
  printed photos rendered as grey; no large shadow blobs.

## Speed

The quality test processes a 3000×2250 sampled page and asserts **< 1500 ms** for Auto (it passes
in the cloud gate). An idle host run measured Auto ≈ 1.0 s; the eval `speed.txt` run under heavy
build load measured Auto 2.6 s / Colour 1.5 s / B&W 2.8 s, so use the quality-test number for the
device-scale budget (the app samples a 12 MP photo to 3000 px before filtering).

## Quality tests (synthetic, always on)

`util/ScanEnhanceQualityTest` (9):
shadow background std drops >70%; text contrast rises; red stamp hue preserved under Auto;
B&W has <0.5% black in the shadow and keeps text black; a thin slanted ruling line is removed;
Auto is idempotent (mean luma change <8); Grey is neutral; Colour boost raises stamp saturation;
12 MP sampled page processes in <1.5 s.
