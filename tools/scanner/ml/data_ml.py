"""Data plumbing for Scanner X ML training/evaluation (local CPU only).

Three jobs, all deterministic under an explicit seed/version:

1. **Letterbox** a camera frame into the network grid: resize so the long side is 256
   (aspect kept) and place the valid region at the top-left, padding right/bottom with
   0. The transform is returned so predictions can be mapped back to source pixels --
   every caller that consumes a model quad must use :func:`net_quad_to_source`.
2. **Augment real training frames only** (the synthetic generator already carries its own
   variation): one homography per sample (perspective jitter + scale + translate, applied
   to image *and* mask so the label stays geometrically consistent), then brightness /
   contrast / gamma, shadow, blur, noise and a JPEG round-trip.
3. **Cache the synthetic pool** from ``tools/scanner/ml/synth.py`` as letterboxed
   256x256 image/mask pairs with a fixed seed, so an epoch never waits on generation.

Nothing here downloads anything, reads the network, or touches private media outside the
owner-recorded / self-generated pools listed in ``LICENSE_REGISTER.md``.
"""

from __future__ import annotations

import csv
import io
import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional, Sequence

import numpy as np
from PIL import Image, ImageFilter

NET_SIZE = 256
MASK_STRIDE = 2
MASK_SIZE = NET_SIZE // MASK_STRIDE  # 128

QA_ROOT = Path(r"C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\scannerx-ml")
OWN_DIR = QA_ROOT / "commercial" / "own"
SYNTH_DIR = QA_ROOT / "commercial" / "synth_train_v1"
RUNS_DIR = QA_ROOT / "runs"

# Recorded-clip ground truth (private, outside git) used only to *derive* labels for the
# splits that manifest.csv already names -- never for the web stills (third-party, eval only).
STILLS_DIR = Path(r"C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\recordings\stills\web-2026-11-19")
WEB_LABELS = Path(r"C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\recordings\labels\labels_web.csv")

__all__ = [
    "NET_SIZE", "MASK_SIZE", "MASK_STRIDE", "QA_ROOT", "OWN_DIR", "SYNTH_DIR", "RUNS_DIR",
    "STILLS_DIR", "WEB_LABELS",
    "letterbox", "net_quad_to_source", "source_quad_to_net",
    "load_net_image", "load_net_mask",
    "perspective_coeffs", "augment_pair",
    "ManifestRow", "load_manifest", "RealSample", "RealPool",
    "SynthPool", "build_synth_pool",
]


# --------------------------------------------------------------------------- geometry
@dataclass(frozen=True)
class Letterbox:
    """How a source frame was mapped into the NET_SIZE x NET_SIZE network grid."""

    src_w: int
    src_h: int
    net_w: int    # width of the valid region inside the grid
    net_h: int    # height of the valid region inside the grid
    scale: float  # net_px = src_px * scale

    def to_dict(self) -> dict:
        return {"src_w": self.src_w, "src_h": self.src_h, "net_w": self.net_w,
                "net_h": self.net_h, "scale": self.scale}


def letterbox(src_w: int, src_h: int, net_size: int = NET_SIZE) -> Letterbox:
    """Aspect-preserving fit with the valid region parked at (0, 0)."""
    if src_w <= 0 or src_h <= 0:
        raise ValueError(f"bad frame size {src_w}x{src_h}")
    scale = net_size / float(max(src_w, src_h))
    net_w = max(1, min(net_size, int(round(src_w * scale))))
    net_h = max(1, min(net_size, int(round(src_h * scale))))
    return Letterbox(src_w=src_w, src_h=src_h, net_w=net_w, net_h=net_h, scale=scale)


def net_quad_to_source(quad: Sequence[Sequence[float]], box: Letterbox) -> list[tuple[float, float]]:
    """Map a quad from network-grid pixels back to source pixels, clamped to the frame."""
    if box.scale <= 0:
        raise ValueError("letterbox scale must be positive")
    out = []
    for x, y in quad:
        sx = float(x) / box.scale
        sy = float(y) / box.scale
        out.append((min(max(sx, 0.0), float(box.src_w - 1)),
                    min(max(sy, 0.0), float(box.src_h - 1))))
    return out


def source_quad_to_net(quad: Sequence[Sequence[float]], box: Letterbox) -> list[tuple[float, float]]:
    return [(float(x) * box.scale, float(y) * box.scale) for x, y in quad]


def load_net_image(path: str | Path, net_size: int = NET_SIZE) -> tuple[np.ndarray, Letterbox]:
    """Load a frame -> (uint8 HWC letterboxed grid, letterbox transform)."""
    with Image.open(path) as img:
        img = img.convert("RGB")
        src_w, src_h = img.size
        box = letterbox(src_w, src_h, net_size)
        resized = img.resize((box.net_w, box.net_h), Image.BILINEAR)
        canvas = Image.new("RGB", (net_size, net_size), (0, 0, 0))
        canvas.paste(resized, (0, 0))
        arr = np.asarray(canvas, dtype=np.uint8)
    return arr, box


def load_net_mask(path: str | Path, box: Letterbox, net_size: int = NET_SIZE) -> np.ndarray:
    """Load a stored binary mask (source resolution) into the same letterboxed grid."""
    with Image.open(path) as img:
        mask = img.convert("L").resize((box.net_w, box.net_h), Image.BILINEAR)
        binary = np.where(np.asarray(mask, dtype=np.uint8) >= 128, 255, 0).astype(np.uint8)
    canvas = np.zeros((net_size, net_size), dtype=np.uint8)
    canvas[: box.net_h, : box.net_w] = binary
    return canvas


# --------------------------------------------------------------------------- augment
def perspective_coeffs(dst: Sequence[Sequence[float]], src: Sequence[Sequence[float]]) -> tuple[float, ...]:
    """8 coefficients for ``Image.transform(PERSPECTIVE)`` mapping output->input.

    Same routine the synthetic generator uses (an 8x8 linear solve); kept here so the
    augmentation is testable on its own and does not import a private helper.
    """
    rows, rhs = [], []
    for (x, y), (sx, sy) in zip(dst, src):
        rows.append([x, y, 1, 0, 0, 0, -sx * x, -sx * y])
        rhs.append(sx)
        rows.append([0, 0, 0, x, y, 1, -sy * x, -sy * y])
        rhs.append(sy)
    solution = np.linalg.solve(np.asarray(rows, np.float64), np.asarray(rhs, np.float64))
    return tuple(float(v) for v in solution)


def augment_pair(rgb: np.ndarray, mask: Optional[np.ndarray], rng: np.random.Generator,
                 size: int = NET_SIZE) -> tuple[np.ndarray, Optional[np.ndarray]]:
    """One geometric + photometric augmentation, applied to image and mask together.

    ``rgb`` is uint8 HWC ``size`` grid, ``mask`` uint8 HW (255 = paper) or None. The same
    homography is applied to both, so the label stays consistent; the mask is resampled
    NEAREST and re-binarised so it stays a clean target.
    """
    # --- geometry: identity frame -> jittered frame (scale + translate + corner jitter)
    scale = float(rng.uniform(0.88, 1.12))
    tx = float(rng.uniform(-0.05, 0.05) * size)
    ty = float(rng.uniform(-0.05, 0.05) * size)
    jitter = 0.055 * size
    src = [(tx, ty), (tx + scale * size, ty),
           (tx + scale * size, ty + scale * size), (tx, ty + scale * size)]
    dst = [(x + float(rng.normal(0.0, jitter)), y + float(rng.normal(0.0, jitter)))
           for x, y in src]
    if abs(polygon_signed_area(dst)) < 1e-3 * size * size:
        dst = list(src)  # degenerate draw: fall back to the plain scale/translate
    coeffs = perspective_coeffs(dst, src)

    image = Image.fromarray(rgb).transform(
        (size, size), Image.PERSPECTIVE, coeffs, resample=Image.BILINEAR, fillcolor=(0, 0, 0))
    if mask is not None:
        warped = Image.fromarray(mask).transform(
            (size, size), Image.PERSPECTIVE, coeffs, resample=Image.NEAREST, fillcolor=0)
        mask = np.where(np.asarray(warped, dtype=np.uint8) >= 128, 255, 0).astype(np.uint8)

    arr = np.asarray(image, dtype=np.float32) / 255.0

    # --- photometric
    arr *= float(rng.uniform(0.82, 1.18))                              # brightness
    mean = float(arr.mean())
    arr = (arr - mean) * float(rng.uniform(0.85, 1.2)) + mean          # contrast
    gamma = float(rng.uniform(0.78, 1.28))
    arr = np.clip(arr, 0.0, 1.0) ** gamma                              # gamma

    if rng.random() < 0.45:                                            # shadow blob
        yy, xx = np.mgrid[0:size, 0:size].astype(np.float32) / float(size)
        cx, cy = rng.uniform(0.1, 0.9), rng.uniform(0.1, 0.9)
        width = rng.uniform(0.25, 0.7)
        shade = np.exp(-(((xx - cx) ** 2 + (yy - cy) ** 2) / (2.0 * width * width)))
        arr *= (1.0 - float(rng.uniform(0.15, 0.5)) * shade)[..., None]

    arr = np.clip(arr, 0.0, 1.0)

    if rng.random() < 0.5:                                             # blur
        radius = float(rng.uniform(0.3, 1.3))
        arr = np.asarray(Image.fromarray((arr * 255).astype(np.uint8)).filter(
            ImageFilter.GaussianBlur(radius)), dtype=np.float32) / 255.0
    if rng.random() < 0.5:                                             # sensor noise
        sigma = float(rng.uniform(1.0, 7.0)) / 255.0
        arr = np.clip(arr + rng.normal(0.0, sigma, arr.shape).astype(np.float32), 0.0, 1.0)
    if rng.random() < 0.5:                                             # JPEG round-trip
        quality = int(rng.integers(58, 96))
        buffer = io.BytesIO()
        Image.fromarray((arr * 255).astype(np.uint8)).save(buffer, format="JPEG", quality=quality)
        buffer.seek(0)
        arr = np.asarray(Image.open(buffer).convert("RGB"), dtype=np.float32) / 255.0

    return (arr * 255.0 + 0.5).astype(np.uint8), mask


def polygon_signed_area(points: Sequence[Sequence[float]]) -> float:
    total = 0.0
    count = len(points)
    for i in range(count):
        x1, y1 = points[i]
        x2, y2 = points[(i + 1) % count]
        total += x1 * y2 - x2 * y1
    return total / 2.0


# --------------------------------------------------------------------------- manifest
@dataclass(frozen=True)
class ManifestRow:
    split: str
    clip: str
    frame: int
    has_doc: int
    category: str
    confidence: str
    weight: float
    mask_status: str
    image: str
    mask: str
    corners: str
    notes: str

    @property
    def ignored(self) -> bool:
        """``has_doc=2`` (page present, unreliable quad) -- dropped from every loss."""
        return self.has_doc == 2

    @property
    def presence_only(self) -> bool:
        return self.has_doc == 1 and self.mask_status == "no_geometry"

    @property
    def masked(self) -> bool:
        return self.has_doc == 1 and bool(self.mask)

    def quad(self) -> Optional[list[tuple[float, float]]]:
        if not self.corners:
            return None
        return [(float(x), float(y)) for x, y in json.loads(self.corners)]


def load_manifest(path: str | Path = OWN_DIR / "manifest.csv",
                  split: Optional[str] = None) -> list[ManifestRow]:
    rows: list[ManifestRow] = []
    with open(path, encoding="utf-8", newline="") as handle:
        for raw in csv.DictReader(handle):
            if split is not None and raw["split"].strip() != split:
                continue
            rows.append(ManifestRow(
                split=raw["split"].strip(),
                clip=raw["clip"].strip(),
                frame=int(raw["frame"]),
                has_doc=int(raw["has_doc"]),
                category=raw["category"].strip(),
                confidence=raw["confidence"].strip(),
                weight=float(raw["weight"] or 1.0),
                mask_status=raw["mask_status"].strip(),
                image=raw["image"].strip(),
                mask=raw["mask"].strip(),
                corners=raw["corners"].strip(),
                notes=raw.get("notes", "").strip(),
            ))
    return rows


# --------------------------------------------------------------------------- pools
@dataclass
class RealSample:
    """One prepared training/eval item in the network grid."""

    grid: np.ndarray            # uint8 (256,256,3)
    mask: Optional[np.ndarray]  # uint8 (128,128) {0,255} or None
    presence: float             # 1.0 page present, 0.0 no page (target for the head)
    weight: float
    has_mask: bool


class RealPool:
    """All non-ignored frames of one manifest split, decoded into RAM up front.

    Clips are small (354 usable TRAIN frames, 98 VAL, 193 TEST); holding the letterboxed
    grids costs ~200 KB each, i.e. < 150 MB in the worst case, which keeps an epoch free
    of PNG decoding.
    """

    def __init__(self, rows: Iterable[ManifestRow], root: str | Path = OWN_DIR,
                 net_size: int = NET_SIZE, log=None):
        self.rows = [r for r in rows if not r.ignored]
        self.samples: list[RealSample] = []
        self.boxes: list[Letterbox] = []
        root = Path(root)
        for row in self.rows:
            grid, box = load_net_image(root / row.image, net_size)
            mask = None
            if row.masked:
                # kept at the full 256 grid so the augmentation warp can move the image
                # and the mask together; the trainer downsamples to 128 for the loss.
                mask = load_net_mask(root / row.mask, box, net_size)
            self.samples.append(RealSample(
                grid=grid, mask=mask,
                presence=1.0 if row.has_doc == 1 else 0.0,
                weight=row.weight, has_mask=mask is not None,
            ))
            self.boxes.append(box)
            if log and len(self.samples) % 50 == 0:
                log(f"  real pool: {len(self.samples)}/{len(self.rows)}")

    def __len__(self) -> int:
        return len(self.samples)

    @property
    def masked_indices(self) -> list[int]:
        return [i for i, s in enumerate(self.samples) if s.has_mask]

    @property
    def presence_indices(self) -> list[int]:
        return list(range(len(self.samples)))


class SynthPool:
    """Pre-generated synthetic samples (letterboxed image/mask pairs, fixed seed)."""

    def __init__(self, synth_dir: str | Path = SYNTH_DIR):
        self.dir = Path(synth_dir)
        manifest = self.dir / "manifest.csv"
        if not manifest.is_file():
            raise FileNotFoundError(f"synthetic pool missing: {manifest} (run build_synth_pool)")
        self.ids: list[str] = []
        self.has_doc: list[int] = []
        with open(manifest, encoding="utf-8", newline="") as handle:
            for row in csv.DictReader(handle):
                self.ids.append(row["id"])
                self.has_doc.append(int(row["has_doc"]))

    def __len__(self) -> int:
        return len(self.ids)

    def sample(self, index: int) -> RealSample:
        ident = self.ids[index]
        with Image.open(self.dir / "images" / f"{ident}.jpg") as img:
            grid = np.asarray(img.convert("RGB"), dtype=np.uint8)
        with Image.open(self.dir / "masks" / f"{ident}.png") as img:
            mask = np.where(np.asarray(img.convert("L"), dtype=np.uint8) >= 128, 255, 0)
        has_doc = self.has_doc[index]
        return RealSample(grid=grid, mask=mask.astype(np.uint8) if has_doc else None,
                          presence=1.0 if has_doc else 0.0, weight=1.0, has_mask=bool(has_doc))


def build_synth_pool(count: int = 3200, seed: int = 20261119,
                     out_dir: str | Path = SYNTH_DIR, log=print) -> Path:
    """Generate the synthetic pool with ``synth.py`` and store it letterboxed.

    Deterministic: ``numpy.random.default_rng(seed)`` drives every draw in the generator,
    so a rebuild with the same (count, seed) reproduces the pool bit for bit.
    """
    from synth import generate_sample  # local import: only needed when building

    out_dir = Path(out_dir)
    (out_dir / "images").mkdir(parents=True, exist_ok=True)
    (out_dir / "masks").mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(seed)
    rows = []
    for index in range(count):
        sample = generate_sample(index, rng)
        src_w, src_h = sample["size"]
        box = letterbox(src_w, src_h)
        image = sample["image"].resize((box.net_w, box.net_h), Image.BILINEAR)
        mask = Image.fromarray(sample["mask"]).resize((box.net_w, box.net_h), Image.BILINEAR)
        canvas = Image.new("RGB", (NET_SIZE, NET_SIZE), (0, 0, 0))
        canvas.paste(image, (0, 0))
        mcanvas = Image.new("L", (NET_SIZE, NET_SIZE), 0)
        mcanvas.paste(Image.fromarray(np.where(np.asarray(mask) >= 128, 255, 0).astype(np.uint8)), (0, 0))
        ident = f"{index:05d}"
        canvas.save(out_dir / "images" / f"{ident}.jpg", quality=92)
        mcanvas.save(out_dir / "masks" / f"{ident}.png")
        rows.append({
            "id": ident, "has_doc": sample["has_doc"],
            "paper_kind": sample["paper_kind"], "background": sample["background"],
            "hard_negative": sample["hard_negative"], "src_w": src_w, "src_h": src_h,
            "net_w": box.net_w, "net_h": box.net_h,
        })
        if log and (index + 1) % 200 == 0:
            log(f"  synth pool: {index + 1}/{count}")
    with open(out_dir / "manifest.csv", "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    meta = {"count": count, "seed": seed, "net_size": NET_SIZE,
            "generator": "tools/scanner/ml/synth.py (generate_sample)", "version": "synth_train_v1"}
    with open(out_dir / "pool.json", "w", encoding="utf-8") as handle:
        json.dump(meta, handle, indent=2)
    log(f"synth pool: {count} samples -> {out_dir}")
    return out_dir


if __name__ == "__main__":  # build the pool: python data_ml.py --n 3200 --seed 20261119
    import argparse

    parser = argparse.ArgumentParser(description="Build the letterboxed synthetic pool.")
    parser.add_argument("--n", type=int, default=3200)
    parser.add_argument("--seed", type=int, default=20261119)
    parser.add_argument("--out", default=str(SYNTH_DIR))
    args = parser.parse_args()
    build_synth_pool(count=args.n, seed=args.seed, out_dir=args.out)
