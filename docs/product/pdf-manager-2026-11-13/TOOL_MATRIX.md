# PDF tool matrix — priority, Free/PRO, feasibility

Legend: **P0** needed for a credible first release; **P1** next differentiator; **P2** later/advanced; **P3** speculative.  
**Free/PRO** is a recommendation, not a decision.
**Feasibility** refers to a 100% offline Android implementation with the current codebase.

## Existing capability in ScanWhirl

Already present in source:

- Merge documents / PDFs.
- Extract pages.
- Reorder pages.
- Rotate pages.
- Crop pages.
- Delete pages.
- Compress.
- Resize images.
- Import PDF as page images.
- Searchable PDF text layer.
- OCR editor.
- Password protect/unlock (RC4-128).
- Share, save, trash, export records.
- Archive/ZIP export.
- Page rendering.

Present but not surfaced as a manager:

- PDF page grid and drag reorder.
- Per-page rotate/crop/filter/delete.
- PDF viewer with Find.
- Lock/unlock.

Missing product surface:

- One PDF Manager home.
- Consolidated recent PDFs/Imports.
- Visible document action dock.
- Split by range.
- PDF-to-images / long image.
- Word/Excel/TXT export.
- PDF/A.
- Signature/annotation/watermark.
- Secure redaction.
- Batch operations.
- Metadata/page numbers.
- PDF form handling.
- Folders/tags in the PDF manager.

## Tool matrix

| Tool | User outcome | Priority | Free / PRO | Offline feasibility | Current status | Competitor reference |
|---|---|---|---|---|---|---|
| **PDF Manager home** | Find/open/act on any PDF | P0 | Free | High | Not built; library + Tools sheet | ACE Home tool grid; CamScanner Files/All; Adobe Home/Files |
| **Import PDF** | Get external PDFs into the library | P0 | Free | High | Exists via Import; rasterize warning | Adobe Create from photos/scan; CamScanner Import Files |
| **Viewer + page stack** | Read, search, share | P0 | Free | High | Viewer exists with Find; no dock | Adobe viewer; CamScanner document; Swift review |
| **Merge** | Combine in explicit order | P0 | Free | High | Exists, name-only picker | Adobe Combine; CamScanner Merge; Swift |
| **Organize pages** | Reorder/rotate/delete/insert | P0 | Free | High | Exists; hidden behind Home overflow | CamScanner page editor; Adobe Modify |
| **Extract pages** | New PDF from selected pages | P0 | Free | High | Exists in repository | CamScanner PDF Extract; Adobe page selection |
| **Split by range** | Break a large PDF into parts | P1 | Free or PRO | High | Not exposed | CamScanner PDF Extract; Adobe split not verified |
| **Compress** | Smaller file under a limit | P0 | Free | High | Exists; ambiguous target UI | Adobe Compress; CamScanner PDF Compress |
| **Resize** | Exact pixel/DPI output | P0 | Free | High | Exists (image); keyboard issue | CamScanner resize? not verified; Swift quality/size |
| **PDF to images** | PNG/JPEG pages or ZIP | P1 | Free | High | Not exposed; renderer exists | CamScanner PDF to Images; Adobe export |
| **PDF to long image** | One tall image for chat | P1 | PRO | High | Not implemented | CamScanner PDF to Long Image |
| **Protect PDF** | Password-protected copy | P0 | Free | High | Exists; weak UI | Adobe password; Genius Ultra; CamScanner Lock |
| **Unlock PDF** | Open a supported locked PDF | P0 | Free | Medium | Exists for supported locks | Adobe open; CamScanner; Genius |
| **Locked badges/list state** | Know which files are protected | P0 | Free | High | Not shown | Adobe lock badge; CamScanner lock |
| **PDF/A** | Archival export | P2 | PRO | Low/medium | Not implemented | Genius Ultra lists PDF/A-1b; Adobe not verified |
| **Searchable PDF control** | Per-export text layer | P1 | Free/PRO | High | Global setting exists | Genius Ultra; Adobe OCR |
| **OCR / text extraction** | Editable/searchable text | P0 | Free | Medium; ML Kit model dependency | Exists | Adobe Edit text; Genius OCR; Swift OCR; CamScanner |
| **To TXT** | Plain text export | P1 | Free/PRO | High | Text export exists in OCR editor | CamScanner text; Genius text |
| **To Word** | Editable document | P1/P2 | PRO | High effort for layout; text-only is feasible | Not implemented | CamScanner To Word; Adobe Export; vFlat Word |
| **To Excel** | Table/spreadsheet output | P2 | PRO | High; needs table heuristics | Not implemented | CamScanner To Excel; Adobe Excel |
| **To PPT** | Slides | P3 | PRO | High | Not implemented | CamScanner To PPT; Adobe PowerPoint |
| **Signature** | Sign a document | P1 | PRO | High | Not implemented | CamScanner Sign; Adobe Fill & Sign; Swift signature |
| **Annotation / text / brush** | Review markup | P1 | PRO | High | Not implemented | CamScanner Markup; Adobe markup; Swift |
| **Watermark** | Add a label to pages | P2 | PRO | High | Not implemented | CamScanner watermark; Adobe? |
| **Secure redaction** | Remove content, not cover it | P1/P2 | PRO | Medium/high; must flatten and verify | Not implemented | Swift Redaction; Adobe cleanup is not proof of secure redaction |
| **Page numbers** | Add page numbers | P2 | PRO | High | Not implemented | CamScanner Page Title; PDF tools |
| **Metadata** | Title/author/subject | P2 | PRO | High | Not implemented | PDF tools |
| **Batch operations** | Apply to selected docs | P1 | PRO | High | Not implemented | CamScanner batch; Adobe combine |
| **Folders/tags** | Filing and retrieval | P1 | Free/PRO | High | Categories only | CamScanner Files/tags; Genius folders; Adobe Files |
| **Naming templates** | Useful names at save | P0/P1 | Free | High | Generic names | Adobe default filename; Genius date; Swift date |
| **App lock/Vault** | Protect library | P1 | PRO | High (once Vault works) | Lock exists; Vault broken | CamScanner folder password; Adobe password |
| **PDF compare** | See page differences | P3 | PRO | Medium | Not implemented | Adobe Compare? not in walk |
| **Form filling** | Fill PDF forms | P3 | PRO | Low/medium | Not implemented | Adobe Acrobat; specialized SDKs |
| **AI summary/translate** | Semantic output | Out of scope | — | No; server | Not implemented | Adobe GenAI; CamScanner AI; iScanner; against offline policy |
| **Cloud sync/fax** | Remote delivery | Out of scope | — | No; server | Not implemented | Adobe cloud; Swift Fax; CamScanner cloud |

## Recommended first release (P0)

1. PDF Manager home with search/filters and recent PDFs.
2. Import PDF with a clear rasterization warning.
3. Viewer with visible Share/Edit/Text/Export/More.
4. Merge with ordered thumbnails and naming.
5. Organize pages: reorder/rotate/delete/insert; counts update correctly.
6. Extract pages.
7. Compress with a clear before/after and a new-copy default.
8. Protect/unlock with a lock badge and search-consequence explanation.
9. Naming templates and a useful default title.
10. Undo/result states for every operation.

## Next differentiators (P1)

- Split by range.
- PDF to images / ZIP.
- PDF to long image.
- Signature/annotation/watermark.
- Secure redaction.
- Batch operations.
- Folders/tags.
- Searchable-PDF per-export controls.
- ID-card/book-spread presets from the PDF manager.

## Later / verify before promising (P2–P3)

- Word/Excel/TXT output with honest fidelity labels.
- PDF/A-1b.
- Table extraction.
- Page numbers/metadata.
- PDF forms.
- PDF compare.
- PPT.
- Local AI (only if fully on-device and optional).

## Free/PRO recommendation

**Free**
- PDF Manager, viewer, search.
- Import.
- Merge, compress, extract, reorder, rotate, delete.
- Password protect/unlock.
- Basic OCR/searchable PDF.
- Share.

**PRO PDF Power Pack**
- Split by range.
- PDF to images/long image.
- Word/Excel/TXT with a fidelity contract.
- PDF/A.
- Per-export searchable PDF controls.
- Signature, annotation, watermark.
- Redaction.
- Batch operations.
- Folders/tags and naming automation.
- ID-card/book-spread finishing.
- Metadata/page numbers.
- App lock/Vault when real.

**Price:** one-time **₹1,499** founder lifetime, regular ₹1,999; optional ₹599–₹899/year only for ongoing OCR language model updates and support. Do not sell “remove ads” because there are none.

## Hard feasibility notes

- **Office formats:** A .docx/.xlsx writer is possible offline, but layout/table fidelity from OCR is a different product. Do not promise “same layout as the PDF.” Start with text-first Word/TXT and a fidelity warning. Excel/PPT should wait.
- **PDF/A:** proper PDF/A-1b requires precise metadata, font embedding, colour profiles and validation. A hand-written PDF is not automatically archival. Do not ship a label without a validator.
- **True PDF text editing:** Adobe’s “Edit text” is a vector/text-layer editor. ScanWhirl currently rasterizes and rebuilds PDFs from images. Editing the original text layer is a large engine problem; do not confuse it with OCR text cleanup.
- **ML Kit offline:** current OCR uses Play Services models that may download on first use. A strict “100% offline” promise needs a bundled model or a clear one-time setup disclosure.
- **Encryption:** the current PDF protection uses RC4-128 and says it stops casual readers. Do not market it as modern encryption. Consider AES only if the PDF engine supports it.
- **No code reuse from competitor APKs.** The APK analysis is for architecture/feature understanding only.
