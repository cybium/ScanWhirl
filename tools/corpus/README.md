# Private real-file regression suite

From a lane worktree on the ScanWhirl QA Windows machine:

```powershell
powershell -NoProfile -File tools\corpus\run_corpus.ps1
```

`-CorpusPath` overrides the default `ScanWhirl-HQ\qa\corpus`. The runner takes the same
FIFO build lock as `tools/gate.ps1`, rsyncs only the source worktree into WSL and
reads the external corpus in place. It does not use GitHub Actions or a phone.
The regular test gate filters out this package unless `-Dscanwhirl.corpus=true`.
For a targeted reproduction use `-Tools to_word,to_text` (one comma-separated string).
Targeted results go to their own `RESULTS/repro-…` folder and never replace the full run.

The suite prints the actual revision (with `-dirty` for a changed checkout) in
`RESULTS/RESULTS.md`, writes one row per matrix input in `RESULTS/results.csv`,
and leaves the Gradle log beside the reports. Read the completion flag and the
Gradle exit status; a terminated run is not a pass. The suite also tries the
malformed PDFs in `pdf_broken_truncated/`. Only the evidence is retained, not
the generated documents. Do not commit the corpus or its reports.

Rows are at the engine or repository boundary, not end-to-end UI tests.
A PASS for a Coming-soon tile tests dispatch, not an unshipped conversion.
Compression checks the real result screen against measured bytes; passport checks
the gallery-to-crop output ratio, not biometric acceptance. The Vault adapter tests
the real storage engine with a test key-store stand-in, not device authentication.
Word and Excel can use PDFBox-extracted vector text as labelled OCR **input**
to the app exporter; they do not measure real OCR. A PDF/A result checks
structure, not formal conformance. Where Android's PDF renderer is unavailable
on the host, downstream tests may use a labelled PDFBox-rendered JPEG fixture;
the separate import tests never replace Android import with PDFBox.
Anything needing the camera, Play services, device credentials, or visual
review must be verified separately. The report includes the device recipes.
