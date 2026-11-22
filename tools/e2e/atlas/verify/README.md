# Pulled result verifier

Run from `qa/phone-1.3.0-final`, **always Python `-I`**, using `.venv`:

```text
verify/.venv/Scripts/python.exe -I verify/verify_result.py --journey J1 --file pulled/photo.jpg --file2 pulled/signature.jpg
verify/.venv/Scripts/python.exe -I verify/verify_result.py --journey J1 --file pulled/photo.jpg --file2 pulled/signature.jpg --spec verify/portal_example.json
verify/.venv/Scripts/python.exe -I verify/verify_result.py --journey J2 --file pulled/marksheet.pdf
verify/.venv/Scripts/python.exe -I verify/verify_result.py --journey J3 --file pulled/id.pdf --password Sample-Only-42
verify/.venv/Scripts/python.exe -I verify/verify_result.py --journey J7 --file pulled/signed.pdf
verify/.venv/Scripts/python.exe -I verify/verify_result.py --journey J9 --file pulled/notice_ocr.txt
verify/.venv/Scripts/python.exe -I verify/verify_result.py --journey J10 --file "pulled/Fees receipts.pdf" --order aug,sep,oct
```

Each check prints `PASS`/`FAIL`, **measured values and exact expectations**.
Exit `0`: all checks passed; `1`: failed checks; `2`: unreadable input/tool/spec.
Use `--json report.json` for machine-readable evidence. Preserve original pulled
filename for J10; an adb pull rename cannot prove the app named it correctly.
Use only **fictional** passwords in CLI arguments; do not put a real password in logs.

## Parameters

`--spec <JSON file>` overrides the selected journey's defaults. Unknown keys fail,
not silently ignored. `verify_result.py::DEFAULTS` is the complete schema.
No fixture-dependent numeric threshold is hidden in a test runner.
KB defaults to **1024 bytes**; set `kb_bytes: 1000` when a portal means decimal KB.

| Journey | Default numeric/file criteria |
|---|---|
| J1 | JPEG photo 200×230, 20–50 KB; JPEG signature 140×60, 10–20 KB. DPI optional; override `photo.dpi`/`signature.dpi`. Illustrated-skin COM ≤0.22 from centre, margin ≥0.02, skin area ≥0.025; skin-box aspect error <0.01 vs clean reference. |
| J2/J6 | PDF strictly <200 KB, 2 pages; A4 within 6pt; render 150 DPI; **fine-print region** sharpness ratio ≥0.45 and source-edge contrast ratio ≥0.55, content correlation ≥0.80. |
| J3 | Exactly 1 page; encrypted; empty/wrong password rejected; page access without password fails; correct password works. Front/back **template matches ≥0.52 NCC** in separate regions, not merely image count. |
| J7 | 5 pages; each page's positive darkening diff inside normalized signature ROI `[.06,.73,.95,.96]` has area fraction .001–.30, matches known signature ≥0.48 NCC and is not a solid block (changed-pixel fill of its bounding box ≤0.55); changed fraction outside ROI ≤.035. |
| J9 | UTF-8 pasted/copied OCR text (or text-layer PDF), NFC-normalized Unicode code-point CER ≤0.10. Whitespace collapses, punctuation/digits/matras remain significant. Empty text fails. |
| J10 | 3 pages, exact filename `Fees receipts.pdf`; each page's whole-page visual fingerprint matches requested reference with score ≥.65 and runner-up margin ≥.015. `--order oct,aug,sep` overrides order. |

`--source` overrides the baseline PDF for J2/J7; use the **actual pre-tool export**
when the phone journey first crops/rebuilds camera inputs. The default J2 source
is the supplied two-page scanned PDF. Misaligned/cropped pages must not be
declared illegible from an invalid reference: preserve and provide the right
baseline. `fine_print_roi` is normalized `[left,top,right,bottom]`.

`--signature` overrides J7's known clean signature template; use the exact
fictional signature the phone agent applied. `--ground-truth` overrides J9 JSON;
`--ocr-text` optionally supplies copied OCR text separate from `--file`.
J9 does **not** secretly re-OCR an image or compare ground truth with itself.

J4/J5/J8 deliberately fail with `journey.requires_phone_evidence`: a pulled file
cannot prove search opened page 2, Trash/Undo recovered the file, or Vault moved
it safely. Record those with screenshots and before/after SHA-256 on the phone.

## Limits / honest interpretation

- J1 is calibrated to this illustrated portrait. It is a centre-of-mass/shape
  heuristic, **not a real-person face detector**. Subtle stretch inference from
  compressed pixels is approximate. Review the actual crop visually too.
- J2 numeric edge/sharpness ratios are regression proxies, not a guarantee of
  human legibility. Content correlation rejects blank/noise "sharpness" gaming.
  A human still checks 7–8pt text at zoom. Reference alignment is required.
- J3 template matching tolerates scale/placement, not arbitrary perspective,
  severe glare or overlapping cards. Use rectified card crops, as the tool does.
- J7 known-template diff detects arbitrary rectangles and missing pages; it is
  **not proof of a legally valid signature**. Rotation/rescan needs registration.
- J10 defaults compare the PDF receipt fixtures. If using camera photos instead,
  first export each one-page crop, then use equivalent local PDF references.
  Supply their directory with `--source`; name them `receipt_<stem>.pdf`
  and use those stems in `--order`. Originals stay untouched.
- Synthetic controls validate this verifier, **not the app**. No phone journey,
  APK, app output or real-device success is claimed by the control results.

## Evidence / reproduce

```text
verify/.venv/Scripts/python.exe -I verify/audit_fixtures.py
verify/.venv/Scripts/python.exe -I verify/selftest.py
```

See `selftest_artifacts/SUMMARY.md` and `SUMMARY.json`, with per-case JSON + logs.
Controls include good outputs and deliberately wrong size, pixels, DPI, format,
crop, stretch, page count, A4 geometry, blur, blank pages, sharp noise, passwords,
missing card sides, missing signatures, arbitrary marks, damaged page content,
wrong Hindi, filename and receipt order.
