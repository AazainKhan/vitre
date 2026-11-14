package dev.glasslauncher.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import dev.glasslauncher.app
import dev.glasslauncher.system.UpdateCheck
import dev.glasslauncher.widgets.IdleState
import kotlinx.coroutines.delay

/**
 * The weekly update check (P340), as a coroutine of Home: one timer until the check is due, then a quiet minute, then one request.
 * It exists only while Home is in front with no music playing (the effect restarts when either changes), so it never runs during
 * another app's playback, never draws, and never wakes anything on its own. No work at all until the week is up.
 */
@Composable
internal fun UpdateWatch(idle: IdleState, homeInFront: Boolean, musicPlaying: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(homeInFront, musicPlaying) {
        if (!homeInFront || musicPlaying) return@LaunchedEffect
        val wait = UpdateCheck.untilDue(UpdateCheck.lastCheck(context), System.currentTimeMillis())
        if (wait > 0) delay(wait)
        while (idle.millisSinceInput() < UpdateCheck.QUIET_MS) delay(UpdateCheck.QUIET_MS - idle.millisSinceInput() + 50)
        UpdateCheck.run(context, context.app.http)
    }
}
