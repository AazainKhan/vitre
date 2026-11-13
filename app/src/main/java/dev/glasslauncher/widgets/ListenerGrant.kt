package dev.glasslauncher.widgets

import android.content.Context
import dev.glasslauncher.system.Root

/**
 * P328: Now Playing and Notification Center need notification-listener access, which an install or a debloat can lose
 * (the stick ran for days with none: no Now Playing at all). With root (VITRE Pro / OS) it is granted again by itself; the
 * plain APK keeps the Settings hint (SettingsPanel), since only the user or adb can grant it there.
 */
object ListenerGrant {
    /** The grant for [component] ("pkg/class"), or null when it is not a plain component name (it ends up in a root shell). */
    internal fun script(component: String): String? =
        if (Regex("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z][A-Za-z0-9_.$]*").matches(component)) "cmd notification allow_listener $component" else null

    /** Off Main. Does nothing when access is already there or there is no root. */
    suspend fun ensure(context: Context) {
        if (NowPlayingSource.isAllowed(context)) return
        val script = script(NowPlayingSource.component(context).flattenToString()) ?: return
        if (!(Root.known || Root.available())) return
        Root.action(context, "Grant notification access (Now Playing, Notification Center)", script)
    }
}
