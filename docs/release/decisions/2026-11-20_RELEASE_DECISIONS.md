# Release decisions D-01..D-14 (lead, 2026-11-20, binding) - from the independent review's decision register
D-01 Freeze after S6B, S6C budgets, G1, G2, F11, L1, OcrIndexer locking; release 1.3.0 / versionCode 26112001, freshly signed; cancelled 1.2.3 AAB never used.
D-02 One bounded SAF copy helper for every picked-file copy (cap + free space + cancel, off main), honest EN/HI refusal; PrivacySweep removes stale pdf_fd copies (lane L1).
D-03 OCR retry: max 3 attempts per page per revision, then visible "some pages could not be read" (lane G1).
D-04 OcrIndexer queue/consumer under one lock; persistence errors keep the document pending (after G1).
D-05 Accept: startup recovery runs every launch, idempotent; a failed stage retries next launch.
D-06 Cloud backup AND device-to-device transfer copy nothing (dataExtractionRules); migration only via ScanWhirl's encrypted Full Backup (lane G2).
D-07 Vault Keystore/biometric enrol-revoke + recovery round trip = owner phone-session item; share copies 24 h (accepted X3).
D-08 Restore never updates "last backed up"; restore warnings localized, no raw exception text (lane G2).
D-09 Hindi: ScanWhirl searchable PDFs keep Devanagari (F9, PDFBox-verified); imported PDFs rasterized + OCR until keep-original import.
D-10 Rs 299 one-time Pro (owner), free/paid per PRO_PLAN.
D-11 Offline wording true about the Play services scanner/QR module download (PROGRAM 6.1).
D-12 Icons/theme/a11y polish after release (PROGRAM 6.2).
D-13 Residual risks listed in the RC folder RISKS.txt in owner wording.
D-14 Final evidence: owner phone on the exact RC + Android 16 emulator smoke on the RC SHA.
