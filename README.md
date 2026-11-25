# Lumenlab

[![Build](https://github.com/YOUR_USER/lumenlab/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/YOUR_USER/lumenlab/actions/workflows/build.yml)

An Android astrophotography camera that uses the real capabilities of your phone's camera hardware.

> Understand your camera. Unlock its capabilities. Capture the night sky.

Lumenlab does not assume every phone is the same. It inspects the device through the Camera2 API and shows what the hardware can actually do before you shoot.

## Status

Version 0.27.0 covers **Stages 1 to 5**, plus camera modes (NORMAL, ASTRO, TRAILS, DOUBLE, PANO, SUPER, FOCUS and MORE), a **Stacking Studio**, automatic stacking while capturing, storage-saving options and temperature protection. Earlier stages: capability detection, an experimental **long exposure probe**, a live preview with manual controls, and multi-frame capture with a self-timer. A **RAW capture test** checks whether the phone really supports RAW.

| Stage | Scope | State |
|-------|-------|-------|
| 1 | Capability detection and compatibility report | Done |
| 1.5 | Long exposure probe and software exposure plan | Done |
| 2 | Camera preview and manual controls | Done |
| 3 | Astrophotography capture mode (single frame, self-timer) | Done |
| 4 | Multi-frame capture and RAW test | Done |
| 5 | Image stacking | Done (alignment is translation only) |
| 6 | Gallery | Done |
| 7 | Optimization and device compatibility | Planned |

## Features

- Star alignment that follows the sky's rotation as well as the shift (Settings: Correct sky rotation)
- Star exposure helper (NPF and 500 rule) and a focus assist sharpness meter
- Gallery of everything you saved, with filters, full-screen viewer, multi-select, share and delete
- Mode tabs like a phone camera: NORMAL (plain auto camera), ASTRO (manual camera with stacking), TRAILS (interval star trails), PANO (turn the phone to take a stitched panorama), SUPER (a burst of handheld photos merged into a finer picture), FOCUS (focus stacking for everything sharp), DOUBLE (two photos blended) and MORE (Stacking Studio, Camera Tester, tests)
- Flash, timer (3, 5, 10 s) and grid overlays from the icon bar
- Noise reduction for dark scenes: outlier rejection, dark frames, 2×2 night mode and star-preserving denoising
- Interval Star Trails: timed shots merged into one image, for sessions up to 4 hours without capturing continuously
- Bursts of up to 1000 frames
- Experimental live stacking with a test that tells you whether your phone can handle it
- Automatic stacking during a burst (after, or every 5, 10 or 20 frames) with a choice to keep or delete the originals
- Phone temperature meter and protection that pauses or stops a burst before the phone overheats
- Stacking Studio: align and average frames, or make star trails
- Multi-frame capture (up to 100 frames) saved to a session folder with minimal processing
- Capture button with self-timer; photos saved to Pictures/Lumenlab with their settings logged
- Live preview with manual exposure, ISO and focus, limited to what the phone really does
- Detects every camera on the device, including logical and physical lenses
- Reports exposure range, ISO range, manual and infinity focus, RAW/DNG, sensor size, output formats and hardware level
- Readiness summary in the MORE tab
- Settings → Experimental → Long Exposure Test: requests real manual exposures from 1/100 s up to 5 minutes, confirms frames get brighter, and shows whether hardware or software (stacking) long exposure is possible
- Verified device profile: the real exposure limit per camera, how it was proven, shareable as JSON
- Shareable plain-text reports
- Dark interface with a red night-vision mode
- Fully offline. The camera permission is requested only when you run the Long Exposure Test

## Requirements

- Android 9 (API 28) or newer
- To build: JDK 17 and Gradle 8.7+, or Android Studio

## Build

```bash
gradle assembleDebug      # debug APK
gradle assembleRelease    # release APK (signed if signing variables are set)
```

APKs are written to `app/build/outputs/apk/`.

## Continuous integration

`.github/workflows/build.yml` builds debug and release APKs on every push and pull request to `master`, and uploads them as the `lumenlab-apks` artifact.

### Release signing

Release APKs are signed automatically when these repository secrets exist (Settings → Secrets and variables → Actions):

| Secret | Value |
|--------|-------|
| `KEYSTORE_BASE64` | Base64 of your `.jks` keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Generate a keystore and get the Base64 value with:

```bash
bash scripts/create-keystore.sh
```

Without these secrets the release APK is built unsigned. Never commit the keystore.

## Project structure

```
app/src/main/java/com/lumenlab/app/
├── MainActivity.kt        Entry point and navigation
├── camera/                Camera2 detection, exposure probe, stacking, panorama, double exposure and reports
└── ui/                    Compose screens and theme
app/src/test/              Unit tests (JUnit)
docs/TESTING.md            Test guide and manual checklist
```

## Tests

```bash
gradle testDebugUnitTest
```

See [docs/TESTING.md](docs/TESTING.md) for what is covered and the manual checklist.

## Changelog

See [CHANGELOG.md](CHANGELOG.md). This project follows [Semantic Versioning](https://semver.org).
