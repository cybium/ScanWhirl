import csv, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import evaluate as ev

score = ev.load_score_module()
labels = ev.merge_labels()
dev = {c for c, _ in labels} - ev.TEST_CLIPS
test = {c for c, _ in labels} & ev.TEST_CLIPS

def report(name, path):
    rows = list(csv.DictReader(open(path, encoding="utf-8")))
    by = {(r["clip"], int(r["frame"])): r for r in rows}
    for split, clips in (("DEV", dev), ("TEST", test)):
        doc1 = ok = doc0 = fa = fa_nf = 0
        corner = []
        for (clip, frame), lab in labels.items():
            if clip not in clips or lab["has_doc"] == "2":
                continue
            r = by.get((clip, frame))
            q = ev.quad_of(r) if (r and str(r.get("has_quad")) in ("1", "1.0")) else None
            if lab["has_doc"] == "0":
                doc0 += 1
                if q is not None:
                    fa += 1
                    if not score.is_frame_fit(q, int(lab["width"]), int(lab["height"])):
                        fa_nf += 1
                continue
            truth = ev.quad_of(lab)
            if truth is None:
                continue
            doc1 += 1
            if q is None:
                continue
            iou = score.polygon_iou(q, truth, int(lab["width"]), int(lab["height"]))
            if iou >= 0.8:
                ok += 1
            err, _ = score.corner_error(q, truth, int(lab["width"]), int(lab["height"]))
            if err == err:
                corner.append(err)
        corner.sort()
        med = corner[len(corner)//2] if corner else float("nan")
        p90 = corner[int(0.9*(len(corner)-1))] if corner else float("nan")
        print(f"| {name} | {split} | {doc1} | {ok} ({ok/doc1*100:.1f}%) | {fa}/{doc0} ({fa/doc0*100:.1f}%) | "
              f"{fa_nf}/{doc0} ({fa_nf/doc0*100:.1f}%) | {med*100:.1f}% | {p90*100:.1f}% |")

for name, path in (("shipped Deskew.detectQuad", os.path.join(ev.LABELS, "pred_detectquad_raw.csv")),
                   ("shipped + frame-fit filter", os.path.join(ev.LABELS, "pred_photoautocrop.csv")),
                   ("Scanner X proto v2", os.path.join(ev.LABELS, "pred_scannerx_v2.csv"))):
    report(name, path)
