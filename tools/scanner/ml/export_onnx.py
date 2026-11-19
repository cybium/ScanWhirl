"""Export a trained run to ONNX and prove the export agrees with PyTorch.

What is written into ``runs/<run_id>/``:

* ``model.onnx`` -- opset 17, batch dimension dynamic, two outputs: ``mask`` (logits,
  N x 128 x 128) and ``presence`` (logits, N). No custom ops, no external data.
* ``model.onnx.sha256`` and the size in KB,
* a parity report: max/mean absolute difference between torch and onnxruntime on
  deterministic pseudo-random inputs, including the padded/border cases.

Parity is checked on **logits**, before any sigmoid, and a random *and* a real-frame
input is used. If the maximum difference is above ``--tol`` the script fails loudly:
a silently wrong export would be worse than no export at all.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import data_ml
import model as model_lib

OPSET = 17


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def build_model(weights: Path) -> torch.nn.Module:
    payload = torch.load(weights, map_location="cpu", weights_only=False)
    state = payload.get("state_dict", payload)
    widths = tuple(payload["widths"]) if isinstance(payload, dict) and "widths" in payload else None
    net = model_lib.TinyPageNet(widths=widths) if widths else model_lib.TinyPageNet()
    net.load_state_dict(state)
    return net.eval()


class ExportWrapper(torch.nn.Module):
    """Single dict output is friendlier for some runtimes; export two plain tensors."""

    def __init__(self, net: torch.nn.Module):
        super().__init__()
        self.net = net

    def forward(self, x: torch.Tensor):
        out = self.net(x)
        return out["mask"], out["presence"]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--weights", default="")
    parser.add_argument("--out", default="")
    parser.add_argument("--tol", type=float, default=1e-4)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args(argv)

    torch.set_num_threads(max(1, args.threads))
    run_dir = data_ml.RUNS_DIR / args.run_id
    weights = Path(args.weights) if args.weights else run_dir / "best.pt"
    if not weights.is_file():
        raise SystemExit(f"weights not found: {weights}")
    out_path = Path(args.out) if args.out else run_dir / "model.onnx"

    net = build_model(weights)
    wrapper = ExportWrapper(net).eval()
    dummy = torch.zeros(1, 3, data_ml.NET_SIZE, data_ml.NET_SIZE)

    torch.onnx.export(
        wrapper, (dummy,), str(out_path),
        input_names=["image"], output_names=["mask", "presence"],
        dynamic_axes={"image": {0: "batch"}, "mask": {0: "batch"}, "presence": {0: "batch"}},
        opset_version=OPSET, do_constant_folding=True,
        # Torch 2.15 defaults to the dynamo exporter, which needs the optional
        # `onnxscript` package that is deliberately not in requirements-ml.txt. The
        # TorchScript exporter produces the standard opset-17 graph from the same
        # weights and needs only `onnx`, so the lane stays dependency-pinned.
        dynamo=False,
    )

    import onnx  # local import: only needed for the checker
    import onnxruntime as ort

    onnx.checker.check_model(str(out_path))
    session = ort.InferenceSession(str(out_path), providers=["CPUExecutionProvider"])
    graph_inputs = {i.name for i in session.get_inputs()}
    if "image" not in graph_inputs:
        raise SystemExit(f"exported graph inputs look wrong: {graph_inputs}")

    rng = np.random.default_rng(20261119)
    parity = []
    inputs = [rng.standard_normal((1, 3, 256, 256)).astype(np.float32),
              rng.standard_normal((3, 3, 256, 256)).astype(np.float32)]
    # a real frame, letterboxed exactly as at eval time
    real_rows = data_ml.load_manifest(split="val")
    if real_rows:
        grid, _box = data_ml.load_net_image(data_ml.OWN_DIR / real_rows[0].image)
        arr = grid.astype(np.float32) / 255.0
        arr = (arr - 0.5) / 0.25
        inputs.append(arr.transpose(2, 0, 1)[None].astype(np.float32))

    with torch.no_grad():
        for index, arr in enumerate(inputs):
            torch_out = wrapper(torch.from_numpy(arr))
            onnx_out = session.run(None, {"image": arr})
            entry = {"case": index, "batch": int(arr.shape[0])}
            for name, reference, produced in (("mask", torch_out[0].numpy(), onnx_out[0]),
                                              ("presence", torch_out[1].numpy(), onnx_out[1])):
                diff = np.abs(np.asarray(reference, dtype=np.float64)
                              - np.asarray(produced, dtype=np.float64))
                entry[name] = {"max_abs_diff": float(diff.max()), "mean_abs_diff": float(diff.mean())}
            parity.append(entry)

    worst = max(entry[name]["max_abs_diff"] for entry in parity for name in ("mask", "presence"))
    size_kb = round(out_path.stat().st_size / 1024.0, 1)
    params = model_lib.count_parameters(net)
    report = {
        "run_id": args.run_id,
        "weights": str(weights),
        "weights_sha256": sha256_file(weights),
        "onnx": str(out_path),
        "onnx_sha256": sha256_file(out_path),
        "opset": OPSET,
        "size_kb": size_kb,
        "params": params,
        "input": {"name": "image", "shape": [1, 3, data_ml.NET_SIZE, data_ml.NET_SIZE],
                  "normalisation": "(pixel/255 - 0.5) / 0.25, RGB, letterboxed"},
        "outputs": {"mask": "logits 128x128 (sigmoid -> probability)",
                    "presence": "logit (sigmoid -> probability)"},
        "parity": parity,
        "max_abs_diff": worst,
        "tolerance": args.tol,
        "verdict": "PASS" if worst <= args.tol else "FAIL",
    }
    (run_dir / "onnx_report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    (run_dir / "model.onnx.sha256").write_text(f"{report['onnx_sha256']}  model.onnx\n",
                                               encoding="utf-8")
    print(f"onnx: {out_path} ({size_kb} KB), params {params}, sha256 {report['onnx_sha256']}")
    for entry in parity:
        print(f"  case {entry['case']} batch {entry['batch']}: "
              f"mask max {entry['mask']['max_abs_diff']:.3e}  "
              f"presence max {entry['presence']['max_abs_diff']:.3e}")
    print(f"parity max abs diff {worst:.3e} (tol {args.tol}) -> {report['verdict']}")
    return 0 if report["verdict"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
