# WhatsAppDateFixer

https://claude.ai/chat/18d42d0e-9ae4-4ae3-b598-2d15dbab9607

I have lots of photos in my WhatsApp images folders over lots of years, backed up and restored to new phones overtime. They all appear in the photos app as the day I last restored a backup. The filenames have the original date in them. Create me an Android app that goes through all the images and sets the modified and created time based on the filenames.5 AprCheck frontend design skillCheck frontend design skillThis is an Android app, so I'll create a complete Android project — specifically a single self-contained Kotlin file (plus necessary config files) that can be built into an APK. Let me build this properly.Created 8 files, ran a commandCreated 8 files, ran a commandNow let me create a comprehensive README explaining how to build and install it:Created 2 filesCreated 2 filesNow let me zip it up for download:Ran 2 commands, read a fileRan 2 commands, read a fileHere's your Android project. Here's what's inside and how to build it:
To build the APK:

Install Android Studio (free)
Unzip and open the WhatsAppDateFixer folder in Android Studio
Click Sync Now when prompted
Go to Build → Build APK(s)
Copy the resulting app-debug.apk to your phone and install it (you'll need to allow "Install unknown apps" in settings)

How the app works:

Scans all standard WhatsApp media folders (images, video, audio, GIFs) in both the old /sdcard/WhatsApp/ path and the newer /sdcard/Android/media/com.whatsapp/ path
Parses dates from filenames using multiple patterns: IMG-20230415-WA0001, WhatsApp Image 2022-06-14 at 12.30.45, IMG_20210805_134523, etc.
Sets both the filesystem modified time and the MediaStore date entry, so the Gallery sorts them correctly
Shows a live log with colour-coded results (green = fixed, amber = skipped, red = error) and a stats bar

On Android 11+, it requests the "All Files Access" (MANAGE_EXTERNAL_STORAGE) permission — this is required to modify files outside the app sandbox. The app has no internet permission and is entirely offline.
After running, you may need to clear your Gallery app's cache for the new dates to appear immediately.
