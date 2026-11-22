# Tool Atlas (emulator-only)

The phone was withdrawn. This is the **GitHub emulator** proof that every real
tool still works and looks right. It is an extension of `tools/e2e/` (same
accessibility-first `uitool.py` / `flow_driver.py`), not a new philosophy:

- `com.scanwhirl.app.test` on a single throwaway `emulator-*` with
  `ro.kernel.qemu=1`, `GITHUB_ACTIONS=true`. Any other device is refused.
- No fixed coordinates, no app-data injection, no production app, no owner
  browser, no secrets, no network in the app. Only fictional fixtures.
- Screenshots (and their uiautomator XML) are taken at every step and named
  `<tool>/NN_<what>.png`; the report links each tool to them.

## Layout

| path | role |
| --- | --- |
| `atlas_driver.py` | tool recipes, step capture, output pull, verifier call, report |
| `run_atlas.sh` | CI-only entry; checks emulator, installs verifier deps, runs driver |
| `test_atlas_driver.py` | no-device harness sanity tests (build job) |
| `fixtures_v2/make_fixtures.py` | builds the fictional JPEG/PDF corpus on the runner (Pillow + reportlab) |
| `fixtures_v2/ground_truth.json` | fictional notice text and corpus facts, regenerated with the corpus |
| `verify/verify_result.py` | numeric, fail-closed verifier (unchanged from QA) |

The corpus is **generated in CI, never committed**: the public repo's pre-push
guard rejects binary blobs. `make_fixtures.py` writes small, deterministic,
fictional pages (seed 20261122, "FICTIONAL / नमूना" on every page, only "Sample"
names) plus the reference crops the verifier template-matches against. The
original Windows `generate_fixtures.py` (Nirmala.ttc via HarfBuzz/FreeType)
cannot run on ubuntu-latest, so only its ground-truth shape is preserved.
`contact_sheet.jpg`, `.venv`, `__pycache__` and `selftest_artifacts` were not copied.

## Tools and verifier journeys

| tool id | journey | what the verifier checks |
| --- | --- | --- |
| `form_photo` | J1 | 200x230 JPEG photo + 140x60 signature |
| `resize` | – | screenshots (exact 200x230 is not a file-only spec here) |
| `compress` | J2 | <200 KB, 2 A4 pages, legible fine print vs source |
| `id_card` | J3 | encrypted, front/back templates in separate regions |
| `merge` | J10 | 3 pages, exact `Fees receipts.pdf`, aug→sep→oct order |
| `split`, `extract` | – | screenshots + pulled page counts |
| `protect` | J3 | password accepted, empty/wrong rejected, cards intact |
| `ocr` | – | screenshots (recogniser availability is environment-dependent) |
| `ocr_pdf` | J9 | NFC-normalised Hindi CER vs `ground_truth.json` |
| `pdf_to_jpg` | – | screenshots + pulled image |
| `sign_all` | J7 | a thin signature added to all 5 pages, text unchanged |
| `watermark`, `page_numbers` | – | screenshots + pulled PDF |
| `import_pdf` | – | named document appears in Library |
| `trash_restore` | J5 | **phone-evidence only** – screenshots, not a fake PASS |
| `vault` | J8 | **phone-evidence only** – screenshots, not a fake PASS |

A tool FAIL (missing control, wrong size, failed numeric check) is recorded and
**does not fail the workflow** – the job reports. A harness/precondition error
(local host, real device, missing package/fixture/verifier, no emulator) exits 2.

## Run it

```powershell
# from any lane worktree (pushes an atlas/<shortsha> branch for the push trigger)
powershell -File tools/e2e/run_e2e.ps1 -Lane emu -Atlas
# or by hand: workflow_dispatch with tools=all or tools=compress,merge,protect
```

Artifact: `atlas-<sha>` with `ATLAS_REPORT.md`, per-tool folders and the verifier
JSON logs.
