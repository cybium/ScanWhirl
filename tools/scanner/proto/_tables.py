import csv, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import evaluate as ev

score = ev.load_score_module()
labels = ev.merge_labels()
cats = {}
for (clip, _), lab in labels.items():
    cats[clip] = lab["category"]

def load(path):
    return list(csv.DictReader(open(path, encoding="utf-8")))

def table(name, clip_filter):
    rows = load(os.path.join(ev.AUDIT, f"pred_scannerx_v2_{name}.csv"))
    clips = {c for c in {k[0] for k in labels} if clip_filter(c)}
    by_cat = {}
    for (clip, frame), lab in labels.items():
        if clip not in clips:
            continue
        if lab["has_doc"] == "2":
            continue
        cat = cats.get(clip, "?")
        st = by_cat.setdefault(cat, {"doc1": 0, "ok": 0, "doc0": 0, "fa": 0, "err": []})
        pred = None
        for r in rows:
            if r["clip"] == clip and int(r["frame"]) == frame:
                pred = r
                break
        q = ev.quad_of(pred) if (pred and pred["has_quad"] == "1") else None
        if lab["has_doc"] == "0":
            st["doc0"] += 1
            st["fa"] += 1 if q else 0
            continue
        truth = ev.quad_of(lab)
        if truth is None:
            continue
        st["doc1"] += 1
        if q is None:
            continue
        iou = score.polygon_iou(q, truth, int(lab["width"]), int(lab["height"]))
        if iou >= 0.8:
            st["ok"] += 1
        err, _ = score.corner_error(q, truth, int(lab["width"]), int(lab["height"]))
        if err == err:
            st["err"].append(err)
    print(f"--- {name} ---")
    for cat, st in sorted(by_cat.items()):
        errs = sorted(st["err"])
        med = errs[len(errs)//2] if errs else float("nan")
        recall = f"{st['ok']}/{st['doc1']} = {st['ok']/st['doc1']*100:.0f}%" if st["doc1"] else "n/a"
        fa = f"{st['fa']}/{st['doc0']} = {st['fa']/st['doc0']*100:.0f}%" if st["doc0"] else "n/a"
        print(f"{cat:14s} doc1={st['doc1']:3d} recall={recall:16s} doc0={st['doc0']:3d} FA={fa:12s} cmed={med:.3f}")

table("dev", lambda c: c not in ev.TEST_CLIPS)
table("test", lambda c: c in ev.TEST_CLIPS)

# per clip for DEV
rows = load(os.path.join(ev.AUDIT, "pred_scannerx_v2_dev.csv"))
by = {(r["clip"], int(r["frame"])): r for r in rows}
print("\n--- per clip DEV ---")
per = {}
for (clip, frame), lab in labels.items():
    if clip in ev.TEST_CLIPS or lab["has_doc"] != "1" or not lab["x1"].strip():
        continue
    r = by.get((clip, frame))
    q = ev.quad_of(r) if (r and r["has_quad"] == "1") else None
    truth = ev.quad_of(lab)
    st = per.setdefault(clip, [0, 0, []])
    st[0] += 1
    if q is not None:
        iou = score.polygon_iou(q, truth, int(lab["width"]), int(lab["height"]))
        st[1] += 1 if iou >= 0.8 else 0
        err, _ = score.corner_error(q, truth, int(lab["width"]), int(lab["height"]))
        if err == err:
            st[2].append(err)
for clip, st in sorted(per.items(), key=lambda kv: ev.clip_sort_key(kv[0])):
    errs = sorted(st[2])
    med = errs[len(errs)//2] if errs else float("nan")
    print(f"{clip:8s} {st[1]:2d}/{st[0]:2d}  cmed={med:.3f}")
