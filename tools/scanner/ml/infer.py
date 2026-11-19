"""Inference wrapper: image -> page quad, shared by training, evaluation and the gallery.

One code path for all three so a number in the report is the number the model actually
produces, not a re-implementation in the report script:

    frame -> letterbox (data_ml) -> TinyPageNet -> sigmoid
          -> postprocess.detect_from_outputs (presence gate, largest component, max-area
             hull quad) -> map back to source pixels

The letterbox transform is kept with the frame, so every prediction is reported in the
source pixels the ground truth uses (the stored 512-long-side PNG for recorded clips, the
stored JPEG for the web stills).
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Iterable, Optional, Sequence

import numpy as np
import torch
from PIL import Image

import data_ml
import model as model_lib
import postprocess

DEFAULT_T_PRESENCE = postprocess.DEFAULT_T_PRESENCE
DEFAULT_T_MASK = postprocess.DEFAULT_T_MASK

__all__ = ["MLDetector", "FramePrediction"]


@dataclass
class FramePrediction:
    quad: Optional[list[tuple[float, float]]]  # source pixels, TL,TR,BR,BL
    presence: float
    mask_prob: np.ndarray
    reason: str
    ms: float
    box: data_ml.Letterbox


class MLDetector:
    """Loads a checkpoint and turns frames into quads (CPU, single-threaded per call)."""

    def __init__(self, weights: str | None = None, t_presence: float = DEFAULT_T_PRESENCE,
                 t_mask: float = DEFAULT_T_MASK, net_size: int = data_ml.NET_SIZE,
                 threads: int = 4, model: Optional[torch.nn.Module] = None):
        torch.set_num_threads(max(1, int(threads)))
        if model is not None:
            self.model = model
        else:
            if weights is None:
                raise ValueError("weights path or a model instance is required")
            payload = torch.load(weights, map_location="cpu", weights_only=False)
            state = payload.get("state_dict", payload) if isinstance(payload, dict) else payload
            widths = None
            if isinstance(payload, dict) and "widths" in payload:
                widths = tuple(payload["widths"])
            self.model = model_lib.TinyPageNet(widths=widths) if widths else model_lib.TinyPageNet()
            self.model.load_state_dict(state)
        self.model.eval()
        self.t_presence = float(t_presence)
        self.t_mask = float(t_mask)
        self.net_size = int(net_size)

    # ------------------------------------------------------------------ tensors
    def _to_tensor(self, grids: Sequence[np.ndarray]) -> torch.Tensor:
        arr = np.stack([g.astype(np.float32) / 255.0 for g in grids], axis=0)
        arr = (arr - 0.5) / 0.25  # simple fixed normalisation, no dataset statistics
        return torch.from_numpy(arr.transpose(0, 3, 1, 2)).contiguous()

    @torch.no_grad()
    def forward_grids(self, grids: Sequence[np.ndarray]) -> tuple[np.ndarray, np.ndarray]:
        """Letterboxed uint8 grids -> (mask probability 128x128 per frame, presence prob)."""
        out = self.model(self._to_tensor(grids))
        mask_prob = postprocess.sigmoid(out["mask"].numpy())
        presence = postprocess.sigmoid(out["presence"].numpy())
        return mask_prob, presence

    # ------------------------------------------------------------------ frames
    def predict_array(self, rgb: np.ndarray) -> FramePrediction:
        """Predict on an RGB uint8 array (already in source orientation)."""
        start = time.perf_counter()
        src_h, src_w = rgb.shape[:2]
        box = data_ml.letterbox(int(src_w), int(src_h), self.net_size)
        resized = Image.fromarray(rgb).resize((box.net_w, box.net_h), Image.BILINEAR)
        canvas = Image.new("RGB", (self.net_size, self.net_size), (0, 0, 0))
        canvas.paste(resized, (0, 0))
        grid = np.asarray(canvas, dtype=np.uint8)
        mask_prob, presence = self.forward_grids([grid])
        det = postprocess.detect_from_outputs(mask_prob[0], float(presence[0]), box,
                                              t_presence=self.t_presence, t_mask=self.t_mask)
        quad = None
        if det.quad is not None:
            quad = data_ml.net_quad_to_source(det.quad, box)
        return FramePrediction(quad=quad, presence=float(presence[0]), mask_prob=mask_prob[0],
                               reason=det.reason, ms=(time.perf_counter() - start) * 1000.0,
                               box=box)

    def predict_path(self, path: str) -> FramePrediction:
        with Image.open(path) as img:
            rgb = np.asarray(img.convert("RGB"), dtype=np.uint8)
        return self.predict_array(rgb)

    # ------------------------------------------------------------------ batches
    def predict_arrays(self, arrays: Iterable[np.ndarray], batch_size: int = 8) -> list[FramePrediction]:
        """Batched variant for evaluation: same maths, fewer Python/torch calls per frame."""
        arrays = list(arrays)
        boxes = []
        grids = []
        for rgb in arrays:
            src_h, src_w = rgb.shape[:2]
            box = data_ml.letterbox(int(src_w), int(src_h), self.net_size)
            resized = Image.fromarray(rgb).resize((box.net_w, box.net_h), Image.BILINEAR)
            canvas = Image.new("RGB", (self.net_size, self.net_size), (0, 0, 0))
            canvas.paste(resized, (0, 0))
            grids.append(np.asarray(canvas, dtype=np.uint8))
            boxes.append(box)
        results: list[FramePrediction] = []
        for start in range(0, len(grids), batch_size):
            chunk = grids[start:start + batch_size]
            chunk_boxes = boxes[start:start + batch_size]
            mask_prob, presence = self.forward_grids(chunk)
            for i, box in enumerate(chunk_boxes):
                det = postprocess.detect_from_outputs(
                    mask_prob[i], float(presence[i]), box,
                    t_presence=self.t_presence, t_mask=self.t_mask)
                quad = data_ml.net_quad_to_source(det.quad, box) if det.quad is not None else None
                results.append(FramePrediction(
                    quad=quad, presence=float(presence[i]), mask_prob=mask_prob[i],
                    reason=det.reason, ms=0.0, box=box))
        return results
