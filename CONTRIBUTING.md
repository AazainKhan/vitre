# Contributing to VITRE

Thanks for helping. VITRE is a Kotlin + Jetpack Compose for TV app (single `:app` module, package `dev.glasslauncher`). Bug reports, device compatibility reports and pull requests are all welcome.

## Build

- JDK 17 or 21 (AGP rejects newer JDKs) and the Android SDK.
- `./gradlew :app:assembleDebug` builds without a device. `scripts/build [debug|release]` also installs on the connected TV and launches it.
- Local release builds are signed with the debug key unless you set `RELEASE_KEYSTORE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`.

## Test without a TV

- `./gradlew :app:testDebugUnitTest` runs the unit tests.
- `scripts/shots verify` renders every Home screen with Robolectric and Roborazzi and diffs against `app/src/test/screenshots/`. If a UI change is intended, run `scripts/shots record` and commit the new PNGs with the code. Fonts differ between operating systems, so pixel diffs are only reliable on the machine that recorded them; CI does not compare pixels. The clock and date are pinned and masked, so shots do not depend on the time of day; to prove a change keeps it that way run `GLASS_SHOTS_TIME=10:05 scripts/shots verify` (any `HH:mm`).
- `scripts/shots focus` crawls every focusable on Home in all four D-pad directions and fails on lost focus.
- `scripts/emulator start` boots an Android TV emulator for functional and focus checks.

## Test on a real TV

`scripts/e2e` runs the pytest suite in `e2e/` against a connected device with real remote presses. Set `ANDROID_SERIAL` if more than one device is attached. Some tests need root (Magisk) and skip themselves without it. Run it after changes to navigation, overlays, Control Center, Settings or app launching.

## Performance rules

The target is a Fire TV Stick 4K (2nd gen): 2 GB of RAM, a weak GPU, 32-bit userspace. Rendering changes should pass `scripts/perf-gate` against `perf-budget.json` (p90 frame time, PSS, janky-frame share, idle CPU). In short: no live blur or per-frame `RenderEffect`, bake backdrops once, keep bitmaps small, and animate transforms rather than layout.

## Pull requests

- Keep a change focused; describe what you tested and on which device or emulator.
- UI changes: include before/after screenshots.
- Don't add analytics, telemetry or network calls that aren't opt-in and documented in the README.
- By contributing you agree your work is licensed under Apache-2.0.
