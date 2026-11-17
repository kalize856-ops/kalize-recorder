# Kalize Recorder
Native Android screen recorder project.

## Build
Use Android Studio with JDK 17 and Android SDK 35, or run `gradle assembleDebug`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Current capabilities
- Jetpack Compose recorder home, settings, and dark/light appearance
- Android MediaProjection screen capture to MP4 with an ongoing foreground notification
- Draggable floating controls with record, stop, screenshot, app launch, and Pro Studio shortcut
- Resolution targets from 720p to 2160p and frame-rate targets up to 240 FPS, subject to device support
- Countdown before capture and optional microphone recording
- Pro Studio capture profiles and bitrate presets
- Screenshot capture saved to the Kalize Recorder Pictures folder
- Recording and screenshot library with playback, sharing, and refresh
- Basic video trimming with optional source-audio retention (turning it off exports a muted copy)
- GitHub Actions debug APK workflow

## Known limitations
This is not yet a full multi-track editor. Effects/filters, crop and rotate transcoding, clip merging, transitions, internal playback-audio capture, face-camera overlay, hardware capability probing, and production signing still need implementation. High resolution and frame-rate selections are targets, not guarantees; the Android device encoder and display determine actual support. Android requires explicit consent for each screen-capture session.