package dev.glasslauncher.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import java.io.File

/**
 * What Glass can do with root (Settings › Root). Each feature reports its state, applies, and reverts;
 * every action is logged by [Root.action]. Measured effects on the Fire TV Stick 4K are in CLAUDE.md.
 */
object RootFeatures {
    private const val PREFS = "root"

    // ── System app ────────────────────────────────────────────────────────────────────────────────

    private const val SYSTEM_MODULE = "/data/adb/modules/glass-system"

    /** Privileged permissions a system-app Glass uses (on top of everything it already requests). */
    private val EXTRA_PRIVILEGED = listOf(
        "com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA",   // other apps' channels: Continue Watching
        "com.android.providers.tv.permission.ACCESS_WATCHED_PROGRAMS",
    )

    /**
     * The privapp-permissions allowlist for [pkg]: everything it requests plus [EXTRA_PRIVILEGED], that this device
     * [known]s. Fire OS enforces the allowlist (ro.control_privapp_permissions=enforce): a privileged permission
     * that is requested and missing from it stops system_server from booting, so this is the one generator, used
     * by the in-app install; scripts/privapp-check builds the same list from the APK before any `scripts/build`.
     */
    internal fun privappXml(pkg: String, requested: List<String>, known: (String) -> Boolean = { true }): String {
        val perms = (requested + EXTRA_PRIVILEGED).distinct().filter(known).sorted()
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<permissions>")
            appendLine("    <privapp-permissions package=\"$pkg\">")
            perms.forEach { appendLine("        <permission name=\"$it\"/>") }
            appendLine("    </privapp-permissions>")
            appendLine("</permissions>")
        }
    }

    /**
     * default-permissions XML (P98): runtime permissions granted without a prompt. Location is needed for BLE scan
     * results on Android 11, so Glass's own remote pairing (hold Home) sees the remote. Glass OS ships this file in
     * /system/etc/default-permissions; the Pro module carries the same text and service.sh also runs `pm grant`.
     */
    internal fun defaultPermissionsXml(pkg: String): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        appendLine("<exceptions>")
        appendLine("    <exception package=\"$pkg\">")
        appendLine("        <permission name=\"android.permission.ACCESS_FINE_LOCATION\" fixed=\"false\"/>")
        appendLine("        <permission name=\"android.permission.ACCESS_COARSE_LOCATION\" fixed=\"false\"/>")
        appendLine("    </exception>")
        appendLine("</exceptions>")
    }

    enum class SystemApp { Off, Pending, On, Removing }

    fun systemAppState(context: Context): SystemApp {
        val isSystem = (context.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        // The app can't see /data/adb (SELinux), so pending changes are remembered here.
        val prefs = context.getSharedPreferences(PREFS, 0)
        return when {
            isSystem && prefs.getBoolean("systemRemoving", false) -> SystemApp.Removing
            isSystem -> SystemApp.On
            prefs.getBoolean("systemPending", false) -> SystemApp.Pending
            else -> SystemApp.Off
        }
    }

    /**
     * Installs Glass as a privileged system app through a Magisk module (systemless; removing the module
     * reverts it). Fire OS enforces the privileged-permission allowlist, and a missing entry stops the
     * system from booting, so the allowlist lists every permission Glass requests.
     */
    suspend fun installSystemApp(context: Context): Root.Result {
        val pm = context.packageManager
        val requested = runCatching {
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
        }.getOrNull().orEmpty()
        // A permission this Android doesn't define (BLUETOOTH_SCAN on API 30) is left out: it would be ignored, but
        // the allowlist stays honest.
        val xml = privappXml(context.packageName, requested) { name -> runCatching { pm.getPermissionInfo(name, 0) }.isSuccess }
        val apk = context.applicationInfo.sourceDir
        // Boot-time half of a refresh: see assets/glass-system-service.sh.
        val service = context.assets.open("glass-system-service.sh").bufferedReader().use { it.readText() }.trimEnd()
        val script = """
            set -e
            M=$SYSTEM_MODULE
            mkdir -p ${'$'}M/system/priv-app/GlassLauncher ${'$'}M/system/etc/permissions ${'$'}M/system/etc/default-permissions
            cp '$apk' ${'$'}M/system/priv-app/GlassLauncher/GlassLauncher.apk
            cat > ${'$'}M/system/etc/permissions/privapp-permissions-${context.packageName}.xml <<'GLASSXML'
            ${xml.trimEnd()}
            GLASSXML
            cat > ${'$'}M/system/etc/default-permissions/default-permissions-${context.packageName}.xml <<'GLASSDEF'
            ${defaultPermissionsXml(context.packageName).trimEnd()}
            GLASSDEF
            cat > ${'$'}M/service.sh <<'GLASSSVC'
            $service
            GLASSSVC
            chmod 755 ${'$'}M/service.sh
            touch ${'$'}M/uninstall-updates
            printf 'id=glass-system\nname=VITRE as a system app\nversion=1\nversionCode=1\nauthor=VITRE\ndescription=Installs VITRE as a privileged system app (Continue Watching from other apps, secure settings). Remove to revert.\n' > ${'$'}M/module.prop
            chmod 755 ${'$'}M/system/priv-app/GlassLauncher
            chmod 644 ${'$'}M/system/priv-app/GlassLauncher/GlassLauncher.apk ${'$'}M/system/etc/permissions/*.xml ${'$'}M/system/etc/default-permissions/*.xml
            rm -f ${'$'}M/remove ${'$'}M/disable
            # PackageManager's cached parse of the old system APK would outlive the reboot (P275): it is rebuilt at boot.
            rm -rf /data/system/package_cache/*
        """.trimIndent().lines().joinToString("\n") { it.trimStart() }
        val r = Root.action(context, "Install as system app (Magisk module glass-system)", script)
        if (r.ok) context.getSharedPreferences(PREFS, 0).edit().putBoolean("systemPending", true).putBoolean("systemRemoving", false).apply()
        return r
    }

    suspend fun removeSystemApp(context: Context): Root.Result {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("systemPending", false).putBoolean("systemRemoving", true).apply()
        return Root.action(context, "Remove system app (module glass-system, on restart)", "touch $SYSTEM_MODULE/remove 2>/dev/null; true")
    }

    // ── Own remote pairing (P75 B) ───────────────────────────────────────────────────────────────

    /**
     * Fire's AutoPairService unpairs a sleeping remote when its 60 s timer fires. With
     * `persist.sys.auto_pair_sys_service=false` it never starts; Glass pairs the remote itself ([RemotePairer]).
     * Both halves move together (the property, and Glass's own pairing), after a restart for the property.
     */
    fun ownRemotePairing(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean("ownRemotePairing", false)

    suspend fun setOwnRemotePairing(context: Context, on: Boolean): Root.Result {
        val r = Root.action(
            context, "Own remote pairing ${if (on) "on" else "off"} (Fire's auto-pair ${if (on) "off" else "back"} after a restart)",
            "setprop persist.sys.auto_pair_sys_service ${if (on) "false" else "true"} || resetprop -p persist.sys.auto_pair_sys_service ${if (on) "false" else "true"}",
        )
        if (r.ok) context.getSharedPreferences(PREFS, 0).edit().putBoolean("ownRemotePairing", on).apply()
        return r
    }

    // ── Home takeover ─────────────────────────────────────────────────────────────────────────────

    // The stock Home screens are found at run time (P155): the system apps that answer the HOME intent, plus the known stock launchers
    // (Fire's and its home starter, Google TV's, the leanback one) that are installed, since a disabled one no longer answers it.
    private val KNOWN_STOCK_HOME = HomeSetup.STOCK_LAUNCHERS + "com.amazon.firehomestarter"

    // The second guard: whatever detection says, a settings or setup package is never in the list (disabling Fire Settings
    // hung the stick at boot, 2026-10-14).
    private fun stockHome(context: Context): List<String> =
        (DeviceProfile.stockHomes(context).map { it.packageName } + DeviceProfile.present(context.packageManager, KNOWN_STOCK_HOME.toList()))
            .distinct().filterNot { DeviceProfile.neverDisable(it) }

    /** The shell that hands Home back to [pkg]: enable it, then ask the system which activity answers HOME for it. */
    private fun restoreHome(pkg: String) = "pm enable $pkg\nc=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $pkg | tail -n 1)\n[ -n \"\$c\" ] && cmd package set-home-activity \"\$c\" || true"

    /** True where there is a stock Home screen to turn off (Settings hides Home Takeover otherwise). */
    fun hasStockHome(context: Context): Boolean = stockHome(context).isNotEmpty()

    fun homeTakeoverOn(context: Context): Boolean =
        HomeSetup.isDefaultHome(context) && stockHome(context).none { isEnabled(context, it) }

    private fun isEnabled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    }.getOrDefault(false)

    /**
     * Keeps Glass as Home: Amazon's launcher came back on once outside any store visit and took over Home.
     * Called whenever Glass comes forward; does nothing unless that launcher is on while no store visit is
     * open and Home Takeover hasn't been turned off.
     */
    suspend fun keepHome(context: Context) {
        if (!context.getSharedPreferences(PREFS, 0).getBoolean("takeover", true)) return
        if (AmazonStore.isOpen(context) || stockHome(context).none { isEnabled(context, it) }) return
        if (!Root.available()) return
        Root.action(context, "The stock launcher was on: VITRE back as Home", stockHome(context).joinToString("\n") { "pm disable-user --user 0 $it" } +
            "\ncmd package set-home-activity ${context.packageName}/.MainActivity")
    }

    // ── The remote-keys accessibility service must always be on (P87) ────────────────────────────

    /** The enabled-services setting with [service] in it (pure, tested): unchanged when it already is. */
    fun withService(current: String?, service: String): String = when {
        current.isNullOrBlank() || current == "null" -> service
        current.split(':').any { it == service } -> current
        else -> "$current:$service"
    }

    suspend fun setHomeTakeover(context: Context, on: Boolean): Root.Result = (if (on) {
        Root.action(context, "Home takeover on", stockHome(context).joinToString("\n") { "pm disable-user --user 0 $it" } +
            "\ncmd package set-home-activity ${context.packageName}/.MainActivity")
    } else {
        Root.action(context, "Home takeover off (stock launcher back)", stockHome(context).joinToString("\n") { "pm enable $it" } +
            (stockHome(context).firstOrNull()?.let { "\n" + restoreHome(it) } ?: ""))
    }).also { context.getSharedPreferences(PREFS, 0).edit().putBoolean("takeover", on).apply() }

    // ── Performance profile ───────────────────────────────────────────────────────────────────────

    /**
     * Fast (P149): measured on the stick, none of the old profile's writes earned a number. The CPU minimum is already
     * pinned at its 2.0 GHz top from boot (Fast's 1.3 GHz write lowered it), the GPU minimum (850 vs 650 MHz) didn't
     * move p99, and zeroed system animations fought Glass's own launch and close. So Fast now frees memory once (the
     * cached apps are killed, as Free Memory does) and changes nothing that persists; Balanced does nothing.
     */
    fun fast(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean("fast", false)

    suspend fun setFast(context: Context, on: Boolean): Root.Result {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("fast", on).apply()
        if (on) freeMemory(context)
        return Root.Result(0, "")
    }

    /** At start: the Fire OS Home-key flash off, and anything the old Fast profile left behind undone (once). */
    suspend fun reapplyAtStart(context: Context) {
        if (!Root.available()) return
        // Fire OS turns its Home-key black flash (SystemUI KeyFeedbackUI) back on at every boot; it ran
        // over Glass's app-close animation. Found by e2e/test_device_setup.py after a restart.
        // Only where the property exists (Fire OS): elsewhere it would create one nothing reads.
        Root.run("p=\"$(getprop persist.perfanimation.enabled)\"; [ -z \"\$p\" ] || [ \"\$p\" = false ] || setprop persist.perfanimation.enabled false")
        val prefs = context.getSharedPreferences(PREFS, 0)
        if (!prefs.getBoolean("fastV2", false)) {
            // The old Fast profile set system animations to 0; Glass's motion is tuned to 1.0. Put them back once, always
            // (P298: Speed is gone as a toggle, so nothing gates this any more).
            Root.action(context, "Performance: system animations back to 1.0", "settings put global window_animation_scale 1.0\nsettings put global transition_animation_scale 1.0\nsettings put global animator_duration_scale 1.0")
            prefs.edit().putBoolean("fastV2", true).apply()
        }
    }

    fun systemAnimationsOff(context: Context): Boolean =
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE) == 0f }.getOrDefault(false)

    // ── Memory tuning ─────────────────────────────────────────────────────────────────────────────

    private const val TUNING_MODULE = "/data/adb/modules/glass-tuning"

    suspend fun memoryTuningOn(): Boolean = Root.run("test -d $TUNING_MODULE && ! test -e $TUNING_MODULE/remove").ok

    /** Same as scripts/tuning: 1.2 GB zram, least-recently-used-first lmkd, 12 cached apps (restart to apply). */
    suspend fun setMemoryTuning(context: Context, on: Boolean): Root.Result = if (on) {
        Root.action(context, "Memory tuning on (module glass-tuning)", """
            set -e
            M=$TUNING_MODULE
            mkdir -p ${'$'}M/system/vendor/etc
            echo '/dev/block/zram0 none swap defaults zramsize=1288490188' > ${'$'}M/system/vendor/etc/fstab.enableswap
            cat > ${'$'}M/service.sh <<'GLASSSH'
            resetprop ro.lmk.kill_heaviest_task false
            resetprop ro.lmk.psi_partial_stall_ms 300
            resetprop ro.lmk.thrashing_limit 200
            resetprop ro.lmk.swap_free_low_percentage 3
            until [ "${'$'}(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
            stop lmkd; start lmkd
            settings delete global activity_manager_constants
            device_config put activity_manager max_cached_processes 12
            GLASSSH
            printf 'id=glass-tuning\nname=VITRE Memory Tuning\nversion=1\nversionCode=1\nauthor=VITRE\ndescription=1.2 GB zram, LRU-first low-memory killer, 12 cached apps. Remove to restore Fire OS defaults.\n' > ${'$'}M/module.prop
            chmod 755 ${'$'}M/service.sh; chmod 644 ${'$'}M/system/vendor/etc/fstab.enableswap
            rm -f ${'$'}M/remove ${'$'}M/disable
        """.trimIndent().lines().joinToString("\n") { it.trimStart() })
    } else {
        Root.action(context, "Memory tuning off (module glass-tuning, on restart)",
            "touch $TUNING_MODULE/remove 2>/dev/null; device_config delete activity_manager max_cached_processes; settings delete global activity_manager_constants")
    }

    // ── Free memory ───────────────────────────────────────────────────────────────────────────────

    /**
     * Ends background apps (the system's own "kill all background processes"): anything playing or
     * otherwise perceptible keeps running. Returns the memory freed, in MB.
     */
    suspend fun freeMemory(context: Context): Int {
        val before = availableMb()
        if (Root.known || Root.available()) Root.action(context, "Free memory", "am kill-all")
        else killBackground(context)
        kotlinx.coroutines.delay(600)
        return (availableMb() - before).coerceAtLeast(0)
    }

    /**
     * No root: ask the system to end the cached background processes of every launchable app but Glass
     * (KILL_BACKGROUND_PROCESSES is a normal permission). Anything playing or otherwise perceptible keeps running.
     */
    private fun killBackground(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val launchers = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
        context.packageManager.queryIntentActivities(launchers, 0)
            .map { it.activityInfo.packageName }.distinct().filter { it != context.packageName }
            .forEach { runCatching { am.killBackgroundProcesses(it) } }
    }

    /** Restart: root `svc power reboot`; a system app PowerManager.reboot (REBOOT); else false (the caller opens Fire's settings). */
    suspend fun reboot(context: Context): Boolean {
        if (Root.known || Root.available()) {
            // Logged first: the reboot ends Glass before Root.action could record it, so a restart Glass asked for
            // left no trace (P149: two unexplained "reboot,shell" restarts).
            Root.log(context, "Restart: requested")
            return Root.action(context, "Restart", "svc power reboot || reboot").ok
        }
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            android.os.PowerManager::class.java.getMethod("reboot", String::class.java).invoke(pm, null)
            true
        }.getOrDefault(false)
    }

    /** What Clean Up freed: bulk caches in MB (root `pm trim-caches`, system app freeStorageAndNotify; none otherwise) and memory in MB. */
    data class CleanUp(val cachesMb: Int, val memoryMb: Int, val bulkCaches: Boolean)

    suspend fun cleanUp(context: Context): CleanUp {
        val memory = freeMemory(context)
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val before = stat.availableBytes
        var bulk = false
        if (Root.known || Root.available()) {
            bulk = Root.action(context, "Clean up: trim caches", "pm trim-caches 999G").ok
        } else {
            bulk = runCatching {
                val pm = context.packageManager
                val m = android.content.pm.PackageManager::class.java.getMethod(
                    "freeStorageAndNotify", String::class.java, Long::class.javaPrimitiveType, Class.forName("android.content.pm.IPackageDataObserver"),
                )
                m.invoke(pm, null, Long.MAX_VALUE, null)
                true
            }.getOrDefault(false)
        }
        kotlinx.coroutines.delay(800)
        stat.restat(android.os.Environment.getDataDirectory().path)
        val freed = ((stat.availableBytes - before).coerceAtLeast(0) / (1024 * 1024)).toInt()
        return CleanUp(freed, memory, bulk)
    }

    fun availableMb(): Int = runCatching {
        File("/proc/meminfo").readLines().first { it.startsWith("MemAvailable:") }.split(Regex("\\s+"))[1].toInt() / 1024
    }.getOrDefault(0)

    // ── App freezer ───────────────────────────────────────────────────────────────────────────────

    data class Freezable(val pkg: String, val label: String, val note: String)

    /** Fire's background services that are safe to turn off; [freezableHere] is the ones this device has. */
    val freezable = listOf(
        Freezable("com.amazon.device.software.ota", "System Updates", "OTA updates can unroot or brick a rooted stick"),
        Freezable("com.amazon.device.software.ota.override", "Update Override", "Part of system updates"),
        Freezable("com.amazon.tv.forcedotaupdater.v2", "Forced Updater", "Part of system updates"),
        Freezable("com.amazon.device.metrics", "Device Metrics", "Usage metrics"),
        Freezable("com.amazon.wirelessmetrics.service", "Wireless Metrics", "Wi-Fi metrics"),
        Freezable("com.amazon.device.telemetry.emitter", "Telemetry", "Telemetry upload"),
        Freezable("com.amazon.neo.minerva", "Minerva Metrics", "Metrics"),
        Freezable("com.amazon.device.crashmanager", "Crash Reports", "Crash report upload"),
        Freezable("com.amazon.ftvads.deeplinking", "Ad Deep Links", "Ads"),
        Freezable("com.amazon.media.recommendations", "Recommendations", "For the stock launcher"),
        Freezable("com.amazon.whisperjoin.middleware.v2.np", "Device Setup", "Wi-Fi setup of other Amazon devices"),
        Freezable("com.amazon.whad", "Whole-Home Audio", "Multi-room Echo groups"),
        Freezable("com.amazon.venezia", "Appstore", "Turning it off also stops app updates from it"),
        Freezable("com.amazon.firebat", "Prime Video", "~95 MB in the background; also the remote's Prime Video button"),
    )

    /** The rows of [freezable] whose package this device has (a non-Fire box lists none). */
    fun freezableHere(context: Context): List<Freezable> = freezable.filter { DeviceProfile.installed(context.packageManager, it.pkg) }

    suspend fun frozen(): Set<String> =
        Root.run("pm list packages -d").out.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()

    suspend fun setFrozen(context: Context, pkg: String, frozen: Boolean): Root.Result =
        Root.action(context, "${if (frozen) "Froze" else "Unfroze"} $pkg", if (frozen) "pm disable-user --user 0 $pkg" else "pm enable $pkg")

    suspend fun restart(context: Context) { Root.log(context, "Restart: requested"); Root.action(context, "Restart", "svc power reboot || reboot") }
}
