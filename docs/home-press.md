# Home press to first frame (P342)

What a press of Home costs, cold (Home's process had to start) and warm (it was alive), and whether pinning it changes that. Everything is
a debug property; a release build with none of them set does nothing.

## Read the numbers

```
adb shell setprop debug.vitre.homepress 1        # per-resume timing line; off with 0
adb logcat -s VitreHome
VitreHome: resume->first frame 84 ms (warm, process age 412000 ms, oom_score_adj 600)
```

- `resume->first frame` is the part Home controls: from `onResume` to the frame after its first draw.
- `cold` / `warm`: the first resume in a process is cold (`process age` is small); later ones are warm. Force cold with
  `adb shell am force-stop dev.glasslauncher` and then press Home.
- `oom_score_adj`: what lmkd kills by. 0 while Home is visible; 600 is the home app in the background; 900 and up is cached.
- Press to screen, end to end: the press happens before the app can see it, so time it from the key's own timestamp. DO NOT time it from a
  host or device clock taken before `adb shell input keyevent KEYCODE_HOME`: `input` is a Java program and its own start-up (about 0.9 s on
  the stick, see scripts/key) sits between that clock and the key, so it adds hundreds of ms to every sample (the first version of this
  recipe said to do that; its 485 ms press-to-screen was mostly that start-up). Use a real key event and read its kernel timestamp:
  `scripts/key home` (writes the event straight to the remote's input device, about 0.1 s) with `adb shell getevent -lt` running beside it
  (its `[ seconds.micro ]` stamp on the `KEY_HOMEPAGE DOWN`/`KEY_HOME DOWN` line is the key), and `adb logcat -v monotonic -s VitreHome` for the
  frame line; subtract the two (both are CLOCK_MONOTONIC seconds).
- The guard's fallback (Fire without root, the Home Button Takeover) logs `VitreBounce: bounce N ms ...` the same way.

## The recipe, per case

1. **Warm**: from Settings (or any app), press Home 10 times with 3 s between; take the median of the `warm` lines.
2. **Cold**: `am force-stop dev.glasslauncher`, press Home, take the `cold` line; repeat 5 times (discard the first launch after an install:
   shader compiles, see CLAUDE.md).
3. **After pressure**: open 6 heavy apps in turn, press Home. The line says `cold` if Home was killed meanwhile. That tells whether
   Home is ever evicted today (stick data, 6 heavy apps twice: never evicted, so no pin).

## Where the time between the key and onResume goes (P351)

Measured on the stick with the first recipe: warm resume to first frame 56 ms, "press to screen" 485 ms, so about 430 ms before `onResume`.
Re-measure with the key-timestamp method above first (the host-clock method over-counts by `input`'s start-up). If a gap remains,
break it down, in this order (each is one read-only command):

1. **Fire's Home handling.** `adb shell dumpsys window policy | grep -E "HomeBehavior|mHomePressed"`. If `mDoubleTapOnHomeBehavior` is
   anything but `DOUBLE_TAP_HOME_NOTHING`, a single press waits the double-tap timeout (300 ms in AOSP) to see whether a second tap follows
   before it launches Home: the top suspect for most of a 430 ms gap, and not ours to change without root (a runtime resource overlay or the
   key layout, both root tier). The emulator prints `LONG_PRESS_HOME_ALL_APPS` and `DOUBLE_TAP_HOME_NOTHING`.
2. **The outgoing app's pause.** Activity manager events, one warm press: `adb logcat -b events -v monotonic -d | grep -E
   "am_pause_activity|am_on_paused_called|am_resume_activity|wm_set_resumed_activity|am_on_resume_called"`. Home cannot resume until the
   outgoing activity reports paused: `am_pause_activity` to `am_on_paused_called` is that app's cost (not ours), `am_on_paused_called` to
   `am_resume_activity` is the system, `am_resume_activity` to `am_on_resume_called` is the Home activity's resume (ours).
3. **Our own path, if Home goes through it.** On Fire the Home key never reaches an accessibility service (docs/research/remote-keys-plain-apk.md),
   so RemoteKeysService is not on the path; `adb logcat -v monotonic -s RemoteKeys` (debug builds) shows any key it saw.
4. **The window transition.** `adb shell settings get global window_animation_scale; ... transition_animation_scale; ... animator_duration_scale`
   (1.0 per CLAUDE.md). The system's open animation starts after `onResume` and does not delay it; it only changes when the screen *looks*
   done, and the `VitreHome` line is the first draw, not the end of that animation.
5. **If 1 to 4 do not account for it,** one trace of one warm press: `adb shell perfetto -o /data/misc/perfetto-traces/home.pftrace -t 8s
   sched freq am wm view input gfx binder_driver` (press Home with `scripts/key home` about 2 s in), pull it, open it in
   https://ui.perfetto.dev, and look at the system_server threads (`android.display`, `android.anim`, `InputDispatcher`) between the input
   event and `activityResumed`, then Home's main thread from `performResume` to its first `Choreographer#doFrame`.

What is ours to cut: only the last part (resume to first frame, 56 ms). Everything before `onResume` is Fire's key handling, the outgoing app
and the system's activity start, unless a root-tier change (a key-layout remap that lets Glass launch Home on key down) is chosen later; that
would also change the long-press Home behaviour (remote pairing), so it needs measuring first and the user's say.
