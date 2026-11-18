# LANE_REPORT_hindi — judged Hindi glossary applied

Branch `lane/hindi`. Source of truth: `next/docs/review/JUDGE_HINDI.md` (binding rules + final glossary).
No `values/` (English) file touched; no key added or removed; every placeholder, escape and plurals
quantity kept exactly (checked against `HEAD` per element).

## Result

- All 18 existing `app/src/main/res/values-hi/*.xml` string files reviewed string by string against the
  final glossary and the binding rules (polite imperatives, आप forms, Latin digits, loanword policy).
- **863 named strings rewritten** (897 entries incl. plurals items) out of 1443 keys. `strings_fixui.xml`
  was already correct (`भाग`, not a glossary term) and is left untouched, marker included.
- Reviewed strings have their inline `<!-- review -->` marker removed; the one untouched string keeps it.
- Navigation now exactly: कागज़ात · काम · स्कैन · तिजोरी · मैं; Create **बनाएँ**; Settings **सेटिंग**;
  Scan action **स्कैन करें**; Vault title **तिजोरी**, hidden vault **छिपी तिजोरी**.

### Strings rewritten per file

| file | names rewritten | entries (with plurals items) |
|---|---|---|
| strings.xml | 331 | 345 |
| strings_l1.xml | 143 | 144 |
| strings_tools.xml | 85 | 93 |
| strings_vault.xml | 82 | 84 |
| strings_draft.xml | 45 | 48 |
| strings_stamp.xml | 39 | 39 |
| strings_backup.xml | 38 | 40 |
| strings_protect.xml | 25 | 26 |
| strings_book.xml | 17 | 18 |
| strings_qr.xml | 13 | 13 |
| strings_ocr.xml | 7 | 7 |
| strings_fixflow.xml | 6 | 6 |
| strings_billing.xml | 5 | 5 |
| strings_pdfa.xml | 4 | 4 |
| strings_crash.xml | 3 | 3 |
| strings_data.xml | 3 | 5 |
| strings_fixidcrop.xml | 3 | 3 |
| strings_fixinsertcrop.xml | 3 | 3 |
| strings_l7.xml | 3 | 4 |
| strings_pro2.xml | 3 | 3 |
| strings_fixtext.xml | 2 | 2 |
| strings_fixbilling.xml | 1 | 1 |
| strings_fixresult.xml | 1 | 1 |
| strings_tools2.xml | 1 | 1 |
| strings_fixui.xml | 0 (already correct) | 0 |

## Glossary terms applied

- Navigation/voice: कागज़ात (Library), काम (Tools, All tools, 18 Pro tasks, batch), स्कैन / स्कैन करें, तिजोरी,
  मैं, बनाएँ (Create), सेटिंग.
- Actions (polite imperative): सहेजें, मिलाएँ, बाँटें, पन्ने निकालें, पन्नों का क्रम बदलें, पन्ने जोड़ें,
  फ़ाइल छोटी करें / PDF का आकार घटाएँ, आकार बदलें, हस्ताक्षर करें, लिखें, हाथ से लकीरें बनाएँ,
  रंग से उभारें, हल्की छाप लगाएँ, पन्नों पर नंबर डालें, मोहर लगाएँ, निजी जानकारी छिपाएँ,
  PDF सुरक्षित करें, PDF का ताला खोलें, ताला लगाएँ, नाम बदलें, ले जाएँ, दूसरी प्रति बनाएँ,
  वापस लाएँ, हटाएँ, भेजें, नकल करें, काटें, घुमाएँ, खोलें, जारी रखें, रद्द करें, फिर कोशिश करें.
- Nouns/states: पन्ना/पन्ने, फ़ोन की PDF, झटपट काम, हाल के, नए पहले, पहचान पत्र, QR कोड, पासपोर्ट,
  फ़ाइल, फ़ोटो, नंबर, पासवर्ड, PIN, गुप्त नंबर, तिजोरी वापस खोलने का कोड, बचाकर रखी प्रति,
  उँगली का निशान, हटाई गई फ़ाइलें, झलक, जाँच, अभी सहेजा नहीं, हो गया, तैयार, काम चल रहा है…,
  सहेजा गया, सहेजा नहीं गया, शब्द खोज सकते हैं, ताला लगा है, मुफ़्त, Pro, एक बार,
  बार-बार भुगतान नहीं, Pro की सुविधाएँ, Pro चालू करें, पहले की खरीद फिर चालू करें.
- Meaning safeguards: compression reduces **फ़ाइल का आकार** and warns that the picture gets less clear;
  redaction says the hidden text/pixels are removed for good; encryption is described as “कोड के बिना
  फ़ाइल पढ़ी नहीं जा सकती”, the hidden vault as “सिर्फ़ छिपी है; मिलने पर पढ़ी जा सकती है”; copying says
  “नकल कर ली; अब दूसरी जगह चिपका सकते हैं” (not a saved file); screenshots use
  “फ़ोन पर दिख रहे पन्ने की तस्वीर न लेने दें”.
- Latin digits throughout (12 पन्ने, 2.5 MB, 6 या ज़्यादा अंक); KB/MB/px/DPI unchanged.
- Kept as judged exceptions/names: सेटिंग, फ़ाइल, फ़ोटो, फ़ोन, ऐप, नंबर, पासवर्ड, PIN, कोड, इंटरनेट,
  स्कैन, फ़ोल्डर (with the “कागज़ात साथ रखने की जगह” role), फ़ॉर्म, and ScanWhirl, PDF, PDF/A, Word,
  Excel, PowerPoint, WhatsApp, QR, ZIP, Pro, KB, MB, px, DPI, A4.

## Judged terms not applied / notes

- None deliberately skipped. Two edits were wording-quality fixes, not glossary exceptions:
  `scan` (“Scan a document”) is now “स्कैन करें” so the judged action word is exact;
  `tools_batch_pro_title` uses “Pro का है” to agree with the singular काम.
- Non-plural count strings inherited from English (`%1$d pages` in `home_page_count`, `doc_row_desc`) still
  contain a number + पन्ने; their placeholder contract could not be changed to an Android `<plurals>`
  without adding a key. Everything that already has `<plurals>` uses पन्ना/पन्ने agreement.
- No string was shortened for fit: `TextFitTest` runs in the default (English) locale, there is no Hindi
  fit harness, and the judged wordings are longer by design.

## Tests

- New `app/src/test/java/com/scanwhirl/app/HindiGlossaryTermsTest.kt`:
  - rejects 50+ judged-rejected transliterations as UI words in any `values-hi` file
    (कंप्रेस, क्वालिटी, कस्टम, साइज़, टूल, वॉल्ट, इम्पोर्ट/एक्सपोर्ट, डिवाइस, लाइब्रेरी, फ़िल्टर, ट्रैश,
    मर्ज, क्रॉप, पेज, पाठ/टेक्स्ट, अनडू/रीडू, डिफ़ॉल्ट, सिस्टम, शेयर, कॉपी, सेव, फ्री, सर्च, क्विक,
    प्रीव्यू, टैप, पिन, आईडी, डुप्लिकेट, बैच, गैलरी, ऑफ़लाइन, हाइलाइट, वॉटरमार्क, रिडैक्ट, बैकअप,
    रिकवरी, पासकोड, फ़िंगरप्रिंट, एन्क्रिप्ट, डाउनलोड/अपलोड, स्क्रीन, प्रो …);
    allowed exception list: **मर्ज़ी** (ordinary Hindi word that contains मर्ज) — the test names it.
  - pins the judged navigation words (कागज़ात, काम, स्कैन, तिजोरी, मैं, बनाएँ, स्कैन करें, सेटिंग).
- `FixCopyTest` (older wording test) asserted every reviewed Hindi string still carried the
  “awaiting native review” marker; it now asserts the judged, marker-free state (same wordings, no long
  dashes). `StringsParityTest` doc comment updated for the same reason.
- Full gate: `GATE PASS hindi tests=1770 failures=0 skipped=0 lint=[skipped]`
  (cloud run https://github.com/cybium/ScanWhirl/actions/runs/44292602879).

## Before / after (30 examples)

| # | string | before | after |
|---|---|---|---|
| 1 | shell_nav_library | लाइब्रेरी | कागज़ात |
| 2 | shell_nav_tools | टूल्स | काम |
| 3 | shell_nav_vault | वॉल्ट | तिजोरी |
| 4 | tab_device_pdfs | डिवाइस PDF | फ़ोन की PDF |
| 5 | tool_compress | कंप्रेस | फ़ाइल छोटी करें |
| 6 | compress_level_low | सबसे अच्छी क्वालिटी | सबसे साफ़ |
| 7 | compress_target_label | कस्टम आकार (ज़रूरी नहीं) | अपनी ज़रूरत का आकार (चाहें तो) |
| 8 | crop_size_free | कस्टम | अपनी मर्ज़ी से |
| 9 | compress_level_title | कंप्रेस का स्तर | कितना छोटा करें |
| 10 | merge_action | मर्ज करें | मिलाएँ |
| 11 | tool_split | विभाजित करें | बाँटें |
| 12 | action_delete_page | पेज हटाएँ | पन्ना हटाएँ |
| 13 | tool_extract | पेज निकालें | पन्ने निकालें |
| 14 | tool_organize_pages | पेज व्यवस्थित करें | पन्नों का क्रम बदलें |
| 15 | tool_to_text | टेक्स्ट में | लिखे हुए शब्द निकालें |
| 16 | tool_read_text | टेक्स्ट पढ़ें | लिखा हुआ पढ़ें |
| 17 | tool_add_text | टेक्स्ट जोड़ें | लिखें |
| 18 | tool_draw | चित्र बनाएँ | हाथ से लकीरें बनाएँ |
| 19 | tool_highlight | हाइलाइट | रंग से उभारें |
| 20 | tool_watermark | वॉटरमार्क | हल्की छाप लगाएँ |
| 21 | tool_page_numbers | पेज नंबर | पन्नों पर नंबर डालें |
| 22 | tool_redact | नामेट करें | निजी जानकारी छिपाएँ |
| 23 | tool_unlock | PDF अनलॉक करें | PDF का ताला खोलें |
| 24 | tool_lock | लॉक | ताला लगाएँ |
| 25 | tool_duplicate | प्रतिलिपि | दूसरी प्रति बनाएँ |
| 26 | home_quick_tools | त्वरित टूल्स | झटपट काम |
| 27 | home_all_tools | सभी टूल्स › | सभी काम › |
| 28 | trash_move | ट्रैश में डालें | हटाएँ |
| 29 | badge_searchable | सर्च-योग्य | शब्द खोज सकते हैं |
| 30 | pro_see_all | सभी 18 Pro टूल्स देखें › | Pro के सभी 18 काम देखें › |

Further representative rewrites: pro_unlock (Pro अनलॉक करें → Pro चालू करें), pro_restore
(खरीदारी बहाल करें → पहले की खरीद फिर चालू करें), theme_system (सिस्टम के अनुसार → फ़ोन के अनुसार),
font_default (डिफ़ॉल्ट → पहले से चुना हुआ), preview (प्रीव्यू → झलक), ocr_copied
(क्लिपबोर्ड में कॉपी किया गया → नकल कर ली; अब दूसरी जगह चिपका सकते हैं), qr_share (शेयर करें → भेजें),
vault_title (छिपा वॉल्ट → छिपी तिजोरी), vault_setup_note (पासकोड/वॉल्ट → गुप्त नंबर/तिजोरी),
vault_locked_body (फ़िंगरप्रिंट या पासकोड → उँगली का निशान या गुप्त नंबर), cat_label_ids (आईडी → पहचान पत्र),
device_empty (डिवाइस → फ़ोन), import_title (इम्पोर्ट करें → लाएँ), export (एक्सपोर्ट → सहेजें),
filter_action (फ़िल्टर → रंग-रूप बदलें), ocr_title (टेक्स्ट → लिखा हुआ), tools_setup_quality
(गुणवत्ता → तस्वीर कितनी साफ़ हो), vault_backup_button (बैकअप फ़ाइल → बचाकर रखी प्रति),
vault_recovery_button (रिकवरी कोड → तिजोरी वापस खोलने का कोड), me_backup
(बैकअप और रीस्टोर → प्रति सहेजना और वापस लाना), stamp_tool_draw (आरेखण → हाथ से लकीरें बनाएँ).

## Git

- Commits: `<pending>` (this report is committed with the work).
- Last gate line: `GATE PASS hindi tests=1770 failures=0 skipped=0 lint=[skipped]`
