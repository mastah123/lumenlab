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
| `StarAlignTest` | Star matching with shift and turn, large turn search, resampling, edge margins |
| `NightMergeTest` | Night burst plan (time, ISO, gain), noise averaging, moved parts ignored, brightening, reference and blur choice |
| `HdrFusionTest` | Bracket exposure plan, exposure fusion keeps dark and bright detail, single photo unchanged, weights, layer sizes, brightness normalising |
| `FocusStackTest` | Focus steps, merge takes the sharp parts, flat areas, shifting, detail measure |
| `SuperResTest` | Sub-pixel photo alignment, rejecting unrelated photos, merge beats single upscale, sharpness score |
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
- [ ] Sky rotation: shoot a 20 to 30 minute session on a tripod, stack it with Correct sky rotation on and off; with it on, stars near the corners stay round and sharp, with it off they smear into short arcs
- [ ] Settings, Stacking: Correct sky rotation switch is on by default and is respected by stacking while capturing, live stacking, the final stack and the Stacking Studio
- [ ] TRAILS: interval run starts, stops and saves one merged image
- [ ] PANO: turning the phone takes photos; second shutter press stitches; result saved in Pictures/Lumenlab/PANORAMA; Add photo button on phones without a rotation sensor
- [ ] DOUBLE: first and second photo (camera and gallery), ghost slider, Blend/Lighten/Silhouette, saved to Pictures/Lumenlab/DOUBLE
- [ ] Gallery: opens from MORE; thumbnails load; Results hides burst frames and Frames shows them; day headers read Today/Yesterday/date
- [ ] Gallery viewer: swipe between pictures; share, open in another app and delete work; delete asks first
- [ ] Gallery selection: long press selects, tapping toggles, share and delete work on several pictures; back or X clears selection
- [ ] Gallery on Android 9 (if available): pictures listed and shareable
- [ ] SUPER: preview opens; choose 6, 8 or 10 photos; shutter takes the photos with a progress bar; merge shows progress; result saves to Pictures/Lumenlab/SUPERRES and appears in the Gallery (Results and Super res); compare against one normal photo of the same scene at 100% zoom
- [ ] SUPER on a tripod or a table: the message about too few usable photos or no gain is acceptable; handheld gives the best result
- [ ] FOCUS: on a phone with manual focus, shutter moves focus from near to far while taking photos; result saves to Pictures/Lumenlab/FOCUS; check that near and far objects are both sharp; Range chips change the distances shown; on a phone without manual focus the mode says so
- [ ] HDR: on a support, shutter takes 3 (or 5) photos at different brightness with a progress bar; blend shows progress; result saves to Pictures/Lumenlab/HDR and appears in the Gallery (Results and HDR); compare against the normal photo of a window scene or backlit subject; Strength chips change the spread; on a phone without manual exposure the mode says so
- [ ] NIGHT: in a dim room, handheld, shutter takes 8/12/16 photos with a progress bar and a second press stops early after 3; merge shows progress; result saves to Pictures/Lumenlab/NIGHT and appears in the Gallery (Results and Night); compare with one normal night photo for noise and sharpness; Shutter chips change the exposure; a person walking through the scene must not ghost; phone without manual exposure says so
- [ ] Mode tabs scroll sideways and the selected tab stays in view after switching mode
- [ ] MORE: Stacking Studio, Camera Tester, Phone Check, tests shortcut
- [ ] Settings: night mode (red), temperature protection, storage options
- [ ] Long Exposure Test and RAW test run and the report can be shared
- [ ] Temperature chip shows below the settings icon
- [ ] All mode tabs are reachable by scrolling and readable
- [ ] STACK slider panel: title "Stack" on one line, originals button shows an icon and toggles
- [ ] Settings: sections in order Appearance, Stacking, Safety, Device profile, Tests and experiments, Diagnostics; originals choice (ask/keep/delete) is respected by the next burst
- [ ] Every top bar has a working back arrow; share icons work on Camera Tester and Long Exposure Test
- [ ] MORE tiles and "Tests and settings" button open the right screens
- [ ] Version in Settings matches `versionName` in `app/build.gradle.kts`

## Version check

`versionName`/`versionCode` live only in `app/build.gradle.kts`. The newest entry in `CHANGELOG.md` and the README status line must match.
