package dev.glasslauncher.system

// The switch and undo flow is ported to Kotlin from Free My TV's HomeMode.java (applyFree, applyOriginal, restoreHome, wifiOwners,
// neededByTv; github.com/simbasjungles/free-my-tv, commit 0e33f16, MIT, Copyright (c) 2026 Free My TV contributors).
// The MIT text is in licenses/free-my-tv-MIT.txt and NOTICE. Left out on purpose: speed/memory measuring, JmLauncher, phone and
// Huawei checks, the screensaver swap (Settings has its own), the watch-next and now-playing grants.

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

/** The result of a switch or an undo: [message] is what Settings shows; [needsGuard] asks the caller to fall back to the window-watching Home guard. */
data class SwitchOutcome(val ok: Boolean, val message: String, val needsGuard: Boolean = false)

/**
 * Makes VITRE the Home screen without root (P341): the app connects to its own TV over [SelfAdb], records what was there, sets the home
 * activity and turns the stock home off, and "Original" ([restore]) puts it all back. The engine is pure over a [TvShell], so JVM tests
 * run it against a fake one. It never runs at boot or while idle; [HomeSwitch] opens a connection for one switch or undo and closes it.
 */
internal class SwitchEngine(
    private val pkg: String,
    private val fire: Boolean,
    private val stockHomes: List<String>,
    private val prefs: SharedPreferences,
) {
    private val home get() = "$pkg/.MainActivity"
    val isOn: Boolean get() = prefs.getBoolean("on", false)
    val disabled: Set<String> get() = prefs.getStringSet("disabled", emptySet()).orEmpty()

    fun switchOn(sh: TvShell): SwitchOutcome {
        // One-time: lets VITRE turn its own accessibility services on and set the screensaver, so Settings never says "grant from a computer".
        runCatching { sh.run("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS") }
        val present = packages(sh, "")
        val off = packages(sh, "-d")
        val originalHome = prefs.getString("originalHome", "").orEmpty().takeIf { it.isNotEmpty() && !it.startsWith("$pkg/") } ?: currentHome(sh)
        val targets = (stockHomes + (if (fire) HomeSafety.FIRE_HOME else emptyList()) + HomeSafety.DEBLOAT)
            .distinct().filter { it in present && it !in off && it != pkg && HomeSafety.mayTurnOff(it, fire) }
        // Turning an app off makes Android delete the Wi-Fi networks it saved, and no undo brings them back: stop before anything changes.
        val owners = wifiOwners(sh)
            ?: if (sh.run("settings get global wifi_on").trim() == "0") emptySet() else return SwitchOutcome(false, "I couldn't check which app saved the Wi-Fi, so nothing changed. Try again in a minute.")
        owners.firstOrNull { it in targets }?.let {
            return SwitchOutcome(false, "$it saved this TV's Wi-Fi network, and turning it off would erase it. In Settings, Network, forget the network and connect again, then try once more.")
        }
        // Inputs and the remote's Input key must keep working: a package that asks for TV_INPUT_HARDWARE stays on (HDMI says "No signal" otherwise).
        val needed = neededByTv(sh, targets) ?: return SwitchOutcome(false, "I couldn't check what this TV's inputs need, so nothing changed. Try again in a minute.")
        prefs.edit().putString("originalHome", originalHome).apply()
        if (!sh.run("cmd package set-home-activity $home").contains("Success")) return SwitchOutcome(false, "Android didn't accept VITRE as the home screen.")
        val done = disabled.toMutableSet()
        var fireRefused = false
        for (t in targets) {
            if (t in needed) continue
            if (sh.run("pm disable-user --user 0 $t").contains("disabled-user")) {
                done += t
                prefs.edit().putStringSet("disabled", done).apply() // after each one, so an interrupted switch can still be undone
            } else if (fire && t in HomeSafety.FIRE_HOME) fireRefused = true
        }
        prefs.edit().putBoolean("on", true).apply()
        // `set-home-activity` can print "Success" and change nothing (reported on Fire OS 8): trust only who answers Home now.
        val inCharge = holder(sh)
        if (inCharge.isNotEmpty() && inCharge != pkg) {
            return SwitchOutcome(true, "Android said yes, but ${inCharge.substringBefore(';')} still answers the Home button, so VITRE watches for it and takes Home back (it may flash for a moment).", needsGuard = true)
        }
        return if (fireRefused) SwitchOutcome(true, "Fire OS wouldn't turn off its home screen without root, so VITRE watches for it and takes Home back (it may flash for a moment).", needsGuard = true)
        else SwitchOutcome(true, "VITRE is your home screen. ${done.size} other app${if (done.size == 1) " is" else "s are"} turned off; Original turns them back on.")
    }

    /** Original: everything [switchOn] turned off comes back on, then the TV's own home screen is the default again. */
    fun restore(sh: TvShell): SwitchOutcome {
        val originalHome = HomeSafety.safeComponent(prefs.getString("originalHome", "").orEmpty())
        val order = disabled.sorted().toMutableList()
        val homePkg = HomeSafety.packageOf(originalHome)
        if (homePkg.isNotEmpty() && order.remove(homePkg)) order.add(0, homePkg) // the TV's home first, so Home never lands nowhere
        val failed = order.filterNot { sh.run("pm enable $it").contains("new state: enabled") }.toMutableList()
        val homeOk = restoreHome(sh, originalHome)
        prefs.edit().putStringSet("disabled", failed.toSet()).putBoolean("on", false).apply()
        return if (failed.isEmpty() && homeOk) SwitchOutcome(true, "The original home screen and apps are back.")
        else SwitchOutcome(false, "Original is on, but I couldn't turn back on: ${(failed + if (homeOk) emptyList() else listOf("the original home screen")).joinToString(", ")}.")
    }

    /** The recorded home screen; without one the TV's own (exactly one system home that isn't ours); last resort: VITRE leaves the Home role. */
    private fun restoreHome(sh: TvShell, recorded: String): Boolean {
        val candidates = listOf(recorded).filter { it.isNotEmpty() } + stockHomes.mapNotNull { componentOf(sh, it) }
        for (c in candidates.distinct()) {
            sh.run("pm enable ${HomeSafety.packageOf(c)}")
            if (sh.run("cmd package set-home-activity '$c'").contains("Success")) return true
        }
        sh.run("cmd role remove-role-holder --user 0 android.app.role.HOME $pkg")
        return !holder(sh).contains(pkg)
    }

    /** The package that answers Home now, or "". Some Android 11 and 12 TVs have no `cmd role get-role-holders` ("Unknown command"), so then ask which activity opens HOME. */
    private fun holder(sh: TvShell): String {
        val role = sh.run("cmd role get-role-holders --user 0 android.app.role.HOME").trim()
        if (role.matches(Regex("[\\w.]+(;[\\w.]+)*"))) return role
        val component = sh.run("cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1").trim()
        // With no default home yet the system's chooser answers ("android/...ResolverActivity"): unknown.
        return if (component.contains('/') && !component.startsWith("android/")) HomeSafety.packageOf(component) else ""
    }

    /** The home screen now in charge, as "package/class", or "" when there is none to read or it is ours. */
    private fun currentHome(sh: TvShell): String {
        val h = holder(sh).substringBefore(';')
        val from = if (h.isNotEmpty() && h != pkg) h else stockHomes.firstOrNull().orEmpty()
        return if (from.isEmpty()) "" else componentOf(sh, from).orEmpty()
    }

    private fun componentOf(sh: TvShell, p: String): String? =
        HomeSafety.safeComponent(sh.run("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $p | tail -n 1").trim()).takeIf { it.isNotEmpty() }

    private fun packages(sh: TvShell, flags: String): Set<String> =
        sh.run("pm list packages $flags").lines().mapNotNull { it.trim().removePrefix("package:").takeIf { p -> p.isNotEmpty() && !p.contains(' ') } }.toSet()

    /** Apps that saved a Wi-Fi network, or null when the Wi-Fi service didn't answer (some firmware turns its dumps off). */
    private fun wifiOwners(sh: TvShell): Set<String>? {
        val out = sh.run("dumpsys wifi | grep -o -E 'cname=[^ ]*|^Wi-Fi is [a-z]+'")
        if (!out.contains("cname=") && !out.contains("Wi-Fi is")) return null
        return Regex("cname=([\\w.]+)").findAll(out).map { it.groupValues[1] }.toSet()
    }

    /** Of [pkgs], the ones the TV still needs (TV inputs, the remote's Input key); null when the check didn't finish: then nothing goes off. */
    private fun neededByTv(sh: TvShell, pkgs: List<String>): Set<String>? {
        if (pkgs.isEmpty()) return emptySet()
        val out = sh.run("for p in ${pkgs.sorted().joinToString(" ")}; do d=\"$(dumpsys package \$p)\"; " +
            "if ! printf '%s\\n' \"\$d\" | grep -q -F \"Package [\$p]\"; then echo \"unknown \$p\"; " +
            "elif printf '%s\\n' \"\$d\" | sed -n -r '/requested permissions:/,/(install|runtime) permissions:|User [0-9]+:/p'" +
            " | grep -q TV_INPUT_HARDWARE || printf '%s\\n' \"\$d\" | grep -q -E 'TV_INPUT_BUTTON|CONNECT_INPUT|AddAccessory';" +
            " then echo \"keep \$p\"; else echo \"- \$p\"; fi; done; echo checked")
        if (!out.contains("checked") || out.contains("unknown ")) return null
        return Regex("keep ([\\w.]+)").findAll(out).map { it.groupValues[1] }.toSet()
    }
}

/** The Home switch as the rest of the app sees it: which path this install has, and one-shot switch/undo on a background thread. */
object HomeSwitch {
    enum class Path { Root, SelfAdb, None }

    /** Root or Glass Pro keep the root path (RootFeatures); a system-app install (Glass OS) is already Home; everything else uses self-ADB. */
    /** A test switch (`setprop debug.vitre.noroot 1`): act as a device without root even on a rooted one, to run the self-ADB path on the stick (docs/p341-no-root-test.md). */
    fun noRootForced(): Boolean = dev.glasslauncher.dream.viz.VizGlView.prop("debug.vitre.noroot")

    fun path(context: Context): Path = when {
        noRootForced() -> Path.SelfAdb
        Root.known -> Path.Root
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 -> Path.None
        else -> Path.SelfAdb
    }

    private fun engine(context: Context) = SwitchEngine(
        context.packageName, HomeSetup.isFireTv, DeviceProfile.stockHomes(context).map { it.packageName },
        context.getSharedPreferences("homeswitch", 0),
    )

    fun isOn(context: Context): Boolean = engine(context).isOn

    /** Runs [action] on its own thread (never the UI thread) with a fresh self-ADB connection that is closed after; [onWait] says the TV's "Allow USB debugging?" prompt is up. */
    fun run(context: Context, restore: Boolean, onWait: () -> Unit = {}, done: (SwitchOutcome) -> Unit) {
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val outcome = try {
                SelfAdb.connect(app, approval = { main.post(onWait) }).use { adb -> engine(app).let { if (restore) it.restore(adb) else it.switchOn(adb) } }
            } catch (e: IOException) {
                SwitchOutcome(false, "Couldn't reach the TV's own debugging: ${e.message}." + if (e.message.orEmpty().contains("is off")) " Turn on USB debugging in Developer options." else "")
            }
            if (!restore && outcome.needsGuard) runCatching { HomeSetup.setGuardEnabled(app, true) }
            android.util.Log.i("VitreHomeSwitch", "${if (restore) "restore" else "switch"}: ${outcome.ok} ${outcome.message}")
            main.post { done(outcome) } // results reach Compose state on the UI thread
        }, "VitreHomeSwitch").start()
    }
}
