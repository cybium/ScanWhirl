"""Train the Scanner X paper-segmentation network from scratch (CPU, no pretrained weights).

Task ML-003-TRAIN-V1. The model, the data policy and the selection rule are fixed by the
lane contract; this script only implements them:

* 256x256 letterboxed input, 128x128 mask logits, one global presence logit,
* mask loss = BCE + Dice on every frame that has a mask, scaled by the manifest weight,
* presence loss = BCE on every non-ignore frame (presence-only positives included),
* 50/50 real/synthetic batches, augmentation on real training frames only,
* **model selection on VAL only** (clips 05, 10, 17, 22, 28). TEST is never read here.

Every run writes a self-describing folder under ``qa/scannerx-ml/runs/<run_id>/``:
``config.json``, ``curve.csv``, ``provenance.json``, ``best.pt``/``last.pt`` with SHA256s.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import platform
import random
import subprocess
import sys
import time
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import data_ml
import infer
import model as model_lib
import postprocess

SEED = 20261119
CFG = {
    "net_size": data_ml.NET_SIZE,
    "mask_size": data_ml.MASK_SIZE,
    "batch_size": 16,
    "real_per_batch": 8,
    "epochs": 30,
    "lr": 3e-3,
    "lr_min": 2e-4,
    "weight_decay": 1e-4,
    "grad_clip": 5.0,
    "mask_loss_weight": 1.0,
    "dice_weight": 1.0,
    "presence_loss_weight": 1.0,
    "negatives_per_batch": 3,
    "synth_negatives_per_batch": 3,
    "pos_weight": 2.0,
    "t_presence": 0.5,
    "t_mask": 0.5,
    "augment": True,
    "synth_per_epoch_ratio": 1.0,
    "seed": SEED,
    "torch_threads": 4,
}


# --------------------------------------------------------------------------- utils
def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def git_sha(repo: Path) -> str:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=str(repo),
                                       text=True).strip()
    except Exception:
        return "unknown"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def log(*args) -> None:
    print(*args, flush=True)


# --------------------------------------------------------------------------- batching
def real_indices_for_batch(order, cursor: int, take: int, negatives: list[int],
                           n_negatives: int, rng: np.random.Generator) -> tuple[list[int], int]:
    """Take ``take`` real indices, forcing ``n_negatives`` true negatives into the batch.

    The NoDocument frames are only ~17 % of the real pool, so a random batch often has
    one or none; the presence head then sees almost only positives and false-accepts on
    walls. Forcing negatives is a sampling fix, not a label change.
    """
    forced: list[int] = []
    if n_negatives > 0 and negatives:
        forced = list(rng.choice(negatives, size=min(n_negatives, len(negatives)), replace=False))
    picked: list[int] = []
    while len(picked) < take - len(forced) and cursor < len(order):
        candidate = int(order[cursor])
        cursor += 1
        if candidate in forced:
            continue
        picked.append(candidate)
    batch = forced + picked
    while len(batch) < take and cursor < len(order):
        batch.append(int(order[cursor]))
        cursor += 1
    return batch, cursor


def make_real_batch(pool: data_ml.RealPool, indices, rng: np.random.Generator,
                    augment: bool) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    grids, masks, has_mask, weights, presence = [], [], [], [], []
    for index in indices:
        sample = pool.samples[index]
        grid = sample.grid
        mask = sample.mask
        if augment:
            # one decorrelated rng per sample so batch composition cannot change a sample
            local = np.random.default_rng(rng.integers(0, 2**31 - 1))
            grid, mask = data_ml.augment_pair(grid, mask, local)
        grids.append(grid)
        masks.append(mask)
        has_mask.append(mask is not None)
        weights.append(sample.weight)
        presence.append(sample.presence)
    return (np.stack(grids), _stack_masks(masks), np.asarray(has_mask, dtype=np.float32),
            np.asarray(weights, dtype=np.float32), np.asarray(presence, dtype=np.float32))


def pick_synth_indices(synth: data_ml.SynthPool, take: int, negatives: int,
                       rng: np.random.Generator) -> np.ndarray:
    """``take`` synthetic indices with at least ``negatives`` hard negatives.

    The v2 pool is ~45 % negatives by construction; forcing a floor is what stops a batch
    from being accidentally all pages when the RNG happens to favour positives.
    """
    neg_pool = synth.negatives
    pos_pool = synth.positives
    n_neg = min(negatives, take, len(neg_pool))
    chosen: list[int] = []
    if n_neg > 0:
        chosen.extend(int(v) for v in rng.choice(neg_pool, size=n_neg, replace=False))
    remaining = take - len(chosen)
    if remaining > 0:
        pool = pos_pool if pos_pool else list(range(len(synth)))
        chosen.extend(int(v) for v in rng.choice(pool, size=remaining, replace=remaining > len(pool)))
    return np.asarray(chosen, dtype=np.int64)


def make_synth_batch(synth: data_ml.SynthPool, indices) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    grids, masks, has_mask, weights, presence = [], [], [], [], []
    for index in indices:
        sample = synth.sample(int(index))
        grids.append(sample.grid)
        masks.append(sample.mask)
        has_mask.append(sample.mask is not None)
        weights.append(1.0)
        presence.append(sample.presence)
    return (np.stack(grids), _stack_masks(masks), np.asarray(has_mask, dtype=np.float32),
            np.asarray(weights, dtype=np.float32), np.asarray(presence, dtype=np.float32))


def _stack_masks(masks) -> np.ndarray:
    """Full-grid (256) masks -> 128 targets with a 2x2 majority (edge-preserving)."""
    out = np.zeros((len(masks), data_ml.MASK_SIZE, data_ml.MASK_SIZE), dtype=np.float32)
    for i, mask in enumerate(masks):
        if mask is not None:
            full = (mask > 127).astype(np.float32)
            small = full.reshape(data_ml.MASK_SIZE, 2, data_ml.MASK_SIZE, 2).mean(axis=(1, 3))
            out[i] = (small >= 0.5).astype(np.float32)
    return out


def concat_batches(*batches):
    return tuple(np.concatenate([b[i] for b in batches], axis=0) for i in range(len(batches[0])))


def to_tensors(grids, masks, has_mask, weights, presence):
    x = torch.from_numpy(grids.astype(np.float32) / 255.0)
    x = ((x - 0.5) / 0.25).permute(0, 3, 1, 2).contiguous()
    return (x, torch.from_numpy(masks), torch.from_numpy(has_mask),
            torch.from_numpy(weights), torch.from_numpy(presence))


# --------------------------------------------------------------------------- losses
def batch_loss(out: dict, mask_t, has_mask, weight, presence_t, cfg) -> tuple[torch.Tensor, dict]:
    mask_logits = out["mask"]
    bce_maps = F.binary_cross_entropy_with_logits(
        mask_logits, mask_t, reduction="none", pos_weight=torch.tensor(cfg["pos_weight"]))
    bce_per_sample = bce_maps.mean(dim=(1, 2))

    prob = torch.sigmoid(mask_logits)
    dims = (1, 2)
    intersection = (prob * mask_t).sum(dims)
    denominator = prob.sum(dims) + mask_t.sum(dims)
    dice_per_sample = 1.0 - (2.0 * intersection + 1.0) / (denominator + 1.0)

    mask_weight = has_mask * weight
    mask_total = (mask_weight * (bce_per_sample + cfg["dice_weight"] * dice_per_sample)).sum()
    mask_total = mask_total / mask_weight.sum().clamp_min(1e-6)

    presence_loss = F.binary_cross_entropy_with_logits(out["presence"], presence_t)
    total = cfg["mask_loss_weight"] * mask_total + cfg["presence_loss_weight"] * presence_loss
    with torch.no_grad():
        parts = {
            "mask_bce": float((mask_weight * bce_per_sample).sum() / mask_weight.sum().clamp_min(1e-6)),
            "mask_dice": float((mask_weight * dice_per_sample).sum() / mask_weight.sum().clamp_min(1e-6)),
            "presence": float(presence_loss),
        }
    return total, parts


# --------------------------------------------------------------------------- validation
def _iou(pred_quad, truth_quad, width: int, height: int, scorer) -> float:
    return scorer.polygon_iou([tuple(map(float, p)) for p in pred_quad],
                              [tuple(map(float, p)) for p in truth_quad], width, height)


def evaluate_split(detector: infer.MLDetector, pool: data_ml.RealPool, scorer) -> dict:
    """Recall@IoU0.8 / false accept on a manifest split, exactly as score.py counts them."""
    arrays = [sample.grid for sample in pool.samples]
    # Predict on the *stored* images, not the letterboxed grid, so the pipeline is the
    # same one the evaluation harness and the app would use.
    means = []
    mask_ious = []
    predictions = []
    for index, row in enumerate(pool.rows):
        path = data_ml.OWN_DIR / row.image
        from PIL import Image
        with Image.open(path) as img:
            rgb = np.asarray(img.convert("RGB"), dtype=np.uint8)
        pred = detector.predict_array(rgb)
        predictions.append(pred)
        if row.masked:
            truth = row.quad()
            if pred.quad is not None and truth is not None:
                box = pred.box
                width, height = box.src_w, box.src_h
                iou = _iou(pred.quad, truth, width, height, scorer)
                means.append(iou)
            else:
                means.append(0.0)
            # mask IoU at the 128 grid (diagnostic, not the deciding number)
            if row.mask:
                gt = data_ml.load_net_mask(data_ml.OWN_DIR / row.mask, pred.box)
                gt_small = gt.reshape(128, 2, 128, 2).mean(axis=(1, 3)) >= 127.5
                pm = pred.mask_prob[: max(1, int(round(pred.box.net_h / 2))),
                                    : max(1, int(round(pred.box.net_w / 2)))]
                pm_big = np.zeros((128, 128), dtype=bool)
                pm_big[: pm.shape[0], : pm.shape[1]] = pm >= detector.t_mask
                union = np.logical_or(pm_big, gt_small).sum()
                mask_ious.append(float(np.logical_and(pm_big, gt_small).sum() / union) if union else 0.0)

    with_corners = [r for r in pool.rows if r.has_doc == 1 and r.mask]
    if len(with_corners) != len(means):
        raise RuntimeError("validation bookkeeping mismatch")
    recall = float(np.mean([m >= 0.8 for m in means])) if means else 0.0
    negatives = [i for i, r in enumerate(pool.rows) if r.has_doc == 0]
    false_accepts = sum(1 for i in negatives if predictions[i].quad is not None)
    presence_truth = np.asarray([1.0 if r.has_doc == 1 else 0.0 for r in pool.rows])
    presence_pred = np.asarray([p.presence for p in predictions])
    return {
        "val_frames": len(pool.rows),
        "val_recall_iou80": recall,
        "val_corner_iou_mean": float(np.mean(means)) if means else 0.0,
        "val_mask_iou_mean": float(np.mean(mask_ious)) if mask_ious else 0.0,
        "val_false_accept": false_accepts / max(1, len(negatives)),
        "val_false_accept_n": false_accepts,
        "val_negatives": len(negatives),
        "val_presence_acc": float(np.mean((presence_pred >= 0.5) == (presence_truth >= 0.5))),
        "selection_score": recall - 1.0 * (false_accepts / max(1, len(negatives))),
    }


def load_scorer(repo: Path):
    """Import the unchanged judge. Registered in sys.modules first: score.py uses
    dataclasses, which needs the module to be findable while it executes."""
    import importlib.util

    if "score" in sys.modules:
        return sys.modules["score"]
    spec = importlib.util.spec_from_file_location("score", repo / "tools" / "scanner" / "score.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules["score"] = module
    spec.loader.exec_module(module)
    return module


# --------------------------------------------------------------------------- main
def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--run-id", default="")
    parser.add_argument("--epochs", type=int, default=CFG["epochs"])
    parser.add_argument("--batch-size", type=int, default=CFG["batch_size"])
    parser.add_argument("--lr", type=float, default=CFG["lr"])
    parser.add_argument("--seed", type=int, default=CFG["seed"])
    parser.add_argument("--threads", type=int, default=CFG["torch_threads"])
    parser.add_argument("--limit-batches", type=int, default=0,
                        help="debug: only N batches per epoch")
    parser.add_argument("--val-every", type=int, default=1)
    parser.add_argument("--no-augment", action="store_true")
    parser.add_argument("--negatives-per-batch", type=int, default=CFG["negatives_per_batch"],
                        help="real NoDocument frames forced into every real half-batch")
    parser.add_argument("--presence-weight", type=float, default=CFG["presence_loss_weight"])
    parser.add_argument("--synth-pool-dir", default=str(data_ml.SYNTH_DIR))
    parser.add_argument("--synth-negatives-per-batch", type=int,
                        default=CFG["synth_negatives_per_batch"])
    parser.add_argument("--resume", default="", help="checkpoint to resume from")
    args = parser.parse_args(argv)

    torch.set_num_threads(max(1, args.threads))
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    random.seed(args.seed)

    here = Path(__file__).resolve()
    repo = here.parents[3]
    run_id = args.run_id or f"ml003_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"
    run_dir = data_ml.RUNS_DIR / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    cfg = dict(CFG)
    cfg.update(epochs=args.epochs, batch_size=args.batch_size, lr=args.lr, seed=args.seed,
               torch_threads=args.threads, augment=not args.no_augment,
               negatives_per_batch=args.negatives_per_batch,
               synth_negatives_per_batch=args.synth_negatives_per_batch,
               presence_loss_weight=args.presence_weight,
               synth_pool_dir=str(args.synth_pool_dir),
               real_per_batch=max(1, args.batch_size // 2))

    log(f"run {run_id} -> {run_dir}")
    train_rows = data_ml.load_manifest(split="train")
    val_rows = data_ml.load_manifest(split="val")
    # TEST is deliberately NOT loaded here: v2 selection is VAL + VAL-NEG only.
    log(f"manifest: train {len(train_rows)}, val {len(val_rows)} rows")

    t0 = time.perf_counter()
    train_pool = data_ml.RealPool(train_rows, log=log)
    val_pool = data_ml.RealPool(val_rows, log=log)
    synth_dir = Path(args.synth_pool_dir)
    synth = data_ml.SynthPool(synth_dir)
    log(f"pools ready in {time.perf_counter() - t0:.1f}s: train {len(train_pool)} usable, "
        f"val {len(val_pool)}, synth {len(synth)} ({len(synth.negatives)} negatives)")

    net = model_lib.TinyPageNet()
    params = model_lib.count_parameters(net)
    log(f"model: {params} params, widths {net.stem[0].out_channels}..., from scratch")
    opt = torch.optim.Adam(net.parameters(), lr=cfg["lr"], weight_decay=cfg["weight_decay"])
    steps_per_epoch = max(1, math.ceil(len(train_pool) / cfg["real_per_batch"]))
    if args.limit_batches:
        steps_per_epoch = min(steps_per_epoch, args.limit_batches)
    total_steps = steps_per_epoch * cfg["epochs"]
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(
        opt, T_max=max(1, total_steps), eta_min=cfg["lr_min"])

    start_epoch = 0
    if args.resume:
        payload = torch.load(args.resume, map_location="cpu", weights_only=False)
        net.load_state_dict(payload["state_dict"])
        start_epoch = int(payload.get("epoch", 0))
        log(f"resumed from {args.resume} at epoch {start_epoch}")

    scorer = load_scorer(repo)
    detector = infer.MLDetector(model=net, t_presence=cfg["t_presence"], t_mask=cfg["t_mask"],
                                threads=args.threads)

    # provenance: everything a later reader needs to reproduce this run
    provenance = {
        "run_id": run_id,
        "created_utc": utc_now(),
        "task": "ML-003-TRAIN-V1",
        "git_sha": git_sha(repo),
        "repo": str(repo),
        "python": sys.version.split()[0],
        "torch": torch.__version__,
        "platform": platform.platform(),
        "params": params,
        "trainable": "from scratch (no pretrained weights, no external checkpoint)",
        "manifest_sha256": sha256_file(data_ml.OWN_DIR / "manifest.csv"),
        "splits_sha256": sha256_file(data_ml.OWN_DIR / "splits.json"),
        "synth_pool": json.loads((synth_dir / "pool.json").read_text(encoding="utf-8")),
        "synth_manifest_sha256": sha256_file(synth_dir / "manifest.csv"),
        "synth_generator_sha256": sha256_file(here.parent / "synth2.py") if (here.parent / "synth2.py").is_file() else sha256_file(here.parent / "synth.py"),
        "cc0_manifest_sha256": sha256_file(Path(r"C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\scannerx-ml\commercial\cc0\manifest.csv")),
        "data_policy": "commercial pool only: owner recordings (manifest) + self-generated synth; "
                       "research_only_DO_NOT_TRAIN never read; web stills never trained on",
        "counts": {"train": len(train_pool), "val": len(val_pool),
                   "train_masked": len(train_pool.masked_indices)},
    }
    with open(run_dir / "config.json", "w", encoding="utf-8") as handle:
        json.dump({"config": cfg, "provenance": provenance}, handle, indent=2)

    curve_path = run_dir / "curve.csv"
    curve_fields = ["epoch", "lr", "train_total", "train_mask_bce", "train_mask_dice",
                    "train_presence", "val_recall_iou80", "val_corner_iou_mean",
                    "val_mask_iou_mean", "val_false_accept", "val_presence_acc",
                    "selection_score", "seconds"]
    with open(curve_path, "w", encoding="utf-8", newline="") as handle:
        csv.writer(handle).writerow(curve_fields)

    best_score = -1e9
    best_epoch = -1
    negatives = [i for i, sample in enumerate(train_pool.samples) if sample.presence == 0.0]
    log(f"real negatives in train pool: {len(negatives)} of {len(train_pool)} "
        f"({len(negatives) / max(1, len(train_pool)):.0%}); "
        f"forcing {cfg['negatives_per_batch']} per batch")
    for epoch in range(start_epoch, cfg["epochs"]):
        net.train()
        order = np.random.default_rng(args.seed + epoch).permutation(len(train_pool))
        synth_rng = np.random.default_rng(args.seed * 7919 + epoch)
        epoch_start = time.perf_counter()
        sums = {"total": 0.0, "mask_bce": 0.0, "mask_dice": 0.0, "presence": 0.0}
        batches = 0
        cursor = 0
        for step in range(steps_per_epoch):
            if cursor >= len(order):
                break
            take = min(cfg["real_per_batch"], len(order) - cursor)
            batch_rng = np.random.default_rng(args.seed + epoch * 100003 + step)
            real_idx, cursor = real_indices_for_batch(
                order, cursor, take, negatives, cfg["negatives_per_batch"], batch_rng)
            if not real_idx:
                break
            real = make_real_batch(train_pool, real_idx, batch_rng, cfg["augment"])
            synth_idx = pick_synth_indices(synth, take, cfg["synth_negatives_per_batch"], synth_rng)
            synth_batch = make_synth_batch(synth, synth_idx)
            merged = concat_batches(real, synth_batch)
            x, mask_t, has_mask, weight, presence_t = to_tensors(*merged)

            out = net(x)
            loss, parts = batch_loss(out, mask_t, has_mask, weight, presence_t, cfg)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(net.parameters(), cfg["grad_clip"])
            opt.step()
            scheduler.step()

            sums["total"] += loss.item()
            for key in ("mask_bce", "mask_dice", "presence"):
                sums[key] += parts[key]
            batches += 1
            if args.limit_batches and step + 1 >= args.limit_batches:
                break

        train_metrics = {key: value / max(1, batches) for key, value in sums.items()}

        val_metrics = {}
        if (epoch + 1) % max(1, args.val_every) == 0 or epoch == cfg["epochs"] - 1:
            net.eval()
            val_metrics = evaluate_split(detector, val_pool, scorer)

        row = {
            "epoch": epoch + 1,
            "lr": scheduler.get_last_lr()[0],
            "train_total": round(train_metrics["total"], 5),
            "train_mask_bce": round(train_metrics["mask_bce"], 5),
            "train_mask_dice": round(train_metrics["mask_dice"], 5),
            "train_presence": round(train_metrics["presence"], 5),
            "seconds": round(time.perf_counter() - epoch_start, 1),
        }
        for key in ("val_recall_iou80", "val_corner_iou_mean", "val_mask_iou_mean",
                    "val_false_accept", "val_presence_acc", "selection_score"):
            row[key] = round(float(val_metrics.get(key, float("nan"))), 5)
        with open(curve_path, "a", encoding="utf-8", newline="") as handle:
            csv.DictWriter(handle, fieldnames=curve_fields).writerow(row)
        log(f"epoch {epoch + 1}/{cfg['epochs']} loss {row['train_total']:.4f} "
            f"(bce {row['train_mask_bce']:.4f} dice {row['train_mask_dice']:.4f} "
            f"pres {row['train_presence']:.4f}) | val recall {row['val_recall_iou80']:.3f} "
            f"iou {row['val_corner_iou_mean']:.3f} maskIoU {row['val_mask_iou_mean']:.3f} "
            f"FA {row['val_false_accept']:.3f} presAcc {row['val_presence_acc']:.3f} "
            f"| {row['seconds']:.1f}s")

        if val_metrics and val_metrics.get("selection_score", -1e9) > best_score:
            best_score = val_metrics["selection_score"]
            best_epoch = epoch + 1
            torch.save({"state_dict": net.state_dict(), "epoch": epoch + 1,
                        "selection_score": best_score, "config": cfg}, run_dir / "best.pt")
            log(f"  new best at epoch {epoch + 1}: selection_score {best_score:.4f}")

        torch.save({"state_dict": net.state_dict(), "epoch": epoch + 1, "config": cfg},
                   run_dir / "last.pt")

    # final provenance + hashes
    provenance["finished_utc"] = utc_now()
    provenance["best_epoch"] = best_epoch
    provenance["best_selection_score"] = best_score
    weights = {}
    for name in ("best.pt", "last.pt"):
        path = run_dir / name
        if path.is_file():
            weights[name] = {"sha256": sha256_file(path), "bytes": path.stat().st_size}
    provenance["weights"] = weights
    provenance["final_config"] = cfg
    with open(run_dir / "provenance.json", "w", encoding="utf-8") as handle:
        json.dump(provenance, handle, indent=2)
    log(f"done. best epoch {best_epoch} (score {best_score:.4f}); weights: "
        f"{json.dumps(weights, indent=2)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
