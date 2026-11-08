# ScanWhirl

Fast, private document scanner for Android. Made for students who need small, clean PDFs and photos for forms and sharing.

## Features
- Scan with Google's on-device scanner (auto edge detection, batch up to 30 pages)
- Searchable PDFs — text is recognized on the phone and hidden inside the PDF so any PDF app can search it
- Text search — names and text inside your scans, for everyone, ranked by relevance
- Categories and Quick Sort — choose a category at save, filter by chip rail, bulk move, Quick Sort for unsorted pile
- Merge several scans into a single PDF
- Compress scans and PDFs with clear size estimates
- Resize images: exact pixels or auto aspect ratio
- Rotate, reorder, and delete pages inside a scan
- Read text editor — extract text, clean it up (join lines / fix spaces / remove gaps), edit, copy, share, save
- Import photos or a PDF from your device
- Hindi and English
- Everything on-device: no accounts, no ads, no servers

**How it works:** tap Scan, scan pages with Google's on-device scanner, review and save, text is read in the background, search finds it.

## Download
- [Google Play](https://play.google.com/store/apps/details?id=com.scanwhirl.app)

## Privacy
- Policy: [cybium.github.io/scanwhirl-privacy](https://cybium.github.io/scanwhirl-privacy/)

## Build and test
Run from the repository root:

```bash
export ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk"
./gradlew :app:compileDebugKotlin -PtestBuild --no-daemon   # after every edit batch
./gradlew test -PtestBuild --no-daemon                       # before every commit
./gradlew assembleRelease -PtestBuild --no-daemon            # test APK, package com.scanwhirl.app.test
```

Rules: one task = one commit `next T<N>: <summary>`; the suite must be green after every task. Never `git push`. Never run `./gradlew assembleRelease` without `-PtestBuild`. Never touch the Play package id, keystore files, `versionCode` or `versionName`. Every new or changed user-visible string gets an English entry in `app/src/main/res/values/strings.xml` and a Hindi entry in `app/src/main/res/values-hi/strings.xml` in the same commit (Hindi entries carry `<!-- review -->`). No network code, no analytics, no new dependency, no new permission. No `@Ignore` a test.

## Support
- cybiumsupport@gmail.com

Indie developer making simple, useful apps for everyday tasks. First release: ScanWhirl.
