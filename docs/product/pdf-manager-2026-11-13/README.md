# ScanWhirl PDF Manager — product dossier

Created 2026-11-13. This is a design and feasibility package, not implementation.

## Files

- `SPEC.md` — full product specification, screen inventory, button map, states and acceptance criteria.
- `TOOL_MATRIX.md` — PDF tools with priority, Free/Pro split, offline feasibility and current-code status.
- `COMPETITOR_MAP.md` — PDF-manager patterns from CamScanner, Adobe, Genius and SwiftScan with screenshot evidence.
- `APK_ARCHITECTURE.md` — APK/dex/native comparison and implications for ScanWhirl’s architecture.
- `OPUS_BRIEF.md` — one-page design brief for the next agent.

## Core recommendation

Build a **PDF Manager** as the document-work hub, not another bottom sheet:

- Entry from Home (“PDF Tools” / “PDF Manager”) and the Tools surface.
- Recent PDFs and imported PDFs in one place.
- Page organizer with thumbnails, reorder, rotate, delete, extract/split.
- Optimization: compress, resize, PDF-to-images, long image.
- Security: password protect/unlock; later redaction and metadata.
- Convert: local text/Word/Excel only where fidelity can be honest.
- Documents: open the PDF viewer with visible Share/Edit/Text/Export/More.
- One-time offline Power Pack for advanced tools, not ads or cloud.

The existing code already has merge, extract, reorder, rotate, crop, compress, resize, import, searchable PDF, OCR and PDF password protection. The new product work is mostly IA, state and workflow, plus a limited set of genuinely local tools.
