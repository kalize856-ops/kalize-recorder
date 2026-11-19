# Kalize Recorder

Current development version: **1.0.2** (version code 3).

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
- Recording resolution targets from 240p to 2160p (4K) and frame-rate targets up to 240 FPS, subject to device encoder and display support
- Countdown before capture and optional microphone recording
- Optional Android touch indicators with an explicit system-settings authorization step
- Pro Studio capture profiles and video bitrate presets
- Recording and screenshot library with playback, sharing, and refresh
- Video compression presets from 240p through 2160p (4K), with bitrate targets and aspect-ratio preservation; saves a separate copy
- Split a selected video into two equal-duration clips without changing the original
- Basic video trimming with optional source-audio retention
- Audio extraction to a separate M4A file when the source audio codec is compatible with the MP4 container
- Clip merging for compatible media tracks
- Trim export controls for rotation and playback speed
- No watermark is added to exported recordings

## Limitations

- This is not yet a full multi-track editor. Multi-layer timeline editing, transitions, advanced audio mixing, and a visual clip-reordering timeline are not implemented. Basic filters, crop, resize, trim, split, rotation, playback speed, mute, and audio extraction are available.
- Clip merging currently supports compatible tracks without re-encoding; incompatible clips may fail to merge.
- Microphone recording captures microphone input; internal game/system audio capture is not included.
- Resolution and FPS settings are requests, not guarantees. Android hardware and encoder capabilities determine the actual output.
- Floating-window security flags protect the app's overlay window; Android/system behavior can vary by device and OS version.
- A production release still needs a protected signing configuration and release-build validation.
- Screen recording requires the user's explicit Android screen-capture consent for each session.
- Touch indicators use Android's system-wide SHOW_TOUCHES setting and require the user to authorize modification in Android Settings. The app does not change it without user action.


## Multi-track studio timeline (1.0.2)

- Separate main video sequence with clip reordering and removal.
- Multiple independent audio sequences layered over the video, with per-track gain controls, original-video audio level control, and automatic audio fade-in/fade-out.
- True overlapping video crossfades (0.5s, 1s, or 2s), dip-to-black transitions, or hard cuts; matching audio crossfades are rendered with FFmpeg.
- Multi-track export uses a local FFmpeg rendering pipeline and saves a new MP4 under Movies/Kalize Recorder/Edits.
- Crossfade exports normalize clips to 720p at 30 FPS for consistent transition rendering. Hardware H.264 encoding is attempted first, with an MPEG-4 fallback when the device encoder is unavailable.
- The FFmpegKit-compatible Android dependency is LGPL-licensed; review the bundled notices and dependency license before distributing builds.
