# Samsung Screen Recorder — Build 1 (Phases 1–3)

Phone-only Android screen recorder project. No Android Studio is required for building this version.

## Included

### Phase 1 — Core stability
- Android 10–14+ MediaProjection flow
- MediaProjection foreground-service type
- Safe start/stop and projection shutdown handling
- H.264 fallback when HEVC is unavailable
- 60 FPS fallback to 30 FPS when the device encoder rejects 60 FPS
- Gallery-visible MediaStore output under `DCIM/ScreenRecordings`
- Temporary files are cleaned after successful/failed recording

### Phase 2 — Audio
- Mute
- Microphone only
- Internal/media playback only using Android 10+ AudioPlaybackCapture
- Internal/media + microphone mixing
- AAC audio encoding and final MP4 muxing

### Phase 3 — Quality
- 720p / 1080p / 1440p choices (never upscale above the device display size)
- 30 / 60 FPS choices
- Hardware-aware HEVC request with H.264 fallback
- Bitrate scaled to the actual capture resolution

## Phone-only GitHub build

1. Create a GitHub repository.
2. Upload the contents of this project.
3. Push to `main` or open **Actions → Build Screen Recorder APK → Run workflow**.
4. Open the completed workflow run.
5. Download the `SamsungScreenRecorder-debug` artifact.
6. Extract the artifact and install `app-debug.apk` on the phone.

## First test

On the phone:

1. Select **1080p**.
2. Select **30 FPS** for the first test.
3. Select **Mute**.
4. Start recording and wait 10 seconds.
5. Stop recording.
6. Check `DCIM/ScreenRecordings`.

Then test **Media Only**, **Mic Only**, and **Media & Mic** separately.

Note: Android apps can only capture playback audio from apps that allow playback capture. Protected/blocked audio may not be captured.
