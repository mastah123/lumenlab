# Testing

## Automated (JUnit)

```bash
gradle testDebugUnitTest
```

Runs on the JVM, no phone needed. CI runs it before building the APKs.

| Test class | Covers |
|------------|--------|
| `LongExposurePlanTest` | Exposure path verdict (hardware, stacking, auto boost, none, error), frame plan and SNR gain, brightness check, exposure text |
| `StackProgressTest` | Time-left text and progress text |
| `StarExposureTest` | NPF and 500 rule, pixel size, clamping, exposure slider reverse mapping, focus sharpness measure |
| `GalleryModelTest` | Gallery folder categories, filters, size and date text |
| `ControlLimitsTest` | Exposure and ISO slider mapping, clamping, round trip |

Not covered by automated tests: Camera2 capture, stacking, panorama and double exposure image output, sensors, thermal readings, and all UI. Check those by hand.

## Manual checklist (on a phone)

- [ ] Install, camera permission prompt appears, camera opens on NORMAL
- [ ] NORMAL: shutter saves one photo; flash, timer and grid icons work
- [ ] ASTRO: exposure, ISO and focus sliders change the preview; burst saves a session folder; dark frame capture; auto stack
- [ ] ASTRO EXP panel: hint shows "Stars stay sharp up to about ..."; the Stars button moves the slider to that time (or the phone's limit)
- [ ] ASTRO FOCUS panel: Sharpness bar moves when you change focus on a bright light; Best appears near the peak; Reset works; the bar stops updating when the panel closes
- [ ] TRAILS: interval run starts, stops and saves one merged image
- [ ] PANO: turning the phone takes photos; second shutter press stitches; result saved in Pictures/Lumenlab/PANORAMA; Add photo button on phones without a rotation sensor
- [ ] DOUBLE: first and second photo (camera and gallery), ghost slider, Blend/Lighten/Silhouette, saved to Pictures/Lumenlab/DOUBLE
- [ ] Gallery: opens from MORE; thumbnails load; Results hides burst frames and Frames shows them; day headers read Today/Yesterday/date
- [ ] Gallery viewer: swipe between pictures; share, open in another app and delete work; delete asks first
- [ ] Gallery selection: long press selects, tapping toggles, share and delete work on several pictures; back or X clears selection
- [ ] Gallery on Android 9 (if available): pictures listed and shareable
- [ ] MORE: Stacking Studio, Camera Tester, Phone Check, tests shortcut
- [ ] Settings: night mode (red), temperature protection, storage options
- [ ] Long Exposure Test and RAW test run and the report can be shared
- [ ] Temperature chip shows below the settings icon
- [ ] All six mode tabs fit on one line and are readable
- [ ] STACK slider panel: title "Stack" on one line, originals button shows an icon and toggles
- [ ] Settings: sections in order Appearance, Stacking, Safety, Device profile, Tests and experiments, Diagnostics; originals choice (ask/keep/delete) is respected by the next burst
- [ ] Every top bar has a working back arrow; share icons work on Camera Tester and Long Exposure Test
- [ ] MORE tiles and "Tests and settings" button open the right screens
- [ ] Version in Settings matches `versionName` in `app/build.gradle.kts`

## Version check

`versionName`/`versionCode` live only in `app/build.gradle.kts`. The newest entry in `CHANGELOG.md` and the README status line must match.
