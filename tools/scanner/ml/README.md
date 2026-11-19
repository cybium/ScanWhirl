# Scanner X ML tooling (research lane, CPU only)

Isolated data-preparation **and training** code for a possible on-device paper-segmentation
experiment. It does **not** touch the shipping scanner, the app's permissions, the network or
any cloud service, and it does not replace Google ML Kit.

Two stages live here:

1. **Data / labelling** (`build_own_dataset.py`, `synth.py`, `mlcommon.py`) — decides what a
   frame means and writes the private pools.
2. **Model** (this task, ML-003-TRAIN-V1) — a tiny from-scratch segmenter:
   `model.py`, `data_ml.py`, `postprocess.py`, `infer.py`, `train.py`, `evaluate_ml.py`,
   `export_onnx.py`, `report_gallery.py`, tested by `test_ml_model.py`.

Nothing in stage 2 is wired into the Android app, and no trained weight is committed.

## Model stage at a glance

| file | job |
| --- | --- |
| `model.py` | `TinyPageNet`: depthwise-separable U-Net, 256x256 in, 128x128 mask logits + one presence logit, ~0.39 M params, no pretrained weights |
| `data_ml.py` | letterbox transform (aspect kept, pad right/bottom), same transform applied to image and mask during augmentation, manifest loader, synthetic pool builder |
| `postprocess.py` | presence gate -> largest component -> area/wall rejection -> hull -> maximum-area inscribed quad -> TL,TR,BR,BL |
| `infer.py` | one inference path shared by training, evaluation and the gallery |
| `train.py` | 50/50 real/synthetic batches, mask BCE+Dice (manifest weight), presence BCE, selection on VAL only |
| `evaluate_ml.py` | writes predictions in `score.py` format, runs the **unchanged** judge for shipped / r2 / trained model on identical frames; `--sweep` selects thresholds on VAL |
| `export_onnx.py` | opset-17 export plus an onnxruntime parity check (fails above 1e-4 logit difference) |
| `report_gallery.py` | 4-panel visual report (original, ground truth, r2, trained model) + offline `index.html`, failures first; adds a v1 column when `pred_<split>_v1.csv` exists |
| `synth2.py` | second generator (ML-004): composite CC0-photo backgrounds, hard negatives (document on a screen, framed pictures, white boxes, tissues, doors/walls, cluttered desk) and hand-held pages filling the frame |
| `hybrid_gate.py` | optional veto: r2's hand-written screen cues evaluated inside the trained quad, used to drop screen-like detections |
| `test_ml_v2.py` | properties of the v2 generator (label/pixel consistency, determinism, occluded mask) and of the veto |

Commands (private workspace, never Git):

```powershell
$py = "C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\scannerx-ml\.venv\Scripts\python.exe"
cd tools\scanner\ml
& $py data_ml.py --n 3200 --seed 20261119        # synthetic pool, deterministic
& $py train.py --run-id ml003_v1 --epochs 40     # writes runs\ml003_v1\
& $py evaluate_ml.py --run-id ml003_v1 --sweep   # VAL-only threshold choice
& $py evaluate_ml.py --run-id ml003_v1 --split val
& $py export_onnx.py --run-id ml003_v1
& $py -m unittest test_ml_model
```

The TEST hold-out (9 clips + the 9 third-party web stills) may be scored **twice, ever**;
`evaluate_ml.py` refuses `--split test|web` without `--test` and appends every such run to
`qa\scannerx-ml\runs\test_runs.log` with the weight SHA256.

## v2 data and the VAL-NEG proxy (ML-004)

```powershell
& $py train.py --run-id ml004_v2 --epochs 40 --batch-size 16 `
    --synth-pool-dir <qa>\commercial\synth_train_v2 `
    --negatives-per-batch 3 --synth-negatives-per-batch 3 --presence-weight 1.0
& $py evaluate_ml.py --run-id ml004_v2 --sweep          # VAL + VAL-NEG, both FA columns
& $py evaluate_ml.py --run-id ml004_v2 --hybrid-sweep --t-presence <p> --t-mask <m>
& $py evaluate_ml.py --run-id ml004_v2 --split valneg   # proxy only, never TEST
```

* **CC0 pool** (`qa\commercial\cc0\`, 44 Poly Haven assets, CC0): background textures for the
  generator plus pure-texture negatives. 14 assets are held out and never trained on.
* **VAL-NEG proxy**: 600 synthetic hard negatives generated with a *different seed* and only
  the held-out CC0 assets, plus 42 crops of those held-out photos. It is a **proxy** for
  "a no-document scene": it tests screen/box/wall/clutter recognitions on rendered scenes and
  rich texture, not a photograph of a real desk.
* Thresholds must keep false accepts at or below 5 % on **both** clip 28 and the proxy while
  maximising recall on VAL positives.
* The hybrid screen veto is adopted only if it improves VAL without spending recall; it can
  only remove a quad, never invent one.

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
