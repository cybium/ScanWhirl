# Architecture

ScanWhirl is a single-module Android app (`:app`) in Kotlin, package `com.scanwhirl.app`. Screens
are Activities; the launcher is `ui/shell/ShellActivity` with four bottom tabs (Library, Tools,
Vault, Me). Long work runs in coroutines; there is no backend, no database server and no network
layer — the document store is a directory tree in the app's private files directory.

## Package map

`app/src/main/java/com/scanwhirl/app/`

| Package | What lives there |
| --- | --- |
| *(root)* | `ScanWhirlApp` — `Application`: owns the process-wide `ScanRepository` and background `OcrIndexer`, applies the saved language, runs the privacy sweep on start. |
| `model` | `ScanDocument` and `OcrPage` data classes: one scan, its pages, metadata and OCR text. |
| `store` | Filesystem persistence for the library: `ScanRepository` plus the stores it uses (drafts, pending scans, save transactions, categories/folders, OCR store and index, stamp/signature stores, export provenance, trash, auto-export). |
| `store/vault` | The encrypted Vault: `VaultStore` (AES-256-GCM files), `VaultCrypto`/`VaultKeys` (key wrapping by passcode, recovery code, Android Keystore/biometric), session and passcode policy, remembered PDF passwords, vault backup. |
| `store/backup` | `FullBackup` — one encrypted file containing library, Vault and settings; import/export. |
| `tools` | Non-UI flows that connect screens to engines: `ScanLauncher`, `MergeFlow`, `CompressFlow`, `ResizeFlow`, `LockFlow` (protect/unprotect), `OcrFlow`, `PdfAFlow`, `VaultFlow`, `HomeHost`, `HindiTextLauncher`. |
| `ui` | Cross-feature activities and view helpers: document/PDF/OCR viewers, scan review, settings, trash, page viewer, merge screen, dialogs (`SwDialog`), crop/zoom views, insets, accessibility targets. |
| `ui/shell` | `ShellActivity` (launcher, tab host, tool dispatch) and the tab screens `LibraryScreen`, `ToolsHubScreen`, `VaultScreen`, `MeScreen`, plus `ProScreen`. |
| `ui/home` | Home list rows and thumbnails, category rail behaviour, search-match highlighting and reveal animation. |
| `ui/kit` | Design system and catalogue: `SwTheme`/`SwStyle`/`SwType`, sheets, snackbars, chips, motion, progress, `ToolRegistry` (43 tools), `ToolTile`, `DocCard`, password field and strength meter. |
| `ui/tools` | Tool entry points: `ToolSetupActivity`, `ToolResultActivity`, `BatchActivity`, ID card / passport / business card screens, PDF import sheets, tool launcher. |
| `ui/stamp` | Signature pad and stamp editor (`StampEditorActivity`, `StampCanvasView`, `SignaturePadView`). |
| `ui/protect` | Password-protect / unlock a PDF: sheets, `LockToolsActivity`, protection and unlock policy. |
| `ui/qr` | QR/barcode scanning (`QrActivity`, `QrScanner`), generating QR content, history. |
| `ui/book` | Book spread reader (`BookPagesActivity`); the tool is marked "Coming soon". |
| `ui/vault` | `VaultActivity` — unlock sheet, vault list, document actions, passcode policy. |
| `ui/backup` | `BackupActivity` — encrypted full backup and restore UI. |
| `ui/pagegrid` | Page grid and page thumbnails used by viewers, review and the page editor. |
| `util` | Engines and helpers: PDF generation and writing (`PdfGenerator`, writers for DOCX/XLSX/PPTX), PDF encryption/decryption (`PdfAes`, `PdfEncryptor`, `PdfDecryptor`, `PdfProtection`, `PdfImport`, `PdfA`), OCR (`OcrEngine`, layout/cleanup/merge), image processing (enhance, filters, deskew, crop, long image), security (Aadhaar/ID masking, sensitive scan detection, clipboard privacy, secure view), billing (`ProBilling`, `ProGate`), preferences, sharing and naming. |

Resources: `app/src/main/res/values/strings.xml` (English) and `values-hi/strings.xml` (Hindi) hold
all user-visible text; drawings, fonts, icons, animations and the few XML layouts live alongside.

## Key classes

| Class | Responsibility |
| --- | --- |
| `ui/shell/ShellActivity` | The app's front door. Hosts the four tabs, launches the ML Kit document scanner and review flow, dispatches every tool tap through `ToolRegistry` + `ToolHost`, and opens the other screens. |
| `ui/shell/LibraryScreen` | The Library: document list, search across names and OCR text, category/folder filters, selection and bulk actions, create row. |
| `ui/shell/ProScreen` and `util/ProBilling`, `util/ProGate` | The Pro offer, the one-time Play Billing product `scanwhirl_pro` (purchase, restore, acknowledge), and the single unlock-state check every Pro feature asks. Test builds default to unlocked with a free/Pro review override. |
| `ui/kit/ToolRegistry` | The catalogue: 43 `SwTool` entries with group, icon, Pro flag, status (`READY` / `BUILD` / `SOON`), keywords and launch action. Tools hub, Home quick tools and Library all read from here. |
| `store/ScanRepository` | The library's single source of truth on disk: create/save/delete scans, page list and page edits, PDF and export bookkeeping, categories, folders, tags, vault moves, trash. |
| `store/OcrStore` + `store/OcrIndexer` + `util/OcrEngine` | OCR pipeline: ML Kit recognizers (Latin and bundled Devanagari) run fully on device; results are stored per page and indexed so search can find them without touching a server. |
| `util/PdfGenerator`, `util/PdfEncryptor` / `util/PdfDecryptor` (`PdfAes`) | PDF output and password protection. Encryption is ISO 32000-2 V5/R6 AES-256; the PDF is rewritten with a fresh xref and verified before it replaces anything. |
| `store/vault/VaultStore` and `ui/vault/VaultActivity` | Vault storage (AES-256-GCM, key wrapped by passcode / recovery code / Keystore-biometric) and its UI. Vaulted documents leave the normal library. |
| `store/backup/FullBackup` and `ui/backup/BackupActivity` | Encrypted backup of library + Vault + settings into one file, and restore. |

## Conventions worth knowing

- Kotlin, no DI framework: `ScanWhirlApp.from(context)` exposes the repository and indexer; tests
  inject fakes through the same seam.
- Most UI is built in Kotlin against `ui/kit` helpers; a few screens use XML layouts.
- Feature code stays inside its package and talks to `store`/`util` through plain functions and
  suspend calls. There is no global event bus.
- Tests mirror these packages under `app/src/test/java/com/scanwhirl/app/`.
