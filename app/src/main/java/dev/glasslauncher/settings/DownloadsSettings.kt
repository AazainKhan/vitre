package dev.glasslauncher.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.glasslauncher.data.DownloadsConfig
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.MyCode
import dev.glasslauncher.downloads.DownloadInput
import dev.glasslauncher.downloads.DownloadState
import dev.glasslauncher.downloads.Downloads
import dev.glasslauncher.downloads.Entry
import dev.glasslauncher.home.HomeModel
import dev.glasslauncher.home.Overlay
import dev.glasslauncher.home.PanelTitle
import dev.glasslauncher.system.RemoteNotice
import dev.glasslauncher.ui.Hint
import dev.glasslauncher.ui.MenuRow
import dev.glasslauncher.ui.ToggleRow

private fun HomeModel.editDownloads(change: (DownloadsConfig) -> DownloadsConfig) = edit { it.copy(downloads = change(it.downloads)) }

/** Settings › Downloads. [onCodes] opens My Codes. */
@Composable
internal fun ColumnScope.DownloadsSettingsPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, open: (Overlay) -> Unit, onCodes: () -> Unit) {
    val context = LocalContext.current
    val d = cfg.downloads
    PanelTitle("Downloads")
    MenuRow("Open Downloads", { open(Overlay.Downloads) }, f, chevron = true)
    MenuRow("Files", { open(Overlay.Files) }, chevron = true, help = "Open, rename, delete or check what is in the Downloads folder.")
    MenuRow("Simultaneous Downloads", {
        val n = d.concurrent % 4 + 1
        model.editDownloads { it.copy(concurrent = n) }
        Downloads.queue(context).setConcurrency(n)
    }, value = d.concurrent.toString(), help = "How many files download at once, 1 to 4. The rest wait their turn.")
    MenuRow("Parts per File", {
        val n = d.segments % 8 + 1
        model.editDownloads { it.copy(segments = n) }
        Downloads.queue(context).setSegments(n)
    }, value = d.segments.toString(), help = "A big file is fetched in this many pieces at once, 1 to 8. Some sites slow you down when there are more.")
    ToggleRow("Keep Installed APKs", d.keepInstalledApks, { on -> model.editDownloads { it.copy(keepInstalledApks = on) } },
        help = "Off: an app's file is deleted once it is installed.")
    MenuRow("My Codes", onCodes, value = d.codes.size.toString(), chevron = true, help = "Short words or numbers that stand for a link of your own.")
    MenuRow("Clear History", {
        open(Overlay.Confirm("Clear Download History?", "Forgets the links you typed and the ones you accepted, and takes finished downloads off the list. The files stay on this TV.", "Clear", destructive = true) {
            model.editDownloads { DownloadInput.cleared(it) }
            val queue = Downloads.queue(context)
            queue.items.value.filter { it.state == DownloadState.Done || it.state == DownloadState.Failed }.forEach { queue.remove(it.id, deleteFile = false) }
        })
    })
}

/** Settings › Downloads › My Codes. */
@Composable
internal fun ColumnScope.DownloadCodesPage(model: HomeModel, cfg: LauncherConfig, f: Modifier, open: (Overlay) -> Unit) {
    val context = LocalContext.current
    PanelTitle("My Codes")
    MenuRow("Add Code", {
        open(Overlay.TextInput("Code", "", "A short word or number, like “tv” or “42”.") { code ->
            if (DownloadInput.parse(code) !is Entry.Code) { RemoteNotice.show(context, "A code is one word or number, with no dots or spaces."); return@TextInput }
            open(Overlay.TextInput("Link for “$code”", "https://", "The file this code downloads. It needs https.") { link ->
                val url = DownloadInput.httpsUrl(link)
                if (url == null) { RemoteNotice.show(context, DownloadInput.NEEDS_HTTPS); return@TextInput }
                open(Overlay.TextInput("Name (optional)", "", "Shown in the list. Leave it empty to use the file's own name.") { name ->
                    model.editDownloads { c -> c.copy(codes = c.codes.filterNot { it.code.equals(code, ignoreCase = true) } + MyCode(code, url, name)) }
                })
            })
        })
    }, f)
    cfg.downloads.codes.forEach { c ->
        MenuRow(c.code, {
            open(Overlay.Confirm("Remove “${c.code}”?", "The code stops working. The file it pointed to is untouched.", "Remove", destructive = true) {
                model.editDownloads { d -> d.copy(codes = d.codes - c) }
            })
        }, value = DownloadInput.host(c.url) ?: c.url)
    }
    Hint("Codes shared in the VITRE repository work too. A download from a code always shows its site first.")
}
