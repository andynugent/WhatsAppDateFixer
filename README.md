# 📅 WhatsApp Date Fixer

An Android app that scans your WhatsApp media folders and restores the original
photo/video dates based on the dates embedded in their filenames.

---

## What it does

WhatsApp filenames contain the original capture date, e.g.:
- `IMG-20230415-WA0001.jpg`  → 15 April 2023
- `VID-20191231-WA0002.mp4`  → 31 December 2019
- `WhatsApp Image 2022-06-14 at 12.30.45.jpeg` → 14 June 2022 12:30:45

After a backup/restore, those files get today's date in the Gallery.
This app reads each filename, parses the date, and sets both the **filesystem
modified time** and the **MediaStore date** so your Gallery sorts them correctly.

---

## Supported filename patterns

| Pattern | Example |
|---|---|
| `IMG-YYYYMMDD-WAxxxx` | `IMG-20230415-WA0001.jpg` |
| `VID-YYYYMMDD-WAxxxx` | `VID-20191231-WA0002.mp4` |
| `WhatsApp Image YYYY-MM-DD at HH.MM.SS` | `WhatsApp Image 2022-06-14 at 12.30.45.jpeg` |
| `IMG_YYYYMMDD_HHMMSS` | `IMG_20210805_134523.jpg` |
| Any 8-digit date in filename | `photo_20200101_final.jpg` |

---

## Scanned folders

The app automatically finds and scans:
- `/sdcard/WhatsApp/Media/WhatsApp Images/`
- `/sdcard/WhatsApp/Media/WhatsApp Video/`
- `/sdcard/WhatsApp/Media/WhatsApp Audio/`
- `/sdcard/WhatsApp/Media/WhatsApp Animated Gifs/`
- `/sdcard/WhatsApp/Media/WhatsApp Documents/`
- `/sdcard/Android/media/com.whatsapp/WhatsApp/Media/…` (newer WhatsApp)
- `/sdcard/DCIM/WhatsApp/`
- `/sdcard/Pictures/WhatsApp Images/`

---

## Building the APK

### Requirements
- [Android Studio](https://developer.android.com/studio) (Hedgehog 2023.1.1 or newer)
- Android SDK 34
- JDK 17 (bundled with Android Studio)

### Steps

1. **Open the project**
   ```
   File → Open → select the WhatsAppDateFixer folder
   ```

2. **Sync Gradle**
   Android Studio will prompt you — click **Sync Now**.

3. **Build the APK**
   ```
   Build → Build Bundle(s) / APK(s) → Build APK(s)
   ```
   The APK is output to:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

4. **Install on your phone**
   - Enable **Install unknown apps** for your file manager in Settings
   - Copy `app-debug.apk` to your phone and tap it to install, **or**
   - Connect via USB and run:
     ```bash
     adb install app/build/outputs/apk/debug/app-debug.apk
     ```

### Build via command line (no Android Studio UI needed)
```bash
cd WhatsAppDateFixer
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Using the app

1. **Grant permission** — tap the blue button. On Android 11+ you'll be taken
   to a system screen to grant "All Files Access" (MANAGE_EXTERNAL_STORAGE).
   This is required to modify files outside the app's sandbox.

2. **Tap ▶ Start Fixing** — the app scans all WhatsApp folders and shows a
   live log with colour-coded results:
   - 🟢 Green = date fixed
   - 🟡 Amber = skipped (already correct, or no date in filename)
   - 🔴 Red = error

3. When done, **open your Gallery app** and the photos should now be sorted by
   their original dates.

> **Tip**: After running, you may need to clear the Gallery app's cache
> (Settings → Apps → Gallery → Storage → Clear Cache) for the new dates to
> appear immediately.

---

## Privacy

The app runs entirely **on-device**. No data is uploaded, no internet
permission is requested, no analytics.

---

## Troubleshooting

| Issue | Fix |
|---|---|
| "No WhatsApp media folders found" | Check your WhatsApp path in a file manager and ensure the paths above exist |
| Dates not updating in Gallery | Clear Gallery cache, or restart the phone |
| Files show errors | The file may be read-only (e.g. in a protected folder); try moving them first |
| Android 11+ permission denied | Go to Settings → Apps → WhatsApp Date Fixer → Permissions and enable "All Files Access" |
