# fixquad — lane report

## Done: Step A only

**What shipped.** `PhotoAutoCrop.crop` now rejects a *frame-fit* quad: when all four corners of
the detected quad sit within 5% of a frame border (the `BENCH_detectQuad.md` definition), the
quad is the photo frame, not a page, and the photo is returned untouched (`Result(bytes, false)`).
Before this, `Deskew.detectQuad` on patterned surfaces (benchmark clip 03, also 13, 17, 26, 27)
picked the strongest gradient pixels, which are the pattern pixels at the 3% inset: the app
cropped a pointless ~3% border off the photo and the honest "no page edge found" hint never
showed. `Deskew` itself is unchanged; `detectQuad`'s signature is unchanged.

Files:
- `app/src/main/java/com/scanwhirl/app/util/PhotoAutoCrop.kt` — `isFrameFit(pts)` guard (internal,
  5% border distance) before `Deskew.warp`.
- `app/src/test/java/com/scanwhirl/app/util/PhotoAutoCropFrameFitTest.kt` — new unit test,
  synthetic patterned surface (16 px high-contrast checker touching all four borders) + plain
  bright page, i.e. the clip 03 failure reduced to a generated photo. Two assertions:
  1. `Deskew.detectQuad` returns a frame-fit quad on that photo (proves the failure mode);
  2. `PhotoAutoCrop.crop` returns `cropped = false` and the same bytes, and the photo keeps its
     1200x900 size.

**Red evidence (test first).**
`gate.ps1 -Lane fixquad -Tests "com.scanwhirl.app.util.PhotoAutoCropFrameFitTest"` before the
guard: `GATE FAIL fixquad tests=2 failures=1` —
`AssertionError: the frame border is not a page: nothing to crop` (the detection test passed, so
the old detector did return the frame border).

**Green evidence.**
Same command after the guard: `GATE PASS fixquad tests=2 lint=[lint skipped (lead runs it at merge)]`.

**Full gate before commit.**
`GATE PASS fixquad tests=1728 failures=0 skipped=0 lint=[skipped]`
(cloud run 44271490730).

Commit: `766d105` fixquad: reject frame-fit quads in PhotoAutoCrop (patterned surfaces no longer
get a 3% trim).

## Not done

**Step B stopped by the lead (scope).** The paper-likeness detector (bright/low-saturation/
low-texture mask, largest component, hull, corner fit, confidence, eval overlays and before/after
table) is out of scope per the owner decision: we do not build a scanner engine. Nothing of
Step B was committed to the lane; the uncommitted tree was discarded (`git restore --staged .`,
`git checkout -- .`, `git clean -fd`), lane tree is clean at `766d105`. No `qa\` files were
touched by the lane.

## Notes for the lead

- `PhotoAutoCrop`'s caller (`IdCardActivity`) was not modified.
- The guard is deliberately separate from `Deskew`: a genuinely full-frame page now falls into
  the "no confident crop" path (`cropped = false`), which is the honest state the crop screen
  offers manual adjustment for.
- Benchmark impact to verify on device: patterned/glare/empty-surface clips (03, 13, 17, 26, 27)
  now report "not cropped" instead of a 3% trim.
