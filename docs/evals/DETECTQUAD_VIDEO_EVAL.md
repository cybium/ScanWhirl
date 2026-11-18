# detectQuad video benchmark (scanbench)

How the owner's private phone videos are turned into a measurement of our own gallery-photo
detector, `Deskew.detectQuad` (the only detector behind `PhotoAutoCrop.crop`), and how to
re-run it.

**Everything private stays on this PC.** The videos live in
`qa/recordings/inbox` (outside every checkout), the frames in `qa/recordings/frames`. Neither
is copied into a repository, pushed, or uploaded; only numbers reach
`qa/recordings/BENCH_detectQuad.md`.

## 1. Cut the frames (WSL, no ffmpeg)

`qa/recordings/tools/extract_frames.py` decodes with OpenCV's FFmpeg backend, so no system
ffmpeg is needed:

```
wsl -d Ubuntu bash -lc "python3 -I /mnt/c/Users/Cybium/Desktop/ScanWhirl-HQ/qa/recordings/tools/extract_frames.py"
```

* one frame every 0.5 s of wall-clock video time, for the canonical clips `01`-`30`
  (`FINAL_VIDEO_INDEX.txt`) plus `EXTRA_*`; the archived `Replaced Originals` are skipped;
* upright: auto-rotation is switched off and `CAP_PROP_ORIENTATION_META` is applied once, so
  a clip stored sideways does not come out rotated twice (or not at all);
* long side downscaled to 2400 px — the size the app decodes a gallery photo to — JPEG q90;
* output `frames/<clip>/<n>.jpg` (n = 1, 2, 3 …) plus `frames/<clip>/manifest.json` with
  rotation, fps, timestamps and per-frame size (numbers only).

`python3 -I` drops the user site directory, which is where `opencv-python-headless` lives;
the script re-adds that directory by path, so `-I` still isolates the run from `PYTHONPATH`.
Useful flags: `--probe` (print clip metadata, write nothing), `--only 07,11`, `--force`.

`qa/recordings/tools/check_detectquad.py` re-implements the shipped detector in numpy and
prints corners / `maxGrad` / the share of pixels above `0.18 × maxGrad` at several detect
resolutions. It exists to sanity-check the Kotlin numbers and to see resolution effects; it is
not part of the app.

## 2. Run the eval (local only)

```
powershell -File C:\Users\Cybium\Desktop\ScanWhirl-HQ\current\tools\gate.ps1 -Lane scanbench -Tests com.scanwhirl.app.eval.DetectQuadVideoEval
```

The class name contains `Eval`, so `tools\gate.ps1` keeps the run on this PC (evals on private
files never go to the cloud). The test is **skipped, never failed**, when
`qa/recordings/frames` is missing, so CI stays green without the private data.

`DetectQuadVideoEval` reads every frame, runs `Deskew.detectQuad` exactly as the app does, and
writes `qa/recordings/BENCH_detectQuad_data.md`:

| figure | meaning |
|---|---|
| detection % | frames where a confident quad came back (those are the frames the app would auto-crop) |
| false-pos % | clips 26-30 have no document, so any quad there is a false positive |
| jitter | mean corner movement between consecutive detected frames, in frame diagonals |
| area % | quad area as a share of the frame |
| edge-hug | mean distance of the four corners from the nearest frame border (0.03 = the 3% inset corners) |
| frame-fit % | of the detected frames, the share whose four corners all sit within 5% of a border, i.e. the quad is the frame itself (harmless only when the page really fills the frame) |
| non-frame-fit % | detected and not frame-fit, as a share of all frames — the "real crop candidate" number |
| ms median / p90 | the detector call itself (decode + gradient + quad) |

`BENCH_detectQuad.md` is the readable report: per-clip table, weak scenarios, and three frame
paths per worst clip for a human to look at.

## 3. Reading the numbers honestly

`Deskew.detectQuad` returns a quad or nothing; its "confidence" is a shape sanity check
(area ≥ 6% of the frame, corners ≥ 5% apart), not a paper detector. A high detection rate
therefore does **not** mean the page was found — check `area %` and `edge-hug` together with
the frame paths before drawing conclusions, and treat the no-document clips as the real test
of whether it says "no".
