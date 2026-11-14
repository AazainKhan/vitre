package dev.glasslauncher.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import dev.glasslauncher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** [sha256] is GitHub's asset digest when it publishes one. */
data class Release(val version: String, val apkUrl: String, val notes: String, val sha256: String? = null, val size: Long? = null)

/** In-app updates from GitHub Releases, installed through the system PackageInstaller. */
object Updater {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latest(http: OkHttpClient): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            val o = json.parseToJsonElement(r.body.string()).jsonObject
            val asset = o["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true } ?: return@withContext null
            val apk = asset["browser_download_url"]?.jsonPrimitive?.content ?: return@withContext null
            val digest = digestOf(asset)
            Release(o["tag_name"]!!.jsonPrimitive.content.removePrefix("v"), apk, o["body"]?.jsonPrimitive?.content.orEmpty(), digest, asset["size"]?.jsonPrimitive?.content?.toLongOrNull())
        }
    }

    fun isNewer(candidate: String, current: String = BuildConfig.VERSION_NAME): Boolean {
        fun parts(v: String) = v.split('.', '-').mapNotNull { it.toIntOrNull() }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** GitHub's asset digest ("sha256:<hex>") as bare lowercase hex, or null if absent or in another form. */
    internal fun digestOf(asset: kotlinx.serialization.json.JsonObject): String? =
        (asset["digest"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.startsWith("sha256:") }
            ?.removePrefix("sha256:")?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }

    /** No digest published may proceed; a published one must match. */
    internal fun digestOk(want: String?, actual: String) = want == null || want == actual

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun downloadAndInstall(context: Context, http: OkHttpClient, release: Release, progress: (Float) -> Unit) {
        val file = StagedApk.newFile(context)
        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
                check(r.isSuccessful) { "Download failed (${r.code})" }
                val total = r.body.contentLength().takeIf { it > 0 } ?: -1L
                file.outputStream().use { out ->
                    val input = r.body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) progress(read / total.toFloat())
                    }
                }
            }
        }
        val staged = withContext(Dispatchers.IO) { StagedApk.adopt(context, file) } ?: throw IllegalStateException("The download isn't a valid APK")
        try {
            check(UpdateCheck.sizeOk(release.size, staged.file.length())) { "The download is not the size GitHub lists" }
            check(digestOk(release.sha256, staged.sha256)) { "The download doesn't match GitHub's checksum" }
            check(UpdateCheck.signerMatches(context, staged.file)) { "The download is not signed with VITRE's key" }
            check(withContext(Dispatchers.IO) { StagedApk.hash(staged.file) } == staged.sha256) { "The download changed before it was installed" }
            // Fire and forget as before: the receiver opens the system card if one is needed.
            (ApkInstall.commit(context, staged.file, userAction = false, await = false) as? InstallResult.Failed)?.let { throw IllegalStateException(it.reason) }
        } finally {
            staged.discard()
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        ApkInstall.onStatus(
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1),
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
        )
    }
}
