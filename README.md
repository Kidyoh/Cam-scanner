# Scanly

A free, open-source document scanner for Android, written in Kotlin with Jetpack Compose.
Everything you would pay for in other scanner apps is included — no ads, no watermarks,
no account, no page limits, and nothing leaves your phone unless you share it.

## Features

**Scanning**
- Live edge detection with auto-capture (Google ML Kit document scanner, on-device)
- Automatic perspective correction and cropping, with manual corner adjustment
- Shadow, stain and finger removal
- Multi-page scanning (up to 100 pages per scan, configurable)
- **ID card mode**: scan front and back onto one A4 page at true size, ready to print
- Import photos from the gallery, import existing PDFs, or "Share to Scanly" from any app

**Saving: your files, where you can see them**
- Every PDF is auto-saved to **Download/Scanly** (visible in the Files app) and kept up to date as you edit
- Save page images to the **Gallery** (Pictures/Scanly), all at once or page by page
- Optional: auto-save every scanned page to the Gallery
- Save to any folder, SD card or cloud drive through the system file picker
- **Password-protected** PDFs (AES-128)

**Image detail and filters**
- Quality presets up to **Maximum detail** (full camera resolution, JPEG 95%)
- Filters with live previews: Magic colour (white-balanced paper), Enhance, Greyscale,
  Black & white (adaptive threshold that ignores shadows), Lighten
- Filters are non-destructive: the original is always kept, so you can switch or undo anytime
- Apply a filter to one page or the whole document
- Full-screen viewer loads pages at full resolution, so pinch-zoom shows real detail

**Smart PDFs**
- Every scan gets an invisible OCR text layer, so the PDF is **searchable** and text can
  be selected and copied in any PDF reader
- Documents are auto-named from the first line of text on the page
- Page size: fit-to-image, A4 or US Letter

**Editing**
- **Sign documents**: draw a signature (black, blue or red), save it for reuse, then drag and pinch to place it
- Add pages to an existing document
- Rotate, reorder or remove individual pages

**Library**
- Full-text search across titles *and* the text inside every scan, with highlighted matches
- Sort by date, name, size or page count
- Favourites, rename, duplicate, delete
- Multi-select to **merge** documents or bulk-delete
- Document details: pages, size, resolution, word count, filters, where it's saved

**Sharing and more**
- Share as PDF, as page images, password-protected or **compressed** for email
- **Print** to any printer
- Extract text (OCR), then copy, share or save as .txt
- QR / barcode scanner (links, Wi-Fi passwords, contacts, …)
- Material You dynamic colour and dark mode

## Building

Requirements: JDK 17+, Android SDK 35.

```bash
./gradlew testDebugUnitTest    # 25 Robolectric tests: saving, filters, PDF/OCR layer, encryption, migration
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew assembleRelease      # app/build/outputs/apk/release/ (minified, signed with the debug key)
```

Builds produce one APK per CPU architecture, plus a universal one:

| APK | For | Size |
|---|---|---|
| `arm64-v8a` | almost every phone from the last ~8 years | ~21 MB |
| `armeabi-v7a` | older or budget 32-bit phones | ~17 MB |
| `x86_64` / `x86` | emulators, Chromebooks | ~21 MB |
| `universal` | any device (if unsure) | ~50 MB |

Every push also builds these on GitHub Actions — download them from the run's
**Artifacts** section.

The scanner and QR reader use Google Play services, which download their ML models on
first use, so the device needs Play services and a one-time internet connection.
Text recognition is bundled in the app and works fully offline.

## Project layout

```
app/src/main/java/dev/kidyoh/scanly/
├── data/       Room database, repository, settings
├── pdf/        PDF building (PDFBox), OCR (ML Kit), image processing, PDF import
├── ui/         Compose screens: home (library), document (viewer/editor), settings
└── util/       Scanner launcher, sharing helpers
```

## Tech

Kotlin · Jetpack Compose · Material 3 · Room · Coroutines/Flow · ML Kit (document scanner,
text recognition, code scanner) · PDFBox-Android · Coil
