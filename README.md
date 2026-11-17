# Kalize Recorder
Native Android screen recorder project.

## Build
Use Android Studio with JDK 17 and Android SDK 35, or run `gradle assembleDebug`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Included in this foundation
- Jetpack Compose recorder home and settings
- Android MediaProjection screen capture to MP4
- Foreground recording notification with Stop action
- Draggable floating overlay control (requires Display over other apps permission)
- Dark/light appearance toggle
- GitHub Actions APK build workflow

This is the initial application foundation. A full multi-track editor, audio controls, 4K/high-FPS profiles, onboarding, and production signing still require implementation and device testing. Android requires explicit consent before each screen-capture session.