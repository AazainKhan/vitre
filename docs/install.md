# Installing VITRE, step by step

A walkthrough for a TV you have never sideloaded to. No root needed. Takes about five minutes, and nothing here is
permanent — the last section undoes all of it.

`install.sh` does the work; this page is so you know what it will do before it does it.

- [On a computer](#on-a-computer)
- [On the TV itself (Termux)](#on-the-tv-itself-termux)
- [Fire TV: where the switches are](#fire-tv-where-the-switches-are)
- [Google TV / Android TV: where the switches are](#google-tv--android-tv-where-the-switches-are)
- [Undo](#undo)

---

## On a computer

You need `adb` (the Android Debug Bridge; it comes with [platform-tools](https://developer.android.com/tools/releases/platform-tools))
and the TV and computer on the same Wi-Fi.

```bash
curl -sL https://raw.githubusercontent.com/AazainKhan/vitre/master/install.sh | sh
```

That installs the latest release over the network. To also make VITRE the home screen, add a flag:

```bash
curl -sL https://raw.githubusercontent.com/AazainKhan/vitre/master/install.sh | sh -s -- --home
```

Nothing is destructive without `--home`: you can install, look at VITRE, and decide after. `--permissions` grants the
optional one-time permissions, and `--help` lists everything.

If you would rather build it yourself, `scripts/build release` and then `./install.sh --apk app/build/outputs/apk/release/app-release.apk`.

## On the TV itself (Termux)

If you have [Termux](https://termux.dev) on the TV, or just do not want a cable, VITRE installs from the TV:

1. In Termux: `pkg install android-tools` (that is `adb`).
2. Find the TV's IP: **Settings → My Fire TV → About → Network** (Fire TV) or **Settings → Network & Internet** (Google TV).
3. `adb connect <the-ip>:5555`
4. `curl -sL https://raw.githubusercontent.com/AazainKhan/vitre/master/install.sh | sh`

Over the network, allow ADB on the TV first — the next two sections show where that switch lives.

## Fire TV: where the switches are

| What | Where |
|---|---|
| ADB debugging (needed to install at all) | **Settings → My Fire TV → Developer Options → ADB Debugging** |
| The TV's IP address | **Settings → My Fire TV → About → Network** |
| Make VITRE the home screen | **Settings → Applications → Manage Installed Applications → VITRE → Home** *(only on some builds)* |

Fire OS has no "default launcher" picker like Android TV, so a Fire TV is made to use VITRE one of two ways:

- **Home Button Takeover** in VITRE's own Settings — an accessibility service. It is gentle and needs nothing else, but
  it works by catching the stock launcher's window, so the stock home flashes up briefly first.
- **Disable the stock launcher** (what `install.sh --home` does). Instant and complete:

  ```bash
  adb shell pm disable-user --user 0 com.amazon.tv.launcher
  ```

  Keep the undo line to hand. With the stock launcher disabled **and** VITRE uninstalled, the TV has no home screen at
  all until you run `adb shell pm enable com.amazon.tv.launcher` — which `install.sh --uninstall` does for you.

If your remote has an **app button** you never use, VITRE can remap it (Settings → Remote Buttons) so you keep a way
into the store. The dedicated **Settings button** on Fire TV remotes opens Fire's quick menu; `install.sh` leaves it
alone, and VITRE adds its own row to that menu (see below).

## Google TV / Android TV: where the switches are

| What | Where |
|---|---|
| ADB debugging | **Settings → System → About → tap Build 7 times**, then **Settings → System → Developer options → USB/Network debugging** |
| Make VITRE the default home | **Settings → System → Home Button → Make VITRE the Default** |

There is no adb command for "be the default home" on Google TV, so `install.sh --home` opens that settings page for you.

## Undo

```bash
./install.sh --uninstall
```

That removes VITRE and re-enables the stock launcher, so the TV is exactly as it was. Your VITRE layout is kept unless
you also clear its data:
`adb shell pm clear dev.glasslauncher`.

## If it goes wrong

| Symptom | Cause | Fix |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | the installed VITRE was signed with a different key — expected if you mix your own build with a release APK, since they use different keys | `install.sh` offers to uninstall and reinstall. If VITRE is **also a system app** (e.g. installed by a Magisk module) it survives the uninstall and keeps its own signature, so only an APK with the matching key will replace it — install your own build with `--apk` |
| `no adb device` | ADB debugging off, or wrong IP | see the tables above; `adb connect <ip>:5555` |
| Installs but never starts | the TV is older than Android 9 | check `adb shell getprop ro.build.version.sdk` (needs 28+) |
| The TV shows a black screen after uninstalling | the stock launcher is still disabled | `adb shell pm enable com.amazon.tv.launcher` |
| VITRE installs but Home still opens Amazon | the stock launcher is still enabled | run `./install.sh --home`, or use Home Button Takeover |

## Optional extras

Everything below is optional; VITRE is complete without it, and each only saves a trip through a system menu.
`install.sh --permissions` grants the ones that are adb-able:

```bash
adb shell pm grant dev.glasslauncher android.permission.WRITE_SECURE_SETTINGS   # its own screensaver
adb shell appops set dev.glasslauncher GET_USAGE_STATS allow                    # recent apps in the switcher
adb shell pm grant dev.glasslauncher android.permission.ACCESS_FINE_LOCATION    # Wi-Fi name in Control Center
adb shell appops set dev.glasslauncher PROJECT_MEDIA allow                      # no capture dialog for Sonos
adb shell pm grant dev.glasslauncher android.permission.RECORD_AUDIO
```

Two switches stay manual: **Notification access** (Now Playing) and the optional **accessibility services**.

On a rooted TV, `install.sh` also gets a VITRE row in Fire OS's own quick menu (the Settings-key panel), so Fire's
settings and VITRE's sit in one place. That is written by `firestick-unlocked/tools/quicksettings/glass-row.sh`; the
TV does not need to be rooted for VITRE itself.
