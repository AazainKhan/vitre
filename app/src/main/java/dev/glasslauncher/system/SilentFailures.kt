package dev.glasslauncher.system

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import dev.glasslauncher.data.FeaturedSourceId

/**
 * P214: three things that used to fail without a word, each a single line through [RemoteNotice] (Glass's banner, or a toast
 * where its service is off), shown at most once per period. The rules are pure and unit-tested.
 */
object Notices {
    const val DAY_MS = 24 * 60 * 60_000L

    /** Whether a notice last shown at [lastMs] (0 = never) may be shown again at [now]. A clock set back counts as due. */
    fun due(lastMs: Long, now: Long, everyMs: Long) = lastMs == 0L || now < lastMs || now - lastMs >= everyMs

    /** True, and remembered, when [key] has not been shown within [everyMs]. */
    @Synchronized fun dueNow(context: Context, key: String, everyMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.applicationContext.getSharedPreferences("notices", Context.MODE_PRIVATE)
        if (!due(prefs.getLong(key, 0L), now, everyMs)) return false
        prefs.edit().putLong(key, now).apply()
        return true
    }
}

/** Storage nearly full: downloads, config saves and tile art all fail quietly when /data is out of space. */
object StorageWatch {
    const val LOW_BYTES = 500L * 1024 * 1024

    fun low(freeBytes: Long) = freeBytes in 0 until LOW_BYTES

    fun line(freeBytes: Long) = "Storage is almost full: ${freeBytes / (1024 * 1024)} MB left. Remove apps or downloads to free space."

    /** What an app can still use: free space plus the cache the system would clear for it. */
    fun freeBytes(context: Context): Long = runCatching {
        val dir = Environment.getDataDirectory()
        val manager = context.getSystemService(StorageManager::class.java)
        manager.getAllocatableBytes(manager.getUuidForPath(dir))
    }.getOrElse { runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrDefault(-1L) }

    fun check(context: Context) {
        val free = freeBytes(context)
        if (low(free) && Notices.dueNow(context, "low-storage", Notices.DAY_MS)) RemoteNotice.show(context, line(free), forMs = 6_000)
    }
}

/** A feed's saved sign-in was refused (HTTP 401): the shelf just stays empty otherwise. */
object CredentialNotice {
    private fun nameOf(source: FeaturedSourceId) = if (source == FeaturedSourceId.Tmdb) "TMDB" else source.name

    fun line(source: FeaturedSourceId) = "${nameOf(source)} rejected its saved sign-in. Sign in again in Settings."

    fun expired(context: Context, source: FeaturedSourceId) {
        if (Notices.dueNow(context, "credentials:${source.name}", Notices.DAY_MS)) RemoteNotice.show(context, line(source), forMs = 6_000)
    }
}

/** After an unexpected restart: Android's own record of how the last process ended (API 30+). */
object CrashRecovery {
    const val RECENT_MS = 10 * 60_000L

    private val UNEXPECTED = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)

    /** A crash or ANR that ended the last process, newer than the last one reported, and recent enough to be why Glass just started. */
    fun shouldNotify(reason: Int, timestamp: Long, lastSeen: Long, now: Long) =
        reason in UNEXPECTED && timestamp > lastSeen && now - timestamp in 0..RECENT_MS

    private const val SEEN = "exit-seen"
    private const val PENDING = "exit-pending"
    private const val LINE = "VITRE restarted after it stopped unexpectedly."

    /**
     * At start: reads how the last process ended. A recent crash is kept as pending, not shown: Glass is not in front yet, and Fire's
     * notification service kills a toast from a background app (P214 on the stick). [showPending] says it once Home is up. Any other
     * exit is consumed.
     */
    fun check(context: Context, now: Long = System.currentTimeMillis()) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val newest = runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return
        record(context, newest.reason, newest.timestamp, now)
        // Home was already up when the check finished (the check runs off the main thread, at start-up).
        if (dev.glasslauncher.system.RemoteWatch.homeInFront) showPending(context)
    }

    /** What to do with an exit: consumed, or pending until it is shown. */
    internal fun record(context: Context, reason: Int, timestamp: Long, now: Long) {
        val prefs = context.applicationContext.getSharedPreferences("notices", Context.MODE_PRIVATE)
        if (timestamp <= prefs.getLong(SEEN, 0L)) return
        if (shouldNotify(reason, timestamp, prefs.getLong(SEEN, 0L), now)) prefs.edit().putLong(PENDING, timestamp).apply()
        else prefs.edit().putLong(SEEN, timestamp).apply()
    }

    /** Home is in front: says the pending notice once, and only then marks that exit as seen, so a notice that could not be shown is tried again. */
    @Synchronized fun showPending(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("notices", Context.MODE_PRIVATE)
        val pending = prefs.getLong(PENDING, 0L)
        if (pending == 0L) return
        // Held back (Do Not Disturb): tried again at the next resume, but not for ever.
        if (RemoteNotice.show(context, LINE, forMs = 5_000) == RemoteNotice.Outcome.Suppressed && System.currentTimeMillis() - pending < Notices.DAY_MS) return
        prefs.edit().putLong(SEEN, pending).remove(PENDING).apply()
    }
}
