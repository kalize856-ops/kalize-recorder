# Kalize Recorder

Native Android screen recorder and video utility app.

## Build

- JDK 17
- Android SDK Platform 35
- Gradle 8.9

Run:

```bash
gradle --no-daemon assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds the debug APK on pushes to `main` and uploads it as the `Kalize-Recorder-debug` artifact.

## Current features

- Jetpack Compose recorder interface with organized Recorder, Library, and Settings pages
- Dark and light appearance
- Android MediaProjection screen recording to MP4 with an ongoing foreground notification
- Draggable floating controls with record, stop, screenshot, app launch, and Pro Studio shortcuts
- Floating overlay uses Android's secure-window flag to prevent its own window from appearing in screenshots and screen captures
- Resolution targets from 720p to 2160p and frame-rate targets up to 240 FPS, subject to device encoder and display support
- Countdown before capture and optional microphone recording
- Optional Android touch indicators with an explicit system-settings authorization step
- Pro Studio capture profiles and video bitrate presets
- Recording and screenshot library with playback, sharing, and refresh
- Video compression with Small (480p), Balanced (720p), and High (1080p) presets; saves a separate compressed copy
- Basic video trimming with optional source-audio retention
- Clip merging for compatible media tracks
- Trim export controls for rotation and playback speed
- No watermark is added to exported recordings

## Limitations

- This is not yet a full multi-track editor. Timeline editing, transitions, filters/effects, crop controls, and advanced audio mixing are not implemented.
- Clip merging currently supports compatible tracks without re-encoding; incompatible clips may fail to merge.
- Microphone recording captures microphone input; internal game/system audio capture is not included.
- Resolution and FPS settings are requests, not guarantees. Android hardware and encoder capabilities determine the actual output.
- Floating-window security flags protect the app's overlay window; Android/system behavior can vary by device and OS version.
- A production release still needs a protected signing configuration and release-build validation.
- Screen recording requires the user's explicit Android screen-capture consent for each session.
- Touch indicators use Android's system-wide SHOW_TOUCHES setting and require the user to authorize modification in Android Settings. The app does not change it without user action.
