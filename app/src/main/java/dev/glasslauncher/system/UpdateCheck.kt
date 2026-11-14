package dev.glasslauncher.system

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest
import okhttp3.OkHttpClient

/**
 * The weekly update check (P340). It runs only while Home is in front, quiet for a minute, with no music playing: never during
 * playback, never on a focus move, never at boot. It fetches one small JSON from GitHub, stores the newer version (if any) and stops;
 * the install itself is always the user's choice and goes through the system installer, which asks.
 */
object UpdateCheck {
    const val EVERY_MS = 7 * 24 * 3_600_000L
    /** No key for this long before a check may start. */
    const val QUIET_MS = 60_000L
    private const val PREFS = "updates"

    /** Pure rule (tested): a check is due when none ran yet, a week passed, or the clock went back past the last one. */
    fun due(lastMs: Long, now: Long): Boolean = lastMs == 0L || now < lastMs || now - lastMs >= EVERY_MS

    /** Pure rule (tested): milliseconds until the next check is due (0 if due now). */
    fun untilDue(lastMs: Long, now: Long): Long = if (due(lastMs, now)) 0L else lastMs + EVERY_MS - now

    /** Pure rule (tested): when a due check may run. */
    fun mayRun(homeInFront: Boolean, sinceInputMs: Long, musicPlaying: Boolean): Boolean = homeInFront && !musicPlaying && sinceInputMs >= QUIET_MS

    fun lastCheck(context: Context): Long = context.getSharedPreferences(PREFS, 0).getLong("last", 0L)

    /** The newer version found by the last check, or null. */
    fun available(context: Context): String? =
        context.getSharedPreferences(PREFS, 0).getString("available", null)?.takeIf { Updater.isNewer(it) }

    /** One check: records the time and the newer version (cleared when up to date). A network failure leaves the clock alone so it tries again soon. */
    suspend fun run(context: Context, http: OkHttpClient, now: Long = System.currentTimeMillis()): String? {
        val latest = runCatching { Updater.latest(http) }.getOrNull() ?: return null
        val newer = latest.version.takeIf { Updater.isNewer(it) }
        context.getSharedPreferences(PREFS, 0).edit().putLong("last", now).apply { if (newer != null) putString("available", newer) else remove("available") }.apply()
        return newer
    }

    /** Pure rule (tested): the same signer, by certificate digest; never true for an unsigned or unreadable APK. */
    internal fun sameSigner(installed: Set<String>, candidate: Set<String>): Boolean = installed.isNotEmpty() && installed == candidate

    /** The SHA-256 digests of the certificates [apk] is signed with, and of the installed app: they must match before anything is installed. */
    fun signerMatches(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        fun digests(signers: Array<android.content.pm.Signature>?): Set<String> =
            signers.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        val mine = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        val theirs = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo?.apkContentsSigners
        sameSigner(digests(mine), digests(theirs))
    }.getOrDefault(false)

    /** Pure rule (tested): the download is as long as GitHub says (when it says). */
    internal fun sizeOk(want: Long?, actual: Long) = want == null || want <= 0 || want == actual
}
