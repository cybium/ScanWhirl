# Evidence map

These are current device screenshots from the M08 walk on an Infinix X6711, Android 14, candidate D. They are not stills from the original 5:18 audit recording.

| File | Screen | Use |
|---|---|---|
| `a01_home.png` | Home library | Density, card height, chips, search entry. |
| `a02_search_teratoma.png` | Search results | Snippet and match card density. |
| `a04_doc1_viewer.png` | Document viewer | Dock and page overview. |
| `a08_edit_pages.png` | Edit pages | Grid, selection, drag handles. |
| `a09_doc_more.png` | Document More sheet | Organise/tools/danger placement. |
| `a11_tools.png` | Tools sheet | Tool grid and Vault/Lock visibility. |
| `a13_import.png` | Import sheet | Photos versus PDF choice. |
| `a14_settings.png` | Settings | Appearance/Library/Language/Viewing. |
| `a2_resize.png` | Resize | Presets, free field, Advanced. |
| `a2_quicksort.png` | Quick sort | Category assignment. |
| `a4_text.png` | Extracted text | OCR actions and density. |
| `a5_trash.png` | Trash | Empty-state copy and overflow. |
| `b04_book_mode_now.png` | Book capture | Spread handling. |
| `c05_filter_compare.png` | Filter compare | Existing-page filter result. |
| `d01_google_preview.png` | Google scanner | Capture/preview handoff. |

The full recording remains outside this workspace. The critique cites it by timestamp and screen name.

## Device walk — candidate D, 2026-11-13

The `evidence/device/` folder contains the ADB walk of the installed 1.1.1-test27 candidate D. Key files:

| File | Surface | Why it matters |
|---|---|---|
| `home_initial.png` | Home | Current card density, chips, titles. |
| `settings_top.png`, `settings_lower.png` | Settings | Starfield, status/wallpaper, “Google processing”, searchable PDF. |
| `about.png` | About | Version, privacy claim, placeholder C mark. |
| `tools.png` | Tools | Four tools + full-width Quick sort + gold PRO band with Lock/Vault. |
| `vault_tap.png` | Vault result | “Adding to Hidden Vault is unavailable.” |
| `lock_chooser.png` | Lock/Unlock | Ambiguous text-only chooser. |
| `pro_dialog2.png` | PRO | Test-build “unlocked” state. |
| `free_tools.png`, `free_lock_chooser.png` | FREE preview | FREE header, PRO tools still shown; tapping Lock says “Not available yet.” |
| `merge_chooser2.png`, `merge_scan_list.png` | Merge | Name-only list, duplicate M08 Book1, clipped final row. |
| `compress_dialog.png`, `compress_keyboard.png` | Compress | Preset/target ambiguity and keyboard. |
| `resize_dialog.png`, `resize_keyboard.png` | Resize | 500 KB orphan, hint/value ambiguity, hidden actions. |
| `import_sheet.png` | Import | Copy-heavy bottom sheet. |
| `review_import2.png`, `review_filter_sheet.png`, `review_bw.png` | Import review | Small preview, dialog filter, B/W result on screenshot. |
| `docx_document.png`, `docx_pageview.png` | Document reader | Only Find is visible; no Share/Edit/Text/More. |
| `home_card_more.png` | Home row overflow | Edit pages, Rename, Move, Share, Trash; no Lock. |
| `page_grid_docx.png`, `grid_more.png` | Page grid | Correct 1-page state, text-only More with Lock/Vault. |
| `page_editor.png`, `page_editor_p2.png` | Page editor | Large black field, bottom Rotate/Crop/Filter/Delete. |
| `page_bw_p2.png` | B/W result | Legible contents page; corrects universal B/W claim. |
| `page_delete_prompt.png` | Delete result | Immediate deletion, snackbar Undo. |
| `save_changes_dialog.png` | Save changes | Overwrite emphasised; no filename; no Discard. |
| `home_after_force_stop.png` | Home after force-stop | DocX remained 1 page / 1009 KB. |

**Test-document note:** the deletion test changed the fixture `DocX` from 2 pages / 1.5 MB to 1 page / 1009 KB. Page 2 (contents) was deleted and not restored. Do not run further destructive edits on it without a backup.

