# Fictional fixture corpus (generated, not committed)

The public repository's pre-push guard rejects binary blobs, so the JPEG/PDF
corpus is built on the GitHub runner by `make_fixtures.py` (Pillow + reportlab)
before the Tool Atlas runs. Only the generator and the ground-truth JSON are
committed. The generated files are fictional and are overwritten on every run:

- `exam_photo.jpg`, `signature_on_paper.jpg`, `id_front.jpg`, `id_back.jpg`
- `marksheet_p1/p2.jpg`, `marksheet_scan_300dpi.pdf`, `electricity_bill_3p.pdf`
- `rent_agreement_5p.pdf`, `receipt_{aug,sep,oct}.jpg/.pdf`, `hindi_notice.jpg`
- `left_thumb.jpg`, `prescription.jpg`
- `references/portrait_crop.png`, `id_front.png`, `id_back.png`, `signature_crop.png`
- `ground_truth.json`, `manifest.json` (regenerated with matching hashes)

`ground_truth.json` is deliberately overwritten with the generator's own notice
text so J9 never compares a pulled result with a stale truth file. The original
Windows generator (`qa/phone-1.3.0-final/fixtures_v2/generate_fixtures.py`, which
needs Nirmala.ttc via HarfBuzz) was not copied: it cannot run on ubuntu-latest.
