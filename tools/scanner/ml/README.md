# Scanner X ML data tooling (research lane, CPU only)

Isolated data-preparation code for a possible on-device paper-segmentation experiment.
It does **not** touch the shipping scanner, the app's permissions, the network or any cloud
service, and it does not replace Google ML Kit. Nothing here is a trained model.

## Policy (from AGENTS.md and the Scanner X roadmap)

- No training data, weights, exports or private media in Git. Code only.
- Private workspace (never committed): `C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\scannerx-ml\`
  - `.venv\` isolated Python 3.11 environment
  - `commercial\own\` owner-recorded frames, commercially usable
  - `commercial\synth\` procedurally generated samples
  - `runs\` future training runs (config, seed, data hashes, weight hash)
- Third-party web stills in `qa\recordings\stills\web-2026-11-19` are **local evaluation only**
  and are never copied into the training pool.
- No dataset downloads here: any external corpus (e.g. SmartDoc) needs a separate licence task.
- CPU-only PyTorch. Keep to <= 4 threads and < 3 GB RAM: other workers share this PC.

## Environment

```powershell
py -3.11 -m venv C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\scannerx-ml\.venv
C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\scannerx-ml\.venv\Scripts\python.exe `
  -m pip install -r tools\scanner\ml\requirements-ml.txt
```

Pinned versions and licences: `requirements-ml.txt`, `THIRD_PARTY.md`.

## Building the owner-data pool

```powershell
cd tools\scanner\ml
C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\scannerx-ml\.venv\Scripts\python.exe build_own_dataset.py
```

Reads `qa\recordings\labels\labels_{A,B,C}.csv` (plus `labels_corrected.csv`,
which overrides matching rows when it exists) and writes to
`qa\scannerx-ml\commercial\own\`:

- `images\<clip>\<frame>.png` — resized lossless copy, long side 512 px
- `masks\<clip>\<frame>.png` — 255 = paper, rasterised from the four labelled corners
- `manifest.csv` — split, clip, frame, has_doc, category, confidence, weight,
  mask_status, corners, source frame SHA256, labeler, notes
- `splits.json` — the split rule, clip lists, per-split counts, field-test list

### Label semantics

| has_doc | meaning | mask_status | mask file | usable for |
| --- | --- | --- | --- | --- |
| 1 with 4 corners | page fully labelled | `ok` | yes | mask + presence loss |
| 1 without corners | page present, geometry not labelled | `no_geometry` | no | presence-only supervision |
| 0 | no page | `negative` | no | genuine negative (see note) |
| 2 | page present, fewer than 3 corners visible | `ignore_partial_doc` | no | **excluded from loss** |

`has_doc=2` frames are kept but marked *ignore*: they carry no reliable quad, so a mask loss
would teach wrong geometry, while treating them as negatives would teach the model to reject a
real page that is simply cropped. The trainer must drop them.

Confidence is kept as a numeric `weight` (high 1.0, med 0.7, low 0.4, blank 0.4) so uncertain
corners can be down-weighted or filtered out, rather than silently trusted or deleted.

## Simulated data

```powershell
C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\scannerx-ml\.venv\Scripts\python.exe synth.py --n 300 --seed 20261119
```

Everything is drawn by `synth.py`: white/cream/receipt/card/notebook papers with procedural
stroke-only content (no real words, fonts or copyrighted text), composited with random
perspective onto wood / fabric / marble / desk-clutter / wall / emissive-screen backgrounds,
with shadows, glare, dim light, blur, sensor noise and JPEG round-trips. Screens with UI blocks
are deliberate hard negatives.

Writes `commercial\synth\images|masks\` plus `manifest.csv`, and a 24-tile
`synth_contact_sheet.png` with the quad drawn on each frame and its mask beside it, for a
human label check.

## Splits (scene level, never frame level)

- **TEST** (fixed contract hold-out, never trained on, never used for model selection):
  clips 03, 07, 13, 15, 18, 23, 27, 29, EXTRA_04_Handwritten_Notebook.
- **VAL**: five whole clips, one per category, fixed before training:
  05 Normal, 10 Movement, 17 Lighting, 22 DifficultPaper, 28 NoDocument.
- **TRAIN**: every remaining clip.
- **FIELD-TEST**: the third-party web stills, untrained and unscored for model selection.

Consecutive frames of one scene are correlated, so a frame-level shuffle would leak scenes
across splits. `splits.json` records the rule and the clip lists; the unit test asserts no clip
appears in two splits.

## Tests

```powershell
C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\scannerx-ml\.venv\Scripts\python.exe -m unittest discover -s tools\scanner\ml -p "test_*.py"
```

Covers mask rasterisation against an independent point-in-polygon oracle and analytic area,
split disjointness/completeness, the mask policy in the written manifest (when the private data
is present), and that generated masks match the corners and that negatives stay empty.
