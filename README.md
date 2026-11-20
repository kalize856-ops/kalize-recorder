# Kalize Recorder

Current development version: **1.0.7** (version code 8).

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

GitHub Actions builds debug and release candidates on pushes to `main`.

## Recording

- Jetpack Compose recorder interface with Recorder, Edit, Library and Settings pages.
- Dark and light themes.
- Android MediaProjection screen recording to MP4 with an ongoing foreground notification.
- Video-only sessions prefer a dedicated surface-input H.264 MediaCodec pipeline, with hardware encoder selection, configurable bitrate/frame-rate hints, output draining and MP4 muxing.
- MediaRecorder fallback if the dedicated codec cannot start.
- Microphone capture remains on the MediaRecorder path.
- Optional Android 10+ internal playback capture uses AudioPlaybackCapture, AAC encoding and MP4 muxing on eligible apps; Android playback-capture policy can block particular apps.
- Resolution targets from 240p through 2160p (4K), and frame-rate targets up to 240 FPS, subject to device display and encoder limits.
- Countdown, optional microphone capture and Android touch indicators.
- Draggable floating controls with record, stop, screenshot, pause/resume (standard MediaRecorder mode), editor, Pro Studio, annotation and app shortcuts.
- The floating overlay uses a secure-window flag to reduce its appearance in screenshots and recordings; Android/system behaviour can vary by device.
- No watermark is added to exported recordings.

## Editing and media tools

- Video trim, split, merge (compatible tracks), rotation and playback-speed export.
- Compression presets from 240p through 2160p (4K), preserving aspect ratio and saving a separate copy.
- Audio extraction to M4A when the source audio codec is compatible.
- Advanced Media3 filters, crop and resize.
- Multi-track timeline with reorderable video clips, multiple audio tracks, per-track gain, original-audio level, fades, overlapping video crossfades, dip-to-black, dissolve, wipe, slide and other transitions.
- FFmpeg-backed timeline export to Movies/Kalize Recorder/Edits.

## Device Lab

- Device and memory summary.
- H.264 encoder inventory.
- Opt-in live download/upload traffic monitor while Kalize is open; samples Android device traffic counters every second without generating test traffic. Other apps’ traffic is included.
- Short local CPU workload benchmark.
- Game Session option to keep the display awake and hide system bars while the Device Lab page is open.

## Current limitations and release checks

- The 120/240 FPS values are configuration targets, not guarantees of sustained frame delivery. Physical-device testing is required.
- Internal playback capture is limited to eligible apps/audio usages and can be blocked by app policy. Microphone and internal playback are separate modes; simultaneous mixing is not included.
- Pause/resume on the dedicated MediaCodec path detaches and reattaches the virtual-display surface; verify timestamp continuity on physical devices.
- Game Session does not overclock the CPU, close other apps, or change system performance governors; Android does not grant ordinary apps those controls.
- Live network rates are device-wide traffic estimates, not an isolated internet speed test; Android may report counters as unavailable on some devices.
- Clip merging currently supports compatible tracks without re-encoding; incompatible clips may fail.
- A production release still needs protected signing secrets, release-build validation, dependency/license review and physical-device testing.
- Screen recording requires the user's explicit Android screen-capture consent for each session.
- Touch indicators use Android's system-wide SHOW_TOUCHES setting and require user authorization before the app changes it.

## Licensing note

The FFmpegKit-compatible Android dependency is LGPL-licensed. Review the bundled notices and dependency license before distributing builds.
