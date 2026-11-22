"""Numeric, fail-closed checks for pulled fictional ScanWhirl results.

All spatial image checks are heuristics, NOT face detection, OCR, authentication
proof, or legal/medical verification. See README for their limits and parameters.
Exit 0 = all requested checks pass; 1 = failed check; 2 = invalid input/tool error.
"""
from pathlib import Path
import argparse
import copy
import hashlib
import json
import sys
import unicodedata

import numpy as np
from PIL import Image, ImageOps, ImageFilter
from pypdf import PdfReader
import pypdfium2 as pdfium

ROOT = Path(__file__).resolve().parent.parent
FIX = ROOT / "fixtures_v2"
DEFAULTS = {
    "J1": {
        "photo": {"width": 200, "height": 230, "min_kb": 20, "max_kb": 50,
                  "format": "JPEG", "dpi": None, "dpi_tolerance": 1,
                  "aspect_error_max": .01, "face_margin_min": .02,
                  "face_centre_max_offset": .22, "skin_fraction_min": .025},
        "signature": {"width": 140, "height": 60, "min_kb": 10, "max_kb": 20,
                      "format": "JPEG", "dpi": None, "dpi_tolerance": 1},
        "require_signature": True, "kb_bytes": 1024,
    },
    "J2": {"max_kb": 200, "kb_bytes": 1024, "pages": 2,
           "page_points": [595.2756, 841.8898], "page_tolerance_points": 6,
           "render_dpi": 150, "sharpness_ratio_min": .45, "edge_contrast_ratio_min": .55,
           "content_similarity_min": .80, "fine_print_roi": [.06, .68, .94, .81],
           "source": "marksheet_scan_300dpi.pdf"},
    "J3": {"pages": 1, "render_dpi": 100, "card_match_min": .52,
           "card_width_fractions": [.22, .85], "card_scales": 25,
           "card_content_roi": [.045, .25, .96, .76], "card_overlap_max": .35,
           "front_template": "references/id_front.png", "back_template": "references/id_back.png"},
    "J7": {"pages": 5, "render_dpi": 100, "source": "rent_agreement_5p.pdf",
           "signature_roi": [.06, .73, .95, .96], "diff_threshold": 35,
           "changed_fraction_min": .001, "changed_fraction_max": .30,
           "unchanged_outside_fraction_max": .035, "signature_match_min": .48, "signature_fill_max": .55,
           "signature_width_fractions": [.08, .45], "signature_scales": 20,
           "signature_template": "references/signature_crop.png"},
    "J9": {"cer_max": .10, "normalization": "NFC", "collapse_whitespace": True},
    "J10": {"pages": 3, "filename": "Fees receipts.pdf", "order": ["aug", "sep", "oct"],
            "render_dpi": 75, "page_similarity_min": .65, "order_margin_min": .015,
            "reference_dir": "."},
}
DEFAULTS["J6"] = copy.deepcopy(DEFAULTS["J2"])


def merge(base, changes):
    for key, value in changes.items():
        if key not in base:
            raise ValueError(f"Unknown spec parameter: {key}")
        if isinstance(base[key], dict):
            if not isinstance(value, dict):
                raise ValueError(f"{key} must be an object")
            merge(base[key], value)
        else:
            base[key] = value
    return base


def refpath(value):
    p = Path(value)
    return p if p.is_absolute() else FIX / p


class Report:
    def __init__(self, journey):
        self.journey, self.checks, self.inputs = journey, [], {}

    def check(self, name, ok, measured, expected):
        record = {"check": name, "pass": bool(ok), "measured": measured, "expected": expected}
        self.checks.append(record)
        # ensure_ascii keeps Hindi usable even in a legacy Windows terminal/log.
        print(f"{'PASS' if ok else 'FAIL'} {name}: "
              f"measured={json.dumps(measured, ensure_ascii=True)} "
              f"expected={json.dumps(expected, ensure_ascii=True)}")

    def finish(self):
        ok = bool(self.checks) and all(c["pass"] for c in self.checks)
        print(f"{'PASS' if ok else 'FAIL'} {self.journey}: "
              f"{sum(c['pass'] for c in self.checks)}/{len(self.checks)} checks")
        return {"journey": self.journey, "pass": ok, "inputs": self.inputs, "checks": self.checks}


def render(path, dpi, password=None):
    doc = pdfium.PdfDocument(str(path), password=password)
    try:
        result = []
        for i in range(len(doc)):
            page = doc[i]
            try:
                bitmap = page.render(scale=dpi / 72)
                try:
                    result.append(bitmap.to_pil().convert("RGB").copy())
                finally:
                    bitmap.close()
            finally:
                page.close()
        return result
    finally:
        doc.close()


def gray(im):
    return np.asarray(im.convert("L"), dtype=np.float32)


def corr(a, b):
    a, b = a.astype(float).ravel(), b.astype(float).ravel()
    a, b = a-a.mean(), b-b.mean()
    denom = np.linalg.norm(a)*np.linalg.norm(b)
    return float(np.dot(a, b)/denom) if denom > 1e-8 else 0.


def edges(a):
    return (np.abs(np.roll(a, -1, axis=0)-a) +
            np.abs(np.roll(a, -1, axis=1)-a)) / 2


def crop_roi(im, roi):
    if len(roi) != 4 or not (0 <= roi[0] < roi[2] <= 1 and 0 <= roi[1] < roi[3] <= 1):
        raise ValueError("ROI must be normalized [left, top, right, bottom]")
    return im.crop(tuple(round(x*s) for x, s in zip(roi, [im.width, im.height]*2)))


def metrics(a):
    # Laplacian variance = sharpness; contrast sampled on source edges separately.
    lap = (np.roll(a, 1, 0)+np.roll(a, -1, 0)+np.roll(a, 1, 1)+np.roll(a, -1, 1)-4*a)
    return float(lap.var())


def ncc_map(image, template):
    """Valid normalized cross-correlation with FFT + integral local variance.

    No OpenCV/system executable. Output is (score, top-left). Flat/blank regions
    have score 0 and cannot satisfy a positive threshold.
    """
    image = np.asarray(image, dtype=np.float64)
    template = np.asarray(template, dtype=np.float64)
    h, w = template.shape
    H, W = image.shape
    if h > H or w > W or h < 3 or w < 3:
        return 0., (0, 0)
    t = template-template.mean()
    tn = np.linalg.norm(t)
    if tn < 1e-8:
        return 0., (0, 0)
    shape = (H+h-1, W+w-1)
    cross = np.fft.irfft2(np.fft.rfft2(image, s=shape) *
                         np.fft.rfft2(t[::-1, ::-1], s=shape), s=shape)
    cross = cross[h-1:H, w-1:W]

    def sums(x):
        integral = np.pad(x, ((1, 0), (1, 0))).cumsum(0).cumsum(1)
        return integral[h:, w:] - integral[:-h, w:] - integral[h:, :-w] + integral[:-h, :-w]
    variance = np.maximum(sums(image**2) - sums(image)**2/(h*w), 0)
    denom = np.sqrt(variance)*tn
    scores = np.divide(cross, denom, out=np.zeros_like(cross), where=denom > 1e-6)
    y, x = np.unravel_index(np.argmax(scores), scores.shape)
    return float(scores[y, x]), (int(x), int(y))


def match_template(image, template, fractions, scales, ink=False):
    # Work at 480px max; broad size search is intentional because card layout varies.
    im = image.copy()
    im.thumbnail((480, 640))
    a = gray(im)
    if ink:
        a = 255-a
    best = {"score": 0., "box": [0, 0, 0, 0]}
    for fraction in np.linspace(*fractions, int(scales)):
        w = max(8, round(im.width*fraction))
        h = max(3, round(w*template.height/template.width))
        t = gray(template.resize((w, h), Image.Resampling.LANCZOS))
        if ink:
            t = 255-t
        score, (x, y) = ncc_map(a, t)
        if score > best["score"]:
            best = {"score": score,
                    "box": [x/im.width, y/im.height, (x+w)/im.width, (y+h)/im.height]}
    return best


def skin_stats(im):
    a = np.asarray(im.convert("RGB"), dtype=np.float32)
    r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    # Calibrated only for the illustrated face in THIS fictional corpus.
    mask = (r > 193) & (r < 220) & (g > 137) & (g < 165) & (b > 85) & (b < 123)
    ys, xs = np.where(mask)
    if len(xs) < 12:
        return None
    # Percentiles suppress JPEG boundary outliers.
    x0, x1 = np.percentile(xs, [1, 99])
    y0, y1 = np.percentile(ys, [1, 99])
    return {"fraction": float(mask.mean()), "centre": [float(xs.mean()/im.width), float(ys.mean()/im.height)],
            "box": [float(x0/im.width), float(y0/im.height), float(x1/im.width), float(y1/im.height)],
            "shape": float((x1-x0)/(y1-y0)) if y1 > y0 else 0}


def verify_image(r, path, spec, label, kb, face=False):
    with Image.open(path) as raw:
        actual_format = raw.format
        dpi = raw.info.get("dpi")
        im = ImageOps.exif_transpose(raw).convert("RGB")
    r.check(f"{label}.format", actual_format == spec["format"], actual_format, spec["format"])
    r.check(f"{label}.pixels", im.size == (spec["width"], spec["height"]), list(im.size),
            [spec["width"], spec["height"]])
    size = path.stat().st_size/kb
    r.check(f"{label}.size_kb", spec["min_kb"] <= size <= spec["max_kb"], round(size, 4),
            [spec["min_kb"], spec["max_kb"]])
    if spec["dpi"] is not None:
        expected = spec["dpi"]
        expected = [expected, expected] if isinstance(expected, (int, float)) else expected
        r.check(f"{label}.dpi", dpi is not None and
                all(abs(a-b) <= spec["dpi_tolerance"] for a, b in zip(dpi, expected)),
                list(dpi) if dpi else None, expected)
    if face:
        baseline = skin_stats(Image.open(FIX / "references/portrait_crop.png"))
        stats = skin_stats(im)
        box = stats["box"] if stats else [0, 0, 1, 1]
        margin = min(box[0], box[1], 1-box[2], 1-box[3])
        offset = max(abs(v-.5) for v in stats["centre"]) if stats else 1.
        fraction = stats["fraction"] if stats else 0.
        r.check("photo.face_region", stats is not None and
                margin >= spec["face_margin_min"] and offset <= spec["face_centre_max_offset"] and
                fraction >= spec["skin_fraction_min"],
                {"margin": margin, "com_max_offset": offset, "skin_fraction": fraction},
                {"margin_min": spec["face_margin_min"], "com_max_offset": spec["face_centre_max_offset"],
                 "skin_fraction_min": spec["skin_fraction_min"]})
        error = abs(stats["shape"]/baseline["shape"]-1) if stats else 1.
        r.check("photo.not_stretched", error < spec["aspect_error_max"], error,
                {"aspect_error_less_than": spec["aspect_error_max"]})


def open_reader(path, password=None):
    reader = PdfReader(str(path))
    if reader.is_encrypted and (password is None or not reader.decrypt(password)):
        raise ValueError("Cannot open encrypted PDF with supplied password")
    return reader


def verify_pdf_basic(r, reader, spec):
    r.check("pdf.pages", len(reader.pages) == spec["pages"], len(reader.pages), spec["pages"])


def verify_j2(r, path, spec, args):
    reader = open_reader(path, args.password)
    verify_pdf_basic(r, reader, spec)
    size = path.stat().st_size/spec["kb_bytes"]
    r.check("pdf.size_kb", size < spec["max_kb"], size, {"less_than": spec["max_kb"]})
    for i, page in enumerate(reader.pages):
        actual = [float(page.mediabox.width), float(page.mediabox.height)]
        r.check(f"p{i+1}.A4_points", all(abs(a-b) <= spec["page_tolerance_points"]
                                      for a, b in zip(actual, spec["page_points"])),
                actual, {"points": spec["page_points"], "tolerance": spec["page_tolerance_points"]})
    source = Path(args.source) if args.source else refpath(spec["source"])
    src, dst = render(source, spec["render_dpi"]), render(path, spec["render_dpi"], args.password)
    r.check("pdf.source_page_count", len(src) == len(dst), len(dst), len(src))
    for i, (a, b) in enumerate(zip(src, dst)):
        b = b.resize(a.size)
        # Dense 7.44pt instructions, not broad page borders, determine legibility.
        aa, bb = gray(crop_roi(a, spec["fine_print_roi"])), gray(crop_roi(b, spec["fine_print_roi"]))
        sharp = metrics(bb)/max(metrics(aa), 1e-6)
        ea, eb = edges(aa), edges(bb)
        edge_mask = ea > max(12, float(np.percentile(ea, 85)))
        contrast = float(eb[edge_mask].mean()/max(ea[edge_mask].mean(), 1e-6)) if edge_mask.any() else 0.
        similarity = corr(aa, bb)
        r.check(f"p{i+1}.sharpness_ratio", sharp >= spec["sharpness_ratio_min"], sharp,
                {"min": spec["sharpness_ratio_min"], "dpi": spec["render_dpi"]})
        r.check(f"p{i+1}.edge_contrast_ratio", contrast >= spec["edge_contrast_ratio_min"], contrast,
                {"min": spec["edge_contrast_ratio_min"]})
        r.check(f"p{i+1}.content_similarity", similarity >= spec["content_similarity_min"], similarity,
                {"min": spec["content_similarity_min"]})


def verify_j3(r, path, spec, args):
    reader = PdfReader(str(path))
    r.check("pdf.encrypted", reader.is_encrypted, reader.is_encrypted, True)
    empty_fails = False
    wrong_fails = False
    if reader.is_encrypted:
        empty_fails = PdfReader(str(path)).decrypt("") == 0
        wrong = PdfReader(str(path))
        wrong_fails = wrong.decrypt("deliberately-wrong-SAMPLE-password") == 0
    r.check("pdf.empty_password_rejected", empty_fails, empty_fails, True)
    r.check("pdf.wrong_password_rejected", wrong_fails, wrong_fails, True)
    locked = False
    try:
        _ = len(PdfReader(str(path)).pages)
    except Exception as exc:
        locked = type(exc).__name__ in ("FileNotDecryptedError", "WrongPasswordError")
    r.check("pdf.pages_locked_without_password", locked, locked, True)
    supplied = args.password is not None
    decrypted = bool(reader.decrypt(args.password)) if supplied and reader.is_encrypted else False
    r.check("pdf.correct_password_works", decrypted, decrypted, True)
    if reader.is_encrypted and not decrypted:
        return
    verify_pdf_basic(r, reader, spec)
    pages = render(path, spec["render_dpi"], args.password)
    if not pages:
        return
    boxes = []
    for label in ("front", "back"):
        template = crop_roi(Image.open(refpath(spec[label+"_template"])).convert("RGB"),
                            spec["card_content_roi"])
        found = match_template(pages[0], template, spec["card_width_fractions"], spec["card_scales"])
        r.check(f"card.{label}_present", found["score"] >= spec["card_match_min"], found,
                {"min_ncc": spec["card_match_min"]})
        boxes.append(found["box"])
    a, b = boxes
    intersection = max(0, min(a[2], b[2])-max(a[0], b[0]))*max(0, min(a[3], b[3])-max(a[1], b[1]))
    minimum_area = min((a[2]-a[0])*(a[3]-a[1]), (b[2]-b[0])*(b[3]-b[1]))
    overlap = intersection/max(minimum_area, 1e-8)
    r.check("card.distinct_regions", overlap < spec["card_overlap_max"], overlap,
            {"overlap_max": spec["card_overlap_max"]})


def verify_j7(r, path, spec, args):
    reader = open_reader(path, args.password)
    verify_pdf_basic(r, reader, spec)
    source = Path(args.source) if args.source else refpath(spec["source"])
    src, dst = render(source, spec["render_dpi"]), render(path, spec["render_dpi"], args.password)
    r.check("pdf.source_page_count", len(src) == len(dst), len(dst), len(src))
    template = Image.open(Path(args.signature) if args.signature else refpath(spec["signature_template"]))
    for i, (a, b) in enumerate(zip(src, dst)):
        b = b.resize(a.size)
        delta = np.maximum(gray(a)-gray(b), 0)
        mask = delta >= spec["diff_threshold"]
        roi = spec["signature_roi"]
        x0, y0, x1, y1 = [round(x*s) for x, s in zip(roi, [a.width, a.height]*2)]
        inside = mask[y0:y1, x0:x1]
        fraction = float(inside.mean())
        outside = mask.copy()
        outside[y0:y1, x0:x1] = False
        outside_fraction = float(outside.sum()/max(1, outside.size-inside.size))
        added = Image.fromarray((255-np.clip(delta[y0:y1, x0:x1], 0, 255)).astype("uint8"))
        found = match_template(added, template, spec["signature_width_fractions"],
                               spec["signature_scales"], ink=True)
        r.check(f"p{i+1}.signature_added", spec["changed_fraction_min"] <= fraction <= spec["changed_fraction_max"],
                fraction, [spec["changed_fraction_min"], spec["changed_fraction_max"]])
        r.check(f"p{i+1}.signature_shape", found["score"] >= spec["signature_match_min"],
                found, {"min_ncc": spec["signature_match_min"]})
        # A solid block can correlate ~0.5 with a signature; strokes are thin, blocks are not.
        ys, xs = np.nonzero(inside)
        fill = float(inside.sum()/((ys.max()-ys.min()+1)*(xs.max()-xs.min()+1))) if len(xs) else 1.0
        r.check(f"p{i+1}.signature_not_solid_block", fill <= spec["signature_fill_max"],
                fill, {"max_fill_of_changed_bbox": spec["signature_fill_max"]})
        r.check(f"p{i+1}.rest_unchanged", outside_fraction <= spec["unchanged_outside_fraction_max"],
                outside_fraction, {"max": spec["unchanged_outside_fraction_max"]})


def normalized(s, spec):
    s = unicodedata.normalize(spec["normalization"], s)
    return " ".join(s.split()) if spec["collapse_whitespace"] else s


def cer(reference, actual):
    # Unicode code-point CER (not grapheme/word error rate). NFC applied by caller.
    row = list(range(len(actual)+1))
    for i, c in enumerate(reference, 1):
        new = [i]
        for j, d in enumerate(actual, 1):
            new.append(min(new[-1]+1, row[j]+1, row[j-1]+(c != d)))
        row = new
    return row[-1]/max(1, len(reference)), row[-1]


def verify_j9(r, path, spec, args):
    truth_path = Path(args.ground_truth) if args.ground_truth else FIX / "ground_truth.json"
    truth = json.loads(truth_path.read_text(encoding="utf-8"))["notice_text"]
    textpath = Path(args.ocr_text) if args.ocr_text else path
    if textpath.suffix.lower() == ".pdf":
        actual = "\n".join(p.extract_text() or "" for p in open_reader(textpath, args.password).pages)
    else:
        actual = textpath.read_text(encoding="utf-8-sig")
    gt, actual = normalized(truth, spec), normalized(actual, spec)
    error, edits = cer(gt, actual)
    r.check("ocr.nonempty", bool(actual.strip()), len(actual), {"min_codepoints": 1})
    r.check("ocr.hindi_CER", error <= spec["cer_max"],
            {"cer": error, "edits": edits, "reference_codepoints": len(gt),
             "actual_codepoints": len(actual)}, {"max": spec["cer_max"], "normalization": spec["normalization"]})


def page_similarity(a, b):
    # Whole-page fingerprints; ordering check does not trust page text/metadata.
    a = gray(ImageOps.pad(a, (160, 225), color="white"))
    b = gray(ImageOps.pad(b, (160, 225), color="white"))
    return .65*corr(a, b)+.35*corr(edges(a), edges(b))


def verify_j10(r, path, spec, args):
    reader = open_reader(path, args.password)
    verify_pdf_basic(r, reader, spec)
    expected_name = args.expected_name or spec["filename"]
    r.check("pdf.filename", path.name == expected_name, path.name, expected_name)
    order = args.order.split(",") if args.order else spec["order"]
    if len(order) != spec["pages"] or len(set(order)) != len(order):
        raise ValueError("Order must contain a unique reference per expected page")
    directory = Path(args.source) if args.source else refpath(spec["reference_dir"])
    refs = {key: render(directory / f"receipt_{key}.pdf", spec["render_dpi"])[0] for key in order}
    for i, page in enumerate(render(path, spec["render_dpi"], args.password)):
        scores = {k: page_similarity(ref, page) for k, ref in refs.items()}
        ranked = sorted(scores, key=scores.get, reverse=True)
        actual = ranked[0]
        margin = scores[actual]-scores[ranked[1]] if len(ranked) > 1 else 1.
        expected = order[i] if i < len(order) else None
        r.check(f"p{i+1}.order", actual == expected and
                scores[actual] >= spec["page_similarity_min"] and margin >= spec["order_margin_min"],
                {"closest": actual, "scores": scores, "margin": margin},
                {"reference": expected, "similarity_min": spec["page_similarity_min"],
                 "margin_min": spec["order_margin_min"]})


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--journey", required=True, choices=[f"J{i}" for i in range(1, 11)])
    parser.add_argument("--file", required=True, type=Path)
    parser.add_argument("--file2", type=Path, help="J1 signature")
    parser.add_argument("--spec", type=Path, help="JSON overrides for selected journey (not all journeys)")
    parser.add_argument("--source", help="Original PDF for J2/J7; reference PDF directory for J10")
    parser.add_argument("--password", help="FICTIONAL PDF password (do not use a real password)")
    parser.add_argument("--signature", help="J7 clean signature image template")
    parser.add_argument("--ocr-text", help="J9 UTF-8 text exported/copied from actual OCR")
    parser.add_argument("--ground-truth", help="J9 ground_truth.json")
    parser.add_argument("--order", help="J10 reference stems, e.g. oct,aug,sep")
    parser.add_argument("--expected-name", help="J10 exact filename, including .pdf")
    parser.add_argument("--json", type=Path, help="Write machine-readable report")
    args = parser.parse_args(argv)
    r = Report(args.journey)
    try:
        if args.journey not in DEFAULTS:
            r.check("journey.requires_phone_evidence", False, "Not a file-only journey",
                    "Use mission screenshots, tap/time and recovery/search/Vault evidence; no invented PASS")
        else:
            spec = copy.deepcopy(DEFAULTS[args.journey])
            if args.spec:
                merge(spec, json.loads(args.spec.read_text(encoding="utf-8-sig")))
            r.check("input.exists", args.file.is_file(), str(args.file), "existing pulled result")
            if not args.file.is_file():
                raise ValueError("Input file not found")
            for name, path in (("file", args.file), ("file2", args.file2)):
                if path and path.is_file():
                    r.inputs[name] = {"path": str(path), "bytes": path.stat().st_size,
                                      "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            r.inputs["resolved_spec"] = spec
            if args.journey == "J1":
                verify_image(r, args.file, spec["photo"], "photo", spec["kb_bytes"], face=True)
                if spec["require_signature"]:
                    r.check("signature.file_present", bool(args.file2 and args.file2.is_file()),
                            str(args.file2), "existing --file2")
                    if args.file2 and args.file2.is_file():
                        verify_image(r, args.file2, spec["signature"], "signature", spec["kb_bytes"])
            elif args.journey in ("J2", "J6"):
                verify_j2(r, args.file, spec, args)
            elif args.journey == "J3":
                verify_j3(r, args.file, spec, args)
            elif args.journey == "J7":
                verify_j7(r, args.file, spec, args)
            elif args.journey == "J9":
                verify_j9(r, args.file, spec, args)
            elif args.journey == "J10":
                verify_j10(r, args.file, spec, args)
        result = r.finish()
        if args.json:
            args.json.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        return 0 if result["pass"] else 1
    except Exception as exc:
        # Tool/input exceptions cannot turn into success. Do not print passwords.
        r.check("verification_error", False, type(exc).__name__, "valid readable inputs/spec")
        result = r.finish()
        if args.json:
            args.json.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        return 2


if __name__ == "__main__":
    sys.exit(main())
