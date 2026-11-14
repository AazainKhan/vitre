# Testing the no-root Home switch on a rooted stick (P341)

`scripts/stick-noroot-home` runs the self-ADB switch on our rooted stick as if it were a stock Fire, and puts the rooted setup back. Read
the header of the script first; this is the short version.

- `plan` prints every step and touches nothing. `probe` asks only what Fire lets the plain shell user do to its launcher.
  `run` also runs the app's own switch and undo (one prompt on the TV needs a person). `restore` always puts it back.
- It needs a build with `debug.vitre.noroot` (this branch), the stick lock, and Magisk su for the setup and the restore.
- The stick's Amazon launcher is uninstalled for user 0 (installed=false), not just disabled. The run brings back ONLY the launcher
  (`cmd package install-existing --user 0`) as the stock stand-in, and the restore disables it and uninstalls it again (`pm uninstall -k --user 0`,
  data kept), then checks installed=false. The home starter (`com.amazon.firehomestarter`) is never touched: its state is recorded first and
  verified unchanged at the end, so no setup or remote-pairing screen can start.
- Making the launcher the home needs care: `install-existing` alone left `set-home-activity` answering "Failed to set default home" on the stick, so
  the script lists the HOME candidates (saved to `reports/noroot-home/home-candidates.txt`), uses the exact component listed for the launcher, sets it,
  reads who answers HOME, starts the launcher once if that is not Amazon's, and retries. If it never becomes the home, the run says so and the
  set-home half of probe A is marked as proving nothing (the disable-user half still does).
- Scope on this stick: `ro.adb.secure=0` and there is no `adb_keys`, so it never shows "Allow USB debugging?" and adbd accepts any client with
  no signature. `probe` (Fire OS 8's policy for the plain shell user) is what this stick can prove. `run` also exercises the app's engine over
  loopback, but the prompt and signature path of SelfAdb is not proven here (the emulator covers it): **Fire prompt path unverified**.
- The run leaves no self-ADB channel: restore removes the app's own key from `/data/misc/adb/adb_keys` as root (matched by its content, every
  other key left alone, verified gone, the original put back if the edit is not clean), and edits the framework's text key record when there is
  one; a reboot clears the framework's in-memory copy. adbd reads `adb_keys` on every connection, so removing it revokes the channel at once.
- Restore is guaranteed three ways: a trap on exit, error and Ctrl-C; `restore` by hand; and a reboot (`start-launcher.sh`).
  It verifies the end state: Amazon's launcher uninstalled for user 0, the home starter as it was, VITRE the HOME activity, and the app's key gone.

What the result means:

| Result | Meaning |
|---|---|
| `shell-disable.txt` says `disabled-user` | Fire lets the shell user disable its launcher: the main path works, no flash. |
| it throws (SecurityException, "Shell cannot change component state") | Fire refuses: the app falls back to the Home guard (it may flash Amazon's launcher; read `VitreBounce`). |
| `set-home-activity` prints Success but HOME is still Amazon's | Success that changes nothing; the engine's post-check reports it and falls back. |
| the app log says `port 5555 is closed` or the connection times out | loopback adb is not reachable (this stick's glass-adb-wifi module keeps it open, so on a stock Fire this may differ). |

What this cannot show: a stock Fire's own adbd SELinux domain, and whether newer stock builds close loopback adb. A pass proves the app's
flow, the undo and Fire's shell-user policy, not that a stock device accepts loopback adb.
