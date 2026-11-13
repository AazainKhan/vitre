package dev.glasslauncher.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.glasslauncher.home.Overlay
import dev.glasslauncher.home.PanelTitle
import dev.glasslauncher.system.AirPlay
import dev.glasslauncher.system.AirPlayPageModel
import dev.glasslauncher.system.AirPlayPageModel.Kind
import dev.glasslauncher.system.AirPlayProtocol.SettingKey
import dev.glasslauncher.system.AirPlayUi
import dev.glasslauncher.system.RemoteNotice
import dev.glasslauncher.ui.Hint
import dev.glasslauncher.ui.MenuRow
import dev.glasslauncher.ui.SectionLabel
import dev.glasslauncher.ui.ToggleRow

/** What the Settings row for AirPlay shows beside its name. */
internal fun airPlayRowValue(ui: AirPlayUi): String = when (AirPlayPageModel.of(ui).kind) {
    Kind.NotSetUp -> "Not Set Up"
    Kind.Waiting -> ""
    else -> if (ui.running) "On" else "Off"
}

/**
 * Settings › AirPlay. Every value is read from PhairPlay's latest STATE ([ui]); a row sends its SET and shows the
 * new value when PhairPlay answers, so the page never holds a copy that could disagree with the receiver.
 */
@Composable
internal fun ColumnScope.AirPlaySettingsPage(ui: AirPlayUi, hub: dev.glasslauncher.system.AirPlayHub, f: Modifier, open: (Overlay) -> Unit, about: () -> Unit) {
    val context = LocalContext.current
    val page = AirPlayPageModel.of(ui)
    // PhairPlay refused a SET (an unknown key, a bad value): say so once per new answer, not for one that came before this page opened.
    val errorsAtEntry = androidx.compose.runtime.remember { ui.errors }
    LaunchedEffect(ui.errors) { if (AirPlayPageModel.newError(errorsAtEntry, ui.errors)) RemoteNotice.show(context, "AirPlay couldn't change that setting", 3_000) }
    PanelTitle("AirPlay")
    when (page.kind) {
        Kind.NotSetUp -> {
            MenuRow("About AirPlay", about, f, chevron = true)
            Hint(AirPlayPageModel.HINT_NOT_SET_UP)
        }
        Kind.Waiting -> {
            MenuRow("About AirPlay", about, f, chevron = true)
            Hint("Asking PhairPlay for its settings.")
        }
        Kind.ToggleOnly -> {
            ToggleRow("AirPlay", page.on, { hub.setOn(it) }, f, help = "Receive AirPlay from a Mac or iPhone.")
            MenuRow("About AirPlay", about, chevron = true)
            Hint(AirPlayPageModel.HINT_OLD)
        }
        Kind.Full -> {
            ToggleRow("AirPlay", page.on, { hub.setOn(it) }, f, help = "Receive AirPlay from a Mac or iPhone." + (page.sender?.let { " Connected to $it." } ?: ""))
            MenuRow("Name", {
                open(Overlay.TextInput("AirPlay Name", page.name, "How this TV appears in the AirPlay list, up to ${page.nameMax} characters. Leave it empty to use the TV's own name.") { typed ->
                    val name = typed.trim()
                    if (name.length > page.nameMax) RemoteNotice.show(context, "A name can be up to ${page.nameMax} characters.", 3_000)
                    else hub.setSetting(SettingKey.DisplayName, name)
                })
            }, value = AirPlayPageModel.nameValue(page.name), chevron = true)
            ToggleRow("Require Code", page.requireCode, { hub.setSetting(SettingKey.PinAuth, it) },
                help = "A code appears on this TV when a device connects, and has to be entered on that device.")
            SectionLabel("Mirroring")
            MenuRow("Video Quality", { hub.setSetting(SettingKey.HighResolution, !page.highQuality) },
                value = AirPlayPageModel.qualityValue(page.highQuality),
                help = "High asks the Mac for 1440p, which looks sharper but is harder for this stick to decode.")
            ToggleRow("Mirroring Audio", page.mirrorAudio, { hub.setSetting(SettingKey.MirrorAudio, it) },
                help = "Play the Mac's sound while mirroring. PhairPlay marks its clock sync as experimental.")
            MenuRow("About AirPlay", about, chevron = true)
        }
    }
}

/** Settings › AirPlay › About: who built the receiver, and its licence. Link text only. */
@Composable
internal fun ColumnScope.AirPlayAboutPage(f: Modifier, open: (Overlay) -> Unit) {
    PanelTitle("About AirPlay")
    MenuRow("Receiver", {}, f, value = "PhairPlay")
    MenuRow("Licence", {
        open(Overlay.Confirm("Licences", AIRPLAY_LICENCE, "OK") {})
    }, value = "Apache-2.0", chevron = true)
    Hint("AirPlay receiving by PhairPlay, Apache-2.0. github.com/mazer666/PhairPlay")
}

internal const val AIRPLAY_LICENCE =
    "AirPlay receiving by PhairPlay, licensed under the Apache License 2.0 (apache.org/licenses/LICENSE-2.0). " +
        "PhairPlay is a separate app that you build and install yourself; VITRE does not include it."
