# Scanly

A free, open-source document scanner for Android, written in Kotlin with Jetpack Compose.
Everything you would pay for in other scanner apps is included — no ads, no watermarks,
no account, no page limits, and nothing leaves your phone unless you share it.

## Features

**Scanning**
- Live edge detection with auto-capture (Google ML Kit document scanner, on-device)
- Automatic perspective correction and cropping, with manual corner adjustment
- Filters: colour, greyscale, black & white; shadow, stain and finger removal
- Multi-page scanning (up to 100 pages per scan, configurable)
- Import from the gallery, import existing PDFs, or "Share to Scanly" from any app

**Smart PDFs**
- Every scan becomes a PDF with an invisible OCR text layer, so it's **searchable**
  and text can be selected and copied in any PDF reader
- Documents are auto-named from the first line of text on the page
- Page size: fit-to-image, A4 or US Letter; three quality/size presets
- **Password protection** (AES-128) when saving or sharing

**Library**
- Full-text search across titles *and* the text inside every scan, with highlighted matches
- Favourites, rename, duplicate, delete
- Multi-select to **merge** documents or bulk-delete

**Editing**
- Add pages to an existing document
- Rotate, reorder or remove individual pages
- Full-screen viewer with swipe between pages and pinch/double-tap zoom

**Extras**
- Extract text (OCR) → copy or share
- QR / barcode scanner (links, Wi-Fi passwords, contacts, …)
- Save to any folder or cloud drive via the system file picker
- Share as PDF or as page images
- Material You dynamic colour and dark mode
- No runtime permissions required

## Building

Requirements: JDK 17+, Android SDK 35.

```bash
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
