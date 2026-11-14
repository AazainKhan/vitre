# Testing the no-root Home switch on a rooted stick (P341)

`scripts/stick-noroot-home` runs the self-ADB switch on our rooted stick as if it were a stock Fire, and puts the rooted setup back. Read
the header of the script first; this is the short version.

- `plan` prints every step and touches nothing. `probe` asks only what Fire lets the plain shell user do to its launcher.
  `run` also runs the app's own switch and undo (one prompt on the TV needs a person). `restore` always puts it back.
- It needs a build with `debug.vitre.noroot` (this branch), the stick lock, and Magisk su for the setup and the restore.
- Restore is guaranteed three ways: a trap on exit, error and Ctrl-C; `restore` by hand; and a reboot (`start-launcher.sh`).
  It verifies the end state: Amazon's launcher and home starter disabled, VITRE the HOME activity.

What the result means:

| Result | Meaning |
|---|---|
| `shell-disable.txt` says `disabled-user` | Fire lets the shell user disable its launcher: the main path works, no flash. |
| it throws (SecurityException, "Shell cannot change component state") | Fire refuses: the app falls back to the Home guard (it may flash Amazon's launcher; read `VitreBounce`). |
| `set-home-activity` prints Success but HOME is still Amazon's | Success that changes nothing; the engine's post-check reports it and falls back. |
| the app log says `port 5555 is closed` or the connection times out | loopback adb is not reachable (this stick's glass-adb-wifi module keeps it open, so on a stock Fire this may differ). |

What this cannot show: a stock Fire's own adbd SELinux domain, and whether newer stock builds close loopback adb. A pass proves the app's
flow, the undo and Fire's shell-user policy, not that a stock device accepts loopback adb.
