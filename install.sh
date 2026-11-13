#!/usr/bin/env bash
# Glass TV Launcher, one-line install.
#
#   curl -sL https://raw.githubusercontent.com/AazainKhan/vitre/master/install.sh | sh
#
# Works from a computer with adb, or from the TV itself with Termux. It only ever Installs an APK and (with --home)
# changes which app is HOME - nothing else, so nothing here needs root.
#
#   --apk PATH     install this APK instead of the latest release
#   --home         also make Glass the home screen (stronger, reversible; see the note at the end)
#   --permissions  also grant the optional one-time permissions (screensaver, recents, Wi-Fi name, Sonos)
#   --yes          no prompts
#   --uninstall    remove Glass and restore the stock launcher
#
# Nothing is destructive by default: without --home the stock launcher is left alone, so you can try Glass first.
set -euo pipefail

REPO="AazainKhan/vitre"
PKG="dev.glasslauncher"
APK=""
DO_HOME=0; DO_PERMS=0; ASSUME_YES=0; DO_UNINSTALL=0
ADB="${ADB:-adb}"

# `curl … | sh` feeds the script to sh on stdin, and adb inherits that same stdin and buffers it - so a few adb calls
# in, sh reads nothing more and the script just stops early with a zero exit (found by testing the piped one-liner;
# it looked identical to success). Every external command therefore gets its stdin cut off, and prompts read the tty.
A() { "$ADB" "$@" </dev/null; }
# Is Glass present for user 0? `pm path` is not enough: after `pm uninstall --user 0` it prints nothing while still
# exiting 0, and an install afterwards does NOT re-include user 0 - the tombstone survives, so the app is on disk and
# simply not runnable ("Activity class does not exist"). Membership is the honest test, and install-existing clears it.
# grep -c, never grep -q: with `set -o pipefail` an early-exiting grep SIGPIPEs the producer and the pipeline then
# reports 141, so a `grep -q` match reads as "no match". Every matcher here has to read its stream to the end.
present() { [ "$(A shell pm list packages --user 0 2>/dev/null | tr -d '\r' | grep -c "^package:$PKG$")" -gt 0 ]; }
reinclude() { A shell cmd package install-existing --user 0 "$PKG" >/dev/null 2>&1 || true; }
have_stock() { [ "$(A shell pm list packages 2>/dev/null | tr -d '\r' | grep -c "^package:com.amazon.tv.launcher$")" -gt 0 ]; }
ensure_home() {  # never leave the TV with no home screen at all
  have_stock && A shell pm enable com.amazon.tv.launcher >/dev/null 2>&1 || true
}

say()  { printf '%s\n' "$*"; }
die()  { printf 'install: %s\n' "$*" >&2; exit 1; }
# Prompts read the tty, not stdin: under `curl | sh` stdin is the script text, so a plain `read` would swallow
# the next lines of the script as the answer. With no tty at all (a phone piping into sh), fall back to stdin.
ask()  {
  [ "$ASSUME_YES" = 1 ] && return 0
  printf '%s [y/N] ' "$1"
  if [ -r /dev/tty ]; then read -r a </dev/tty; else read -r a; fi
  case "$a" in y|Y|yes|YES) return 0;; *) return 1;; esac
}

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="${2:?--apk needs a path}"; shift 2 ;;
    --home) DO_HOME=1; shift ;;
    --permissions|--perms) DO_PERMS=1; shift ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    --uninstall) DO_UNINSTALL=1; shift ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option $1 (try --help)" ;;
  esac
done

# --- find a device ---------------------------------------------------------------------------------------------------
# Termux has no USB: the user must already have run `adb connect <tv-ip>:5555`, which is also how a rooted or unrooted
# Fire TV is reached over the network. Report that instead of a bare "no devices".
command -v A >/dev/null 2>&1 || [ "${ADB%% *}" = "adb" ] || die "adb not found. Install it, or set ADB=/path/to/adb"
A start-server >/dev/null 2>&1 || true
DEVICES=$(A devices | awk 'NR>1 && $2=="device" {print $1}')
[ -n "$DEVICES" ] || {
  say "No device. On the TV: Settings -> My Fire TV -> About -> Network, note the IP, then:"
  say "  adb connect <tv-ip>:5555"
  say "(on an unrooted Fire TV, enable Settings -> My Fire TV -> Developer Options -> ADB Debugging first)"
  die "no adb device"; }

if [ "$(printf '%s\n' "$DEVICES" | wc -l)" -gt 1 ] && [ "$ASSUME_YES" = 0 ]; then
  say "More than one device:"; printf '  %s\n' "$DEVICES"
  printf 'Use which? '; if [ -r /dev/tty ]; then read -r pick </dev/tty; else read -r pick; fi
  A -s "$pick" get-state >/dev/null || die "no such device: $pick"
  ADB="$ADB -s $pick"
fi

# Android 9+ (API 28 - Glass's minSdk). A TV reporting less is an old Fire OS and will install but may misbehave.
SDK=$(A shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || echo 0)
case "$SDK" in ''|*[!0-9]*) SDK=0 ;; esac
[ "$SDK" -ge 28 ] || say "warning: Android API $SDK is below VITRE's minSdk 28; it may not start."
say "device ready (API $SDK)"

# --- uninstall -------------------------------------------------------------------------------------------------------
if [ "$DO_UNINSTALL" = 1 ]; then
  A uninstall "$PKG" >/dev/null 2>&1 && say "removed VITRE" || say "VITRE was not installed"
  A uninstall --user 0 "$PKG" >/dev/null 2>&1 || true
  # The stock launcher must come back or the TV has no home screen.
  have_stock && { A shell pm enable com.amazon.tv.launcher >/dev/null 2>&1 && say "re-enabled the stock launcher"; }
  exit 0
fi

# --- get the APK -----------------------------------------------------------------------------------------------------
TMP=""
cleanup() { [ -n "$TMP" ] && rm -rf "$TMP"; }
trap cleanup EXIT
if [ -z "$APK" ]; then
  TMP=$(mktemp -d)
  # The releases *list*, not /releases/latest: while the project is in beta every release is a prerelease, and
  # /latest skips those, so the one-liner would 404 exactly when it is most needed. Newest first, first APK wins.
  # The API needs no auth for a public repo; jq is not assumed (a phone may not have it).
  URL=$(curl -fsSL </dev/null "https://api.github.com/repos/$REPO/releases?per_page=10" \
        </dev/null | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | sed 's/.*"\(https[^"]*\)"/\1/')
  [ -n "$URL" ] || die "no APK in any recent release; pass --apk PATH, or build with scripts/build release"
  say "downloading $(basename "$URL")"
  curl -fL --progress-bar </dev/null "$URL" -o "$TMP/app.apk" || die "download failed"
  APK="$TMP/app.apk"
fi
[ -f "$APK" ] || die "no such APK: $APK"

# An APK signed with a different key cannot update an installed one, and the failure reads as a confusing
# INSTALL_FAILED_UPDATE_INCOMPATIBLE. Say what to do rather than dumping the code.
if present && ! A install -r "$APK" >/dev/null 2>&1; then
  # Signing mismatch: an APK signed with another key cannot update the installed one, and the raw failure reads as an
  # unexplained INSTALL_FAILED_UPDATE_INCOMPATIBLE. Say what it means.
  say "in-place update failed: the installed VITRE is signed with a different key than this APK"
  say "(expected when mixing your own build with a release one - they use different keys)"
  # A system copy (installed as a system app, e.g. by a Magisk module) is the other half: it survives uninstall and
  # has its own signature, so removing the update is not enough.
  # dumpsys, not `pm path`: with an update present, `pm path` lists only the /data copy and hides the /system one.
  if [ "$(A shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | grep -c 'codePath=/system')" -gt 0 ]; then
    say "note: VITRE is also a SYSTEM app here, so it survives uninstall and keeps its own signature -"
    say "      only an APK with the matching key can replace it"
  fi
  ask "Uninstall the installed VITRE and install this one?" || die "left as it was"
  A uninstall "$PKG" >/dev/null 2>&1 || true
  if ! A install "$APK" >/dev/null 2>&1; then
    say "install failed - the key still does not match"
    ensure_home
    say "the stock launcher was left ENABLED, so the TV still has a home screen"
    die "nothing else was changed"
  fi
else
  A install -r "$APK" >/dev/null 2>&1 || A install "$APK" >/dev/null || die "install failed"
fi
# A package previously removed for user 0 stays removed through a plain install; this is what makes it runnable.
reinclude
present && say "installed VITRE" || die "installed but not visible to user 0 (try: adb shell cmd package install-existing $PKG)"

# --- optional: home screen --------------------------------------------------------------------------------------------
if [ "$DO_HOME" = 1 ]; then
  if have_stock; then
    # Fire OS only. Disabling is instant and reversible, and is what makes Glass HOME at boot without a service.
    if ask "Disable the stock Fire TV launcher so VITRE is HOME? (reversible)"; then
      A shell pm disable-user --user 0 com.amazon.tv.launcher >/dev/null && say "stock launcher disabled"
      say "KEEP THIS: to undo, adb shell pm enable com.amazon.tv.launcher"
      say "With the stock launcher disabled and VITRE removed the TV has NO home screen - re-enable it over adb."
    fi
  else
    # Google TV / Android TV: there is no adb command for the default home, so hand over to the picker.
    say "Google TV / Android TV: choose VITRE under Settings -> Home Button -> Make <app> the Default"
    A shell am start -a android.settings.HOME_SETTINGS >/dev/null 2>&1 || true
  fi
  A shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
fi

# --- optional: one-time permissions ----------------------------------------------------------------------------------
# All optional; each only saves a trip through a system menu. Some fail harmlessly on an older Fire OS.
if [ "$DO_PERMS" = 1 ]; then
  g() { A shell "$@" >/dev/null 2>&1 && say "  ok   $*" || say "  skip $*"; }
  say "granting optional permissions"
  g pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
  g appops set "$PKG" GET_USAGE_STATS allow
  g pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
  g appops set "$PKG" PROJECT_MEDIA allow
  g pm grant "$PKG" android.permission.RECORD_AUDIO
  say "Two switches are still manual: Settings -> Notification access (Now Playing), and the optional"
  say "accessibility services (Home Button Takeover, Remote Buttons)."
fi

say ""
say "Done. Open VITRE from the app list, or press Home if you used --home."
say "Settings -> Root shows root-only extras (system app, debloat) when the TV is rooted."
