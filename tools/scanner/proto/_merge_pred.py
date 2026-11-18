import csv, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import evaluate as ev

dev = list(csv.DictReader(open(os.path.join(ev.AUDIT, "pred_scannerx_v2_dev.csv"), encoding="utf-8")))
test = list(csv.DictReader(open(os.path.join(ev.AUDIT, "pred_scannerx_v2_test.csv"), encoding="utf-8")))
rows = {(r["clip"], int(r["frame"])): r for r in dev + test}
fields = ["clip", "frame", "has_quad", "x1", "y1", "x2", "y2", "x3", "y3", "x4", "y4", "ms"]
out = os.path.join(ev.LABELS, "pred_scannerx_v2.csv")
with open(out, "w", newline="", encoding="utf-8") as handle:
    writer = csv.DictWriter(handle, fieldnames=fields)
    writer.writeheader()
    for key in sorted(rows, key=lambda k: (ev.clip_sort_key(k[0]), k[1])):
        writer.writerow({f: rows[key].get(f, "") for f in fields})
print("wrote", out, len(rows), "rows")
