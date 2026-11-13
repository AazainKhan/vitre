# Glass TV Launcher

Open-source (Apache-2.0) Apple TV / tvOS-style launcher for Android TV and Fire TV. Kotlin + Jetpack Compose for TV, single `:app` module, package `dev.glasslauncher`. The roadmap and goals are in `README.md`.

## Commits

- Use [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`, type one of feat, fix, perf, refactor, test, docs, build, ci, chore, style. Mark breaking changes with `!` or a `BREAKING CHANGE:` footer. Example: `fix(focus): stop Right at row ends`.
- The repo is public (github.com/AazainKhan/vitre). Commits use the noreply email set in the repo config. Before pushing, `git pull --rebase`, then push with `scripts/gates && git push`. The pre-push hook (`scripts/git-hooks/pre-push`) runs `scripts/leak-check` on every push (no device serials, home paths or crash dumps) and the gates unless `scripts/gates` passed for that exact tree, so a push can never follow a failed check.

## How the agents work

**Lanes.** Stay in yours. Findings for someone else's area go on the board, never as a patch.

**Subagents are allowed again** (user lifted the ban, 2026-10-25): spawn them for parallel or context-heavy work, one board item each, no grandchildren. The four lanes stay the default and the team is still four sessions.

| Agent | Lane |
|---|---|
| **Agent Manager** | Tooling, CI, hooks, publishing, the user's rules, vitals, and diagnosis handoffs. Raises every user decision with AskUserQuestion. Takes no app bugs. |
| **Yin** (Opus) | Design calls, reviews, merges to master, stick time, Control Center. Reads `tvos27-inspo` directly for design. |
| **Yang** (Sonnet) | Features and fixes on worktree branches, JVM tests, emulator runs. |
| **REA agent** | tvOS and Fire OS reverse-engineering answers, written to `rom/research/`. |

Pushes to the public repo happen only with the user's OK, relayed by Agent Manager.

**Board first.** `.superpowers/pair/board.md` is the single source of truth for work. The SessionStart hook prints its open items next to vitals and reports.
- Read it at the start of each turn.
- Claim an item before starting; update its status and a one-line result as you go; close it when done.
- Take new ids only from `scripts/board add <owner> "<note>"`: it assigns the next free number under a lock, so two agents never reuse one. `scripts/board` lists open items; `scripts/board set Pn "<status>"` updates one.
- A new user request gets a board item with a named owner before anyone works on it.
- SendMessage is for urgent interrupts only (a broken build, a stick collision, a decision blocking someone), not status updates.

**Devices: the emulator is the queue, the stick is for looks and speed.**
- **Functional, focus and e2e checks run on the emulator.** `scripts/e2e` defaults to it: it boots `scripts/emulator`, installs a debug build, and skips stick-only tests (marked `@pytest.mark.stick`, or listed in `STICK_ONLY_MODULES` in e2e/conftest.py). For your own commands: `eval "$(scripts/emulator env)"`, then `scripts/key`, `shot`, `tv` act on it. **The stick is for looks, perf, and anything the emulator cannot measure** — frame damage/partial redraw, native library load, frame-exact motion. Take the stick lock for any input; only Yin installs on it.
- **Leave a RELEASE build on the stick for the user, not debug** (P303): on the same key route debug measured p90 22 ms / 5.0 % deadline-missed against release's 11 ms / 1.55 % — the user is looking at whatever is installed, so a debug build makes Glass feel roughly twice as slow as it is. `scripts/build debug` is for iteration; finish a session with `scripts/build release --device`.
- **The stick is only for looks** (`scripts/clip`, tv-live) **and performance** (`scripts/perf-gate`, `scripts/bench`, `scripts/e2e --stick -m perf`). Always use a time-boxed lock: `scripts/stick-lock hold <who> [--max 30] -- <command>` renews itself while the command runs and releases it at the end. `bench`, `perf-gate`, `clip` and `e2e --stick` do this on their own. If someone else holds the lock, they fail fast instead of colliding.

**Models.** Each session keeps its own model: Yin Opus 5.5, Yang Sonnet 5.5. For ordinary work prefer running scripts yourself and reading only their short summaries; spawn a subagent when the work is parallel or would flood your context.

**Context:**
- Read logs with `tail`/`grep`, never whole.
- Look at images at 960 px or as crops, once.
- Point at the Gallery instead of pasting images.
- Background long jobs and wait for the notification.
- Keep state on the board, not in chat.
- Read state cheaply: `scripts/board` and `scripts/vitals --brief`, not whole files; `scripts/shots` already prints a short report.
- Before a context reset, write `.superpowers/handoff/<agent>.md` (≤25 lines: role, items and status, branch, next step, numbers, open questions, gotchas). A fresh session reads it first; the SessionStart hook lists them.
- At each milestone (item merged, round done), write the one-line result to the board, then compact or start fresh. Durable state lives in the repo, the board and decisions.md, so nothing is lost.
- Don't re-read the same strip, clip or log: note what you saw, with numbers, the first time.

**Automated eyes.** `scripts/shots all` (run by the Stop and pre-commit hooks) includes `VisualChecks`:
- **Ghost frames:** no element that appears during a transition stays half-transparent for more than 2 frames.
- **Control Center material:**
  - tiles don't change colour after landing;
  - unfocused tiles agree;
  - the Settings tile is never grey.
- A check with a known open bug is wrapped in `expectFail("Pn")`: it shows as skipped while the bug exists and fails once it passes, so the marker gets removed when the item closes. Today the three Control Center checks expect P17.

**Where knowledge lives:**

| What | Where |
|---|---|
| Rules and how-tos | this file |
| The user's settled decisions | `docs/decisions.md`. Read it before proposing a change; add a line when the user decides. |
| Live work | the board |
| The user's bug queue | `reports/inbox/` (`scripts/reports`) |
| Health | `scripts/vitals` (`--full` adds the JVM checks); Agent Manager runs it between rounds and chases anything red |
| Evidence | the Gallery |

**The loop:**
1. Read the board and vitals.
2. Claim.
3. Build in a worktree, Mac first, then the emulator.
4. Prove it: `scripts/shots all`, plus `perf-gate` and a clip on the stick for anything that renders.
5. Update the board.
6. Yin merges.
7. Agent Manager gets the user's OK and pushes.

## Every install mode, every device

Each feature defines its behaviour for the plain APK (no root), Glass Pro (rooted, Magisk) and Glass OS (ROM): full where possible, a clean fallback where not. It must also work on any Android TV or Google TV device, not just the Fire TV Stick. Root and system features (memory tuning, remote keys, Bluetooth, privileged permissions) detect what the device has at runtime: props, sysfs paths, packages, keycodes. Never hardcode Fire or karat values. Fire-specific code runs only behind a detection check, with a generic default. Yin rejects merges that assume root or Fire with no fallback.

## Build and run

- **Needs JDK 17 or 21.** The system default is JDK 25, which AGP rejects. `scripts/build` picks 21 automatically. For raw Gradle: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- **A gate run can die with `java.io.EOFException` on `:app:testDebugUnitTest` and no failing test.** That is the 3 GB test worker being killed, not a code flake: this Mac runs 16 GB with swap already near full (Hermes, Chrome, Claude), so check `vm_stat` / `sysctl vm.swapusage` before blaming the tree. It is not the JDK 21.0.1 `Field too big for insn` bug either — that one leaves an `hs_err_pid*.log` beside `app/` and names a JIT assembler; an EOFException leaves none. Re-run once the machine has room, and confirm the result by counting `app/build/test-results/testDebugUnitTest/TEST-*.xml` rather than reading the log tail (the tail of a failed run is only the Gradle trailer).
- `scripts/build [release|debug] [--device]`: builds; with `--device` it also checks the privapp allowlist, installs on the connected TV and launches. Without `--device` it never touches adb (agents and hooks must not pass it). `--device` needs the stick lock held by you (`scripts/stick-lock take`, `TV_OWNER`) unless the device is an emulator. Default is release (R8 on, signed with the debug key for now).
- Toolchain: Gradle 9.8 wrapper, AGP 9.4.1 with **built-in Kotlin** (don't apply `org.jetbrains.kotlin.android` in `app/`), Kotlin 2.4.20, compileSdk 37, minSdk 28, targetSdk 36. Versions live in `gradle/libs.versions.toml`.
- If a Gradle command hangs with no output, a daemon is stuck: `./gradlew --stop`, then rerun.
- macOS has no `timeout`; use `gtimeout` (Homebrew coreutils, installed). Never wrap a command in a missing binary: it silently does nothing.
- `installRelease` sometimes doesn't replace the APK. If behaviour looks stale, check `adb shell dumpsys package dev.glasslauncher | grep lastUpdateTime`, then `adb install -r app/build/outputs/apk/release/app-release.apk`.

## Test device

Fire TV Stick 4K **Max** 2nd Gen (AFTKRT / `ro.product.device=karat`, Fire OS 8 = API 30, armeabi-v7a, Imagination GE9215 GPU, 1.67 GB usable). Rooted with Magisk. Connected over adb.

- **Glass TV Launcher is the default home** (set with `cmd package set-home-activity`; the Magisk boot script `/data/adb/service.d/start-launcher.sh` starts it). AT4K (`com.overdevs.at4k`) stays installed as the fallback. To revert: `adb shell cmd package set-home-activity com.overdevs.at4k/.MainActivity` and edit the boot script back.
- The user has given broad permission for device changes. Still restore anything you change only for a test (e.g. screensaver settings).
- Fire OS ignores third-party DreamServices, even with Ambient Experience disabled; `Somnambulator` won't start one. Use the in-app "Start Aerials on Home after" (set to 3 min on this stick).
- `WRITE_SECURE_SETTINGS` and the Now Playing notification listener are granted to the app on this device.
- **A black screenshot usually means the stick is asleep** — wake it with `adb shell input keyevent KEYCODE_WAKEUP`. Sleep is set to never (`settings get secure sleep_timeout` = `-1`, set 2026-10-11, logged in DEBLOAT-LOG.md); anything shorter is drift.
- To simulate a Home press to the launcher: `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n dev.glasslauncher/.MainActivity`. **Always start Glass this way** (`scripts/build` and the boot script do): a plain `am start -n` puts it in an ordinary task, and the first Home press then creates a second Glass in the home task (state lost, double memory).
- Fire OS drops `Log.d`/`Log.v` from apps; use `Log.i`/`Log.w` for temporary debugging.
- Netflix (and other DRM apps) set FLAG_SECURE: screenshots and `scripts/clip` show black. Use YouTube, Stremio or TizenTube to judge app open/close.
- AirPlay comes from PhairPlay (`scripts/phairplay install`): upstream at a pinned commit plus `tools/phairplay/glass-control.patch` (signature-guarded control receiver, state broadcasts, player brought forward on a session). Same debug key as Glass, which is what grants `com.phairplay.permission.CONTROL`. Idle cost: 0% CPU, ~44 MB PSS in its own process. Control Center shows the AirPlay pill only when it's installed.

## Device end-to-end tests

- `tv.tree()` asks `system/DebugDumpReceiver` (guarded by `android.permission.DUMP`) first: Glass returns the Control Center overlay's or its own window's semantics tree as uiautomator-shaped XML (`system/SemanticsDump.kt`) in ~80 ms instead of uiautomator's ~2 s, which took the full suite from 57 to 13 minutes. When another app is in front it returns nothing and the test falls back to `uiautomator dump`. `GLASS_FAST_TREE=0` forces uiautomator. Control Center is an accessibility overlay window (`home/ControlCenterWindow.kt`, owned by `RemoteKeysService`) that uiautomator can't see at all.
- Page helpers wait for a press to land before reading focus (`Screen._focus_after_press`): fast reads beat the key event.
- Aerial clip changes are checked from a screen recording, frame by frame (`e2e/test_aerials.py`, needs ffmpeg): `AerialActivity` takes `--el dev.glasslauncher.extra.NEAR_END_MS 9000` to start near a clip's end.
- Compose pads accessibility bounds up to the 48 dp minimum touch target: measure drawn sizes from a screenshot, not from node bounds.
- Perf measurements: open overlays from the grid (Home's featured slideshow cross-fades every few seconds and lands at random in the window), warm up once (first open decodes and composes), and don't read the tree inside the measured window (uiautomator makes Glass build its accessibility tree mid-animation).

`scripts/e2e` (pytest in `e2e/`, see its README): tests over every main flow, with real remote presses and state from the accessibility tree. **It runs on the emulator by default** (Fire-OS-only modules are skipped); use `scripts/e2e --stick` for the full suite on the real Fire TV under a self-renewing lock. Run it after changes to navigation, overlays, Control Center, Settings, app launching or Fire OS integration. `uiautomator dump` refuses while something animates (move mode's wiggle), so those checks read the screen instead.

## Dev loop: see it, check it, measure it

Work cheapest-first. Most UI work never needs the device.

**0. Start of a session:** the SessionStart hook lists open **reports** (the user's "Report" button in the live view, and crashes/ANRs from `scripts/watch-crashes`): `scripts/reports`, `scripts/reports show <id>`, and when fixed `scripts/reports done <id> "what changed"`. Treat them as the user's bug queue. Before sending input to the stick, `scripts/stick-lock take "<you>"` and `export TV_OWNER="<you>"`; other sessions may be using it. Release it when done. Restore blocks never `pm uninstall` Glass (it drops the su grant and leaves the TV without a home): `adb install -r` a master APK that is already built, and check the file exists before any install step. Work in a git worktree when another session is active in this checkout.

**Guard rails (automatic):** a Stop hook runs `scripts/shots all` when UI code changed and blocks once if it fails. The git pre-commit hook (`core.hooksPath = scripts/git-hooks`) does the same for commits touching UI or baselines. If the change is intended, run `scripts/shots record` (and `previews record`) and commit the new baselines with the code.

**Build loop:** after a one-line change, a debug build is about 3 s plus 4 s to install; release (R8) is about 80 s. Use `scripts/build debug` for anything visual on the device and release only for performance numbers.

**1. On the Mac, no device (Robolectric + Roborazzi, `app/src/test/.../shots/`):**

- `scripts/shots verify` renders every Home screen (dock, featured row, grid, Control Center, app menu, folder, settings, light) at the stick's 960x540 dp and diffs against `app/src/test/screenshots/`. About 25 s warm. On failure, look at `app/build/outputs/roborazzi/*_compare.png`.
- `scripts/shots record` after an intended UI change, then review the new PNGs in the diff like code. `scripts/shots verify grid` runs one screen.
- `scripts/shots focus` crawls every focusable on Home with all four D-pad directions and fails on focus loss, unreachable apps, or Down from a featured card staying in the row. Read the whole graph in `app/build/focus-graph/home.md`; add named expectations to `FocusCrawlTest` when you fix a focus bug.
- `scripts/shots previews [record]`: component `@Preview`s in `app/src/debug/.../preview/ComponentPreviews.kt` (tiles, tray, menu, light/dark) on the real wallpaper, as screenshot tests (a couple of seconds each). Iterate on a single component here; add a preview for whatever you're polishing.
- `scripts/compare-ref [screen] [keyword]`: our render next to the matching tvOS 27 frame from `tvos27-inspo/screenshots` (default mapping per screen), written to `build/compare/`. Use it to check spacing, glass and type against the source of truth. `--list <keyword>` finds frames.
- `scripts/shots strips [name]` writes frame-exact strips of transitions (12 frames, 32 ms apart, clock paused) to `app/build/strips/`. One image shows a whole focus move: use it to judge motion, not just the end state. Add a case in `MotionStrips.kt`.
- The harness (`TvHarness`) installs 14 fake TV apps with generated banners, seeds the featured cache with fixture art, blocks the network, marks tips seen, and takes a `config` lambda for other states (folders, theme…). The clock and status pill are masked out of baselines. CI runs these tests but doesn't compare pixels, because fonts differ across OSes.
- Robolectric gotchas: Coil must use BitmapFactory (`imageDecoderEnabled(false)`, already done in the harness), and `createEmptyComposeRule` + `ActivityScenario` run the real `MainActivity`.

**2. On the device:**

- **Gallery** (`/gallery` in tv-live): everything being built, refreshing itself. Tabs: screen renders and failing diffs, component previews, motion strips and device clips, the focus graph, reference comparisons, and reports. Point the user here instead of pasting images.
- **Live view in the Claude browser pane:** preview `tv-live` (`scripts/tv-live`, http://localhost:8765). It shows the screen at native 1080p (20 Mbit/s), with a "Lossless still" PNG and an unscaled "1:1" mode for judging edges and blending, and arrows/Enter/Backspace/H/M on the page drive the remote. It records with the stick's encoder, so stop it before benchmarking. `scrcpy` gives a native window. Its **Report** box saves a lossless still, the last 6 s as video, the focused element and the note to `reports/inbox/`.
- `scripts/key down down right select`: real remote events via `sendevent` (adb is root), about 0.1 s per press vs 0.9 s for `input keyevent`. Also `hold:800:down` (auto-repeat), `wait:300`, and `settle`. `settle` waits until the app stops drawing and prints when the last frame landed (an animation-length measurement), or `busy` after 3 s of continuous drawing (video backdrop).
- `scripts/shot [out.png] [width]`: screenshot in about 1 s, downscaled to 960 wide. It fails with a clear message when a DRM app (Netflix…) is on screen.
- `scripts/clip <name> <keys…>`: records a transition and writes `build/clips/<name>.png`, 18 frames 33 ms apart starting at the first visible change, plus the mp4.
- `scripts/tv-type [--select] [--submit] "text"`: types any Unicode through ADBKeyBoard, which is installed on first use and draws no UI, so the D-pad keeps working. Restores Fire TV's keyboard afterwards. Use `--select` on a text row so editing starts after the IME switch.
- `scripts/dev-mode on|off`: keep the stick awake (stay-on + long timeouts), stop AT4K, wait for dexopt to finish; `off` restores the saved values.
- **Idle chrome fade** doesn't swallow keys (`Idle.touch()` only records the time); tested 2026-10-07: Down after a 1-minute fade in full screen moved focus. If a key ever seems lost after idle, it's Fire OS (screen dim/wake), not Glass.
- **Debug-build hooks:** `app/src/debug/.../DebugEventActivity.kt` handles `glassdev://event?name=…&payload=…`. Events: `config` (merge JSON into `LauncherConfig`) and `home`. Use `scripts/tv trigger-app-event <name> '<json>'`. Release builds don't contain it.

- **Emulator for parallel work:** `scripts/emulator start` boots an Android TV (API 31) emulator on its own adb server, invisible to everything using the stick; `eval "$(scripts/emulator env)"` points `scripts/key`, `shot`, `tv` etc. at it, and `scripts/emulator install debug` puts the current build on it. Use it for functional and focus checks while someone else has the stick. It doesn't match the stick's GPU, Fire OS or speed, so judge looks and performance on the stick only.
- **Fire twin:** `scripts/emulator start --twin` boots GlassFire_API30 (API 30, 1080p @320dpi, like the stick; ports 5610/5611, `env --twin`), `scripts/emulator mirror` copies the stick's user apps and Glass prefs onto it (read-only on the stick: `GLASS_STICK=1` + lock + `STICK_SERIAL`), `scripts/emulator deploy-stick [--stick]` builds and installs (twin by default). What it can and cannot prove: `docs/emulator-twin.md`; test the stick-facing paths with `scripts/emulator-test-shim`.

**agent-device** (`scripts/tv`, also registered as an MCP server in `.mcp.json`; defaults live in `agent-device.json`):

- Batch steps: `scripts/tv batch --steps '[{"command":"tv-remote","input":{"action":"press","button":"down"}},{"command":"snapshot","input":{"interactiveOnly":true,"diff":true}}]'`. Add `--level digest` for cheaper output.
- Assert with **id selectors**: `scripts/tv is focused 'id="app:com.netflix.ninja"'`. Tags include `app:<pkg>`, `folder:<id>`, `featured:<id>`, `featured-row`, `status-pill`, `control-center`, `settings-tile`, `folder-title`, `text-value`, `text-input`, `dock`, `home`. `label=` selectors fail on text containing `…`.
- Snapshots take about 2.5 s and `wait stable` about 5 s, so prefer `scripts/key … settle` plus `scripts/shot` for quick loops. `find <text>` **taps** the match. `perf frames` needs `scripts/tv open dev.glasslauncher` first.
- Every agent-device version shares one daemon in `~/.agent-device`; two sessions on different versions keep replacing each other's daemon. Keep everyone on the pinned version, or set `AGENT_DEVICE_STATE_DIR` per session.

**3. Numbers:** `scripts/perf-run [--rounds N] [keys…]` (see below). `scripts/perf-gate` builds release, runs two routes (the dock, where the Top Shelf autoplays, and the grid) and fails against `perf-budget.json` on either route's deadline-missed %, p99 or PSS, or idle CPU; janky %, high-input-latency % and p90 are reported, not judged (Android 11 flags on-time autoplay frames as high input latency). Run it before merging rendering changes. `scripts/bench` runs the Macrobenchmarks (cold startup and D-pad browsing, with and without the Baseline Profile) and `scripts/bench profile` regenerates the profile; both need Magisk root for the adb Shell user.

## Remote buttons (Fire TV)

- Fire OS consumes the app buttons (`KeyMapManager` in system_server launches Netflix/Prime/…), Recent Apps, and Settings (a global key sent to `com.amazon.tv.settings.v2/.GlobalKeyHandler`) before any app or accessibility service sees them. Home long-press goes to `com.amazon.tv.quicksettings`. Leave Home and the quick menu alone (user's call).
- `scripts/remote-keys install` (Magisk module `tools/magisk/glass-remote-keys`, needs a restart) remaps the app buttons and Recent Apps to BUTTON_9..13, which reach `RemoteKeysService`; actions live in `system/RemoteButtons.kt` and Settings › Remote Buttons. `uninstall` restores Fire's behaviour. Settings can't be freed by a key layout: `FireTVKeyPolicyManager` (in `interceptKeyBeforeQueueing`) catches it by scan code 249 whatever keycode it maps to, and it also swallows BUTTON_16. So the module's `service.sh` rewrites the remote's kernel keymap (`tools/keymap/glass-keymap`, built with `zig cc -target arm-linux-musleabi -static`) from 249 to 185, a code the layout doesn't name: it arrives as KEYCODE_UNKNOWN with scan code 185, and its key-up never reaches accessibility services, so `RemoteKeysService` acts on key-down. The remote gets a fresh keymap on every reconnect; `service.sh` re-applies via Magisk busybox `inotifyd` (toybox's rejects the `:n` mask).
- Fire TV's Settings app has no main screen (the stock launcher draws it; `ACTION_SETTINGS` hits `CTSDummySettingsHandler`). Glass's `Overlay.TvSettings` lists the sections and opens `com.amazon.tv.settings.v2/.tv.*Activity` directly. Display & Sounds, Preferences and Account & Profile require `com.amazon.tv.permission.LAUNCHER_SETTINGS` (signature|privileged), so `SystemControls.openTvSettings` falls back to `am start` as root. Don't request the permission instead: Fire OS won't boot if a privileged app requests a permission missing from glass-system's privapp allowlist (written at install time).
- androidx.benchmark 1.5.0 checks root with `su root id`, which Magisk's su reads as an interactive shell (it waits on stdin forever). `scripts/bench` therefore runs `am instrument` as root with an AOSP-style `su` shim first on its PATH. Gradle's connected/generate tasks still hang: use `scripts/bench` (Macrobenchmarks, one per perf budget scenario) and `scripts/bench profile` (Baseline + Startup Profiles, generated on the non-minified build so R8 rewrites the names each build; re-run after big UI changes and before releases). `scripts/profile-capture` is retired: it dumped obfuscated names that went stale.
- `RemoteKeysService` must set `FLAG_REQUEST_FILTER_KEY_EVENTS` at runtime in `onServiceConnected`; the XML flag alone didn't deliver keys on Fire OS 8. Keys injected with `input keyevent` never reach accessibility filters; use `scripts/key` (sendevent), which now also knows `app1-app4 recents settings alexa tv mute`.

## Performance rules (learned on the GE9215, don't regress)

The GPU affords only about **two full-screen blended passes per frame**. Target: under 1% janky frames, about 0% idle CPU, under 120 MB PSS, release APK under 8 MB.

- Wallpaper bitmaps are opaque (`setHasAlpha(false)`), HARDWARE config, with the scrim baked in (`WallpaperLoader.load`). Never add full-screen translucent overlays.
- Glass (`glass/Glass.kt`) is a single fill per surface: a screen-mapped `BitmapShader` composed with the tint and highlight. No `clipPath`, no stacked translucent passes. The dock tray is *clear* glass (`GlassStyle.clear`): it samples the lightly blurred `clearSoftware` over the hero (the frosted `blurredSoftware` once the grid is up) and adds one masked stroke, the refracted edge band, which fades into the body so there's no seam. Panels and folders stay frosted. Each extra shader-filled stroke along the tray cost ~7 ms/frame (measured: 4 rings took p50 from 12 to 28 ms), so keep it to one.
- No animated `shadowElevation`; tiles use the pre-blurred `TileShadow` bitmap.
- Appearance is baked: `WallpaperLoader` washes the blurred rungs and glass texture milky white in light appearance (dark art more) and towards charcoal in dark (light art only), growing per rung so the scroll fades into it. The palette follows the theme, not the art. Switching appearance re-bakes behind a dissolve.
- Heavy content that scrolls in and out (the top shelf) lives **outside** the `LazyColumn` and moves with the scroll in `graphicsLayer`. Fades use `CompositingStrategy.ModulateAlpha`.
- Animate only in layout/draw phases (`graphicsLayer {}`, `drawWithContent {}`); no per-frame recomposition.
- Idle: no infinite animations. The clock and idle timer wake at most once a minute (seconds on the status pill measured 1.2% idle CPU, over budget; Control Center's clock ticks seconds only while open).

- Wallpaper blur transitions step through `Backdrop.ladder` (opaque, progressively blurred copies). Mid-scroll the two neighbouring rungs are cross-faded (two full-screen draws only while the blur moves): measured 0% janky on dock↔grid, and it removed the visible stepping. Don't cross-fade two full-screen images at rest.
- Hero backdrops are baked at 1920x1080 (shown 1:1); every blurred copy comes from one 960x540 intermediate. 1080p vs 720p made no measurable frame difference. Blurs use RenderScript's ScriptIntrinsicBlur (deprecated, still on Fire OS 8; ~50x the Kotlin box blur, which stays as the JVM/test fallback): a bake is ~245 ms CPU, was ~640. Don't put a colour filter on bitmap draws that don't need one (forces Skia's slow path).
- Glass stays flat while the backdrop blurs and fades its texture back in (220 ms) after a scroll lands; blending during the scroll cost ~6% janky frames. Keep one glass node per surface and draw focus fills over it (swapping modifiers made fresh nodes that could draw unpositioned).
- Glass is the GPU hot spot. Over a blurred backdrop (`wallpaperBlur > 0`, i.e. scrolling to or resting on the grid) it draws a flat tint and rim, no texture or gradient shaders: dock-to-grid went from 25% janky frames to ~1%. Measure with the recorder off: `scripts/perf-run --rounds 5 down up` while killing `screenrecord` (tv-live respawns it).
- The slideshow pauses while the grid is focused and waits for 3 quiet seconds, so a slide's bake and cross-fade never land on a scroll.
- Rendered tiles are HARDWARE bitmaps and Coil's memory cache is capped at 6% (GlassApp.kt); software bitmaps cost both native heap and GPU memory.
- Blurred text shadows (`TextStyle.shadow`) are expensive when the text moves. Use them only on small static text (the clock).
- Background load skews numbers: `installd` runs after `compile -m speed`, and AT4K burns CPU if it's running. Wait for `installd` to go idle and keep AT4K stopped before benchmarking. Current baseline in a key-every-1.4 s stress run: median 7 ms, p90 about 11 ms, 4–9% janky (transitions only), about 0% idle CPU, about 84 MB PSS.

How to measure: `scripts/perf-run` compiles with `-m speed`, turns on dev-mode, resets gfxinfo, plays D-pad rounds at real remote pace (1.4 s apart), and prints one JSON line: frames, janky %, p50/p90/p95/p99, PSS and idle CPU (from `/proc/<pid>/stat`, since toybox `top` is unreliable). Run `scripts/dev-mode off` afterwards. Check `adb shell ps -A | grep screenrecord` first: a live view open in any chat records with the stick's encoder and roughly doubles p90. If you can't stop it, A/B against the previous build under the same load. For deeper dives: `adb shell perfetto … gfx view sched freq --app dev.glasslauncher`, analysed with the `perfetto` Python package in `tools/.venv` (`python3 -m venv tools/.venv && tools/.venv/bin/pip install perfetto`).

To bisect, temporarily add a `DebugFlags` object that reads `getprop debug.glass.flags` and gate suspects on its bits. Benchmark each bit with the script pattern in git history, then delete it.

## Root features and device tuning (measured 2026-10-07)

Settings › Root (shown only when su is granted; `system/Root.kt`, `system/RootFeatures.kt`) and two Control Center buttons (Performance, Free Memory). Every action is logged to `files/root-actions.log` (Settings › Root › Root Log). Glass's su grant is in Magisk's policy table (uid 10277), so no prompt appears on the TV.
- **System App** (Magisk module `glass-system`): Glass as a privileged app. Fire OS enforces the privapp allowlist (`ro.control_privapp_permissions=enforce`; a missing entry stops boot), so the module's allowlist lists every requested permission. Updates still install over it with `scripts/build` (same key; it becomes an UPDATED_SYSTEM_APP). `dumpsys meminfo <pkg>` stops matching a system app's process: query by pid (perf-run does).
- **TV rows** (`featured/TvRows.kt`, needs the system app for ACCESS_ALL_EPG_DATA): Continue Watching (Watch Next + apps' "Continue watching" channels) and, in Focused App mode, the focused app's own channel (Stremio, Spotify, VLC publish them). Don't filter on `browsable`: Fire OS's launcher never sets it. Stremio rows have portrait posters, so titles with an IMDb id get Cinemeta's background and logo (`withCinemeta`, parallel and cached).
- **Memory tuning** (`scripts/tuning` / module `glass-tuning`): Fire OS caps cached apps at 4 (`activity_manager_constants max_cached_processes=4`), lmkd kills the heaviest app first (`ro.lmk.kill_heaviest_task=true`, always the video app you just left) and the 800 MB zram fills during boot. The module sets 12 cached apps, LRU-first kills and 1.2 GB zram by overlaying `/vendor/etc/fstab.enableswap` (Fire turns swap on at boot completion, so resizing later fails). Five-app switching: 0/5 cold starts on return, was 2–3/5.
- **Debloat** (`scripts/debloat`, logged in the workspace's DEBLOAT-LOG.md): OTA (an update could unroot), metrics/telemetry, crash upload, ads, recommendations, FFS setup, whole-home audio; Prime Video, Appstore and ES File Explorer restricted from running in the background. Measured gain is small (91 → 89 processes); OTA off is the main value.
- **Didn't help, not kept:** GPU min 850 MHz / CPU min 1.3 GHz / schedutil 1 ms (no frame or launch-time change; schedutil already ramps to 2 GHz at once; only adds heat — still part of the opt-in Fast profile, as asked); Skia Vulkan (`debug.hwui.renderer=skiavk`): p90 +1–2 ms and +20 MB PSS.
- Fast profile = system window/transition animations off (instant app switches; Glass keeps its own motion) + the clock floors. Re-applied at Glass start since sysfs resets on boot.
- `scripts/key` falls back to Amazon's virtual keyboard (`amzkeyboard`, Select = KEY_ENTER) when the Bluetooth remote's node is gone (it sleeps); injected `input keyevent` Selects don't click Glass's tiles on Fire OS.
- When regenerating the profile, check time to full display as well as TTID: an earlier fresh capture measured slower to fully drawn than the profile it replaced (2.2 vs 1.9 s).

## App open and close (`home/AppTransition.kt`, motion-spec §9)

Glass draws both itself; measured on the stick 2026-10-07 against the HotshotTek reference.
- **Open:** PixelCopy of the window straight into 480×270 (async, off the UI thread), blurred, shown as the cover in one frame; a rounded window grows from the tile's focused rect (`tween(450)`, exp-decelerate k=3.5, starting 12% in), crossfading from the tile art to a launch colour plus the logo lifted off the banner in grey. `startActivity` at 170 ms with `makeCustomAnimation(app_open_enter, app_open_hold)`; the app's first window fades in over it. Home's own content is hidden (alpha 0) while the cover is opaque.
- **Close:** on return the window (left full screen through ON_STOP, so the system's task snapshot matches Home's late first frame) waits for steady frames, shrinks into the tile (`tween(220)` ease-in-out, art back by half size), lands 1.3× and settles (125 ms), then Home sharpens (120 ms) before the window goes.
- **Fire OS's Home key flash:** SystemUI's `KeyFeedbackUI` plays a `BlackEffect` (screen fades to black and back, ~250 ms) on every real Home key press, over whatever is on screen; it was the "glitch" in the close. It's gated by `persist.perfanimation.enabled` (was true); **pinned to false by the `glass-props` Magisk module** (source `firestick-unlocked/tools/magisk/glass-props/`) because Fire OS drifts it back to true part-way through boot — the module's `service.sh` re-asserts across the boot window, and a bare `setprop` alone does not survive. Logged in DEBLOAT-LOG.md. A Home *intent* never showed it.
- Measuring: `scripts/key` now falls back to a temporary virtual remote (`tools/uinput/glass-press`, vendor 0171/product 0427 so the remote's key layout applies) when the Bluetooth remote sleeps; `scripts/clip`'s settle can stop recording during the hand-off, so add `wait:1500` after `home`.
- What didn't work: `makeThumbnailScaleUpAnimation` draws no zoom at all on Fire OS 8; `GraphicsLayer.toImageBitmap` for the cover cost 50–90 ms of main thread per press; `clipPath` for the window made its first frames 80–150 ms; the theme's `windowAnimationStyle` is ignored for the home task (Fire faded Home in from black ~300 ms after the Home press), but `overridePendingTransition(0, 0)` in `onRestart` works.
- A Home intent that arrives while Glass isn't resumed is a return from an app: it keeps focus and scroll (Home pressed on Home still goes to the top). The slideshow pauses while Home is stopped.

## Text contrast (light and dark)

- Glass and the grid backdrop are baked into a text-safe range per pixel (`Blur.legible`): dark appearance nothing brighter than 45% (white text ≥ 4.5:1), light appearance nothing darker than 50% (dark text ≥ 4.5:1). Overlay snapshots get the same. Palette secondary/faint alphas are set for that range.
- Text placed straight on artwork (Control Center clock and weather, "Press up for full screen") picks white-with-shadow or dark from `Backdrop.artLight(region)`, a luminance grid of the art, not from the theme.
- Control Center tiles use the dock's clear glass from a text-safe copy (`clearLegible`), without the refracted-edge stroke: with it a dozen small tiles took frames to 23 ms. Measured on the CC navigation route: 9% janky vs 23% for the old overlay style.

## Fire OS integration notes

- Appstore: its launcher entry only sends Home `navigate_node=l_apps`, which needs Amazon's launcher. `system/AmazonStore.kt` enables it (root), opens the page, and on the next Home start (root `logcat` of ActivityTaskManager START / `wm_new_intent`; Home presses reach no app or accessibility service, and `amazon.intent.action.HOME_PRESSED` is sent to two Amazon packages only) disables it again and restores Glass as Home.
- Fire TV's sleep timer ignores adb/`am` activity: long e2e runs used to find the stick asleep mid-run (null root node from uiautomator, "Glass in front" timeouts). The `home` fixture sends KEYCODE_WAKEUP before every test.
- `com.amazon.firetv.troubleshooting` ("FireTV Troubleshooting") is a 23 KB stub that only adds an app icon; `scripts/debloat` disables it. Fire TV Early Access is hidden by `HomeModel.LATER_HIDDEN`; holding Back 1.5 s (`RemoteKeysService`) goes Home from any app that traps the remote.
- Uninstall needs `REQUEST_DELETE_PACKAGES` (Android 9+); without it the system uninstaller closes at once (the "white flash").

## Code gotchas

- `BackdropState.swap` must clear `previous` in a `finally`: a swap cancelled mid-fade left it set, so every glass surface drew twice forever and the old backdrop stayed in memory (perf-gate went from 0% to 12% janky, +26 MB).

- **No labelled returns out of inline composable lambdas** (`return@Column`, `return@key`, `return@LaunchedEffect` inside `runCatching{}.getOrElse{}`). They compile but R8 fails with `$$$$$NON_LOCAL_RETURN$$$$$`. Use if/else.
- Compose leaves a node out of the accessibility tree when a later sibling covers it completely. Home's list is full screen, so anything tests or TalkBack must see on Home (the Now Playing hero) is drawn after it.
- Compose on TV defaults to a 30% "pivot" bring-into-view. Lists that shouldn't pivot use `MinimalScroll` (panels) or `NoAutoScroll` (home list, scrolled manually in `onRowFocused`).
- Overlays: only the top overlay traps focus (`Modifier.trapFocus(active)`); stacked ones refuse focus. Select and Menu handling only fires on a press that **started** on that element, so the key-up of the press that opened a menu is ignored.
- Fire TV's keyboard is full-screen and modal and opens whenever a `BasicTextField` gains focus. Text panels show the value in a row and only make the field focusable while editing (`TextInputBody`). The keyboard's action button submits; the phone form (`PhoneSetupServer`) is the preferred path.
- Apple's Aerial host (`sylvan.apple.com`) chains to Apple Root CA, which Android lacks. It's trusted for that host only in `res/xml/network_security_config.xml`.
- Config is one JSON blob in DataStore (`data/ConfigStore.kt`, `LauncherConfig`), encoded with defaults. **Changing a default doesn't affect existing installs.**

## Design source

Follow the `tvos27-guidelines` skill (and `~/Documents/firestick-unlocked/tvos27-inspo/`). Geometry lives in `ui/Metrics.kt`, measured from tvOS 27 frames: 5:3 tiles, 45dp inset, 24dp gutters, tray at 384dp, 36dp tray-to-grid gap. Home's backdrop is a scene: featured art (`WallpaperLoader.fromUrl`), the wallpaper, or Aerial video (`MotionBackground`). It's swapped through `BackdropState.swap`, which cross-fades for only 550 ms. No focus shimmer: the user rejected it as tacky. Motion curves live in `ui/Motion.kt`, fitted to the measured tvOS 27 timings (skill `references/motion-spec.md`); the stick's animation scales are 1.0x so they play at real speed.

## Layout

`apps/` (app list, tile art, icon packs) · `glass/` (blur, wallpaper, glass node) · `home/` (screen, model, overlays, menus) · `featured/` (top shelf + Stremio/TMDB/YouTube/Plex sources) · `dream/` (Aerial screensaver) · `widgets/` (clock, weather, now playing, idle/burn-in) · `system/` (home guard, updater, backup) · `settings/` · `ui/` (FocusTile, controls, theme).

## Licensing

Apache-2.0. Don't copy code from GPL projects (FLauncher, AerialViews) or from atvLauncher (no licence). Google's `android/tv-samples` is Apache-2.0 and safe to borrow from. Featured sources use the user's own API keys; keep the TMDB and JustWatch attribution text.
