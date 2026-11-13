package dev.glasslauncher.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.glasslauncher.data.LauncherConfig
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import dev.glasslauncher.system.AppNotice
import dev.glasslauncher.system.NoticeStore
import dev.glasslauncher.widgets.NowPlayingService
import dev.glasslauncher.ui.MenuRow
import android.content.Intent

/** "now", "4m", "2h" - enough to read a row, short enough for a 1920-wide TV at ten feet (P98). */
internal fun agoLabel(nowMs: Long, whenMs: Long): String {
    val d = (nowMs - whenMs).coerceAtLeast(0)
    val m = d / 60_000
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m"
        m < 60 * 24 -> "${m / 60}h"
        else -> "${m / (60 * 24)}d"
    }
}

/**
 * What the TV has to say (P98).
 *
 * Rows are other apps' notifications, newest first, with the ones VITRE has been told to ignore left out. Select
 * opens the app the notification came from and clears it, as a phone shade does; the app might also clear it itself,
 * which is why the row disappears on its own rather than only on our say-so.
 *
 * When nothing has been granted the drawer says so instead of showing an empty list, because "no notifications" and
 * "we cannot see notifications" look identical otherwise and want different things from the user.
 */
@Composable
fun NoticeDrawer(cfg: LauncherConfig, active: Boolean, open: (Overlay) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    // NoticeStore is a plain object, not a data class the model rebuilds, so watch its version to re-read on change.
    val version by remember { NoticeStore.state }
    val blocked = remember(cfg) { cfg.blockedNoticePackages.toSet() }
    val rows = remember(version, blocked) { NoticeStore.list(blocked) }
    val now = remember(version) { System.currentTimeMillis() }

    SettingsPage(active) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.testTag("notice-drawer")) {
        MenuList(active) { first ->
            when {
                !NoticeStore.connected() -> MenuRow(
                    title = "Let VITRE see notifications",
                    onClick = { open(SettingsOverlay("Notifications")) },
                    value = "Needed",
                    help = "VITRE can show what your apps are saying, but only once it may read notifications.",
                    modifier = androidx.compose.ui.Modifier.focusRequester(first),
                )

                rows.isEmpty() -> MenuRow(
                    title = "Nothing right now",
                    onClick = {},
                    enabled = false,
                    help = "Notifications appear here as your apps post them.",
                    modifier = androidx.compose.ui.Modifier.focusRequester(first),
                )

                else -> {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                        PillButton("Clear all", androidx.compose.ui.Modifier.focusRequester(first)) { rows.forEach { NoticeStore.removed(it.key) } }
                    }
                    val open = { n: AppNotice ->
                        NoticeStore.removed(n.key)
                        NowPlayingService.instance?.dismiss(n.key)
                        context.packageManager.getLaunchIntentForPackage(n.pkg)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ?.let { runCatching { context.startActivity(it) } }
                        close()
                    }
                    groupNotices(rows).forEach { g -> NoticeGroupView(g, now, open) }
                }
            }
        }
        }
    }
}

/** An app's own name, falling back to the package when it is not installed any more. */
@Composable
internal fun appLabel(pkg: String): String {
    val pm = LocalContext.current.packageManager
    return remember(pkg) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()?.takeIf { it.isNotBlank() && it != pkg }
            ?: labelFallback(pkg)
    }
}

/** An app with no label of its own (or not installed any more): the last part of its package name, capitalised ("com.chat.app" -> "App"). */
internal fun labelFallback(pkg: String): String =
    pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifBlank { pkg }

/** Settings opened straight onto a page, for the "grant me" row. */
private fun SettingsOverlay(@Suppress("UNUSED_PARAMETER") page: String) = Overlay.Settings

/** One app's notifications, newest first. */
internal class NoticeGroup(val pkg: String, val items: List<AppNotice>)

/** [rows] (newest first) grouped by app; the groups keep the order of their newest notification. */
internal fun groupNotices(rows: List<AppNotice>): List<NoticeGroup> =
    rows.groupBy { it.pkg }.map { (pkg, items) -> NoticeGroup(pkg, items) }
