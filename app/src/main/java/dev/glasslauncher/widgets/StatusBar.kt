package dev.glasslauncher.widgets

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.glass.GlassStyle
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.glass
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalMetrics
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HOME-03: the only persistent chrome is one small glass capsule in the top right: the time and a
 * gear (the date and weather are in Control Center's header). It becomes focusable when nothing else is
 * above the tray, and opens Control Center.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun StatusPill(
    cfg: LauncherConfig,
    idle: IdleState,
    focusable: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    fade: () -> Float = { 1f },
    /** Opens search (P161): a glass circle left of the pill, focusable with it. Null: none. */
    onSearch: (() -> Unit)? = null,
    /**
     * Keep the time on screen once Home has faded out (P93 #7). tvOS leaves the clock over the wallpaper when the top
     * shelf goes full-bleed, and it is the one piece of chrome worth keeping: it says the TV is awake and what time it
     * is. The pill's glass and gear go with the rest of the chrome; only the digits stay.
     */
    keepClockWhenIdle: Boolean = false,
    /** Opens the notifications drawer (P98): a glass circle beside the pill, focusable with it. Null: none. */
    onNotices: (() -> Unit)? = null,
    /** Drawn as a filled dot: what the drawer would show, so the pill is worth pressing. */
    noticeCount: Int = 0,
) {
    val palette = LocalPalette.current
    val m = LocalMetrics.current
    // Clear glass shows the art through it, so the text follows the art (dark on bright art).
    val onLight = LocalBackdrop.current.backdrop?.artLight(0.86f, 0.03f, 0.98f, 0.09f) == true
    // Asking for focus on the group (Up from the shelf) lands on the pill, not on the search circle beside it: Left reaches search.
    val pillFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .padding(top = m.chromeInset, end = m.chromeInset)
            .graphicsLayer { alpha = (if (keepClockWhenIdle && idle.hidden) 1f else idle.chromeAlpha) * fade() }
            .onGloballyPositioned { dev.glasslauncher.home.ControlCenterWindow.chromeBounds = it.boundsInWindow() }
            .focusProperties { enter = { pillFocus } }
            .focusGroup(),
    ) {
        if (onSearch != null) FocusTile(
            label = "Search",
            onClick = onSearch,
            onLongClick = onSearch,
            shape = CircleShape,
            focusedScale = 1.08f,
            shadow = false,
            // The halo on the tile itself, as the pill's: inside the tile it was clipped to the circle (P177).
            modifier = Modifier.focusProperties { canFocus = focusable }.testTag("status-search")
                .then(if (onLight) Modifier.drawBehind { drawHalo() } else Modifier),
        ) { focused -> SearchFace(focused, onLight) }
        if (onNotices != null) FocusTile(
            label = if (noticeCount > 0) "$noticeCount notifications" else "Notifications",
            onClick = onNotices,
            onLongClick = onNotices,
            shape = CircleShape,
            focusedScale = 1.08f,
            shadow = false,
            modifier = Modifier.focusProperties { canFocus = focusable }.testTag("status-notices")
                .then(if (onLight) Modifier.drawBehind { drawHalo() } else Modifier),
        ) { focused -> NoticeFace(focused, onLight, noticeCount) }
        // Now Playing is a Home takeover and a Control Center card now, not a pill.
        FocusTile(
            label = "Status and Control Center",
            onClick = onSelect,
            onLongClick = onSelect,
            shape = Shapes.pill,
            focusedScale = 1.08f,
            // Over light art the capsule separates from it by its edge (tvOS light glass): a faint halo (below)
            // and a thin rim, not a darker fill. Dark art keeps the bare glass.
            shadow = false,
            modifier = Modifier.focusRequester(pillFocus).focusProperties { canFocus = focusable }.testTag("status-pill")
                .then(if (onLight) Modifier.drawBehind { drawHalo() } else Modifier)
                // Control Center grows out of exactly this capsule.
                .onGloballyPositioned { dev.glasslauncher.home.ControlCenterWindow.pillBounds = it.boundsInWindow() },
        ) { focused -> if (keepClockWhenIdle && idle.hidden) IdleClock(cfg, onLight) else PillFace(cfg, focused, onLight) }
    }
}


/**
 * The status chrome's ONE glass recipe (P177), shared by the pill and the search circle: the tray's clear glass, and
 * over light art the milky light glass with the soft halo and the glass's bright rim, so either reads as glass on a
 * white backdrop instead of leaving only its icon.
 */
@Composable
internal fun Modifier.statusGlass(shape: androidx.compose.ui.graphics.Shape, onLight: Boolean, halo: Boolean = true): Modifier {
    val palette = LocalPalette.current
    return this
        .then(if (onLight && halo) Modifier.drawBehind { drawHalo() } else Modifier)
        .glass(LocalBackdrop.current, shape, if (onLight) GlassStyle.shelf(true).copy(tint = Color.White.copy(alpha = 0.6f), edgeBand = false) else GlassStyle.shelf(palette.light).copy(edgeBand = false))
}

/** The search button as drawn: a glass circle with a magnifying glass, the pill's material. */
/**
 * The notifications target: a dot when there is something to read, a ring when there is not, so whoever is holding the
 * remote can tell at a glance whether pressing it is worth it. No bell glyph: at ten feet a bell and a dot read alike.
 */
@Composable
private fun NoticeFace(focused: Boolean, onLight: Boolean, count: Int) {
    val palette = LocalPalette.current
    // As the search circle: glass at rest, the solid focus fill (and a dark dot) when it has focus.
    Box(
        Modifier
            .size(32.dp)
            .statusGlass(CircleShape, onLight, halo = false)
            .then(if (focused) Modifier.background(palette.focusFill, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(32.dp)) {
            val ink = if (focused) palette.onFocusFill else if (onLight) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
            val r = size.minDimension * 0.2f
            val a = if (focused) 1f else 0.82f
            if (count > 0) drawCircle(ink.copy(alpha = a), r)
            else drawCircle(ink.copy(alpha = a * 0.85f), r, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.4f))
        }
    }
}

@Composable
private fun SearchFace(focused: Boolean, onLight: Boolean) {
    val palette = LocalPalette.current
    val rest = if (onLight) Color(0xFF0E1015) else Color.White
    Box(
        Modifier
            .size(32.dp)
            .statusGlass(CircleShape, onLight, halo = false)
            .then(if (focused) Modifier.background(palette.focusFill, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(dev.glasslauncher.R.drawable.ic_search), null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (focused) palette.onFocusFill else rest),
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * The pill as drawn: the glass capsule with the clock and the gear. Control Center draws it too, at the pill's
 * spot, as its first frame, so the pill turns into Control Center without a cut (P29).
 */
@Composable
fun PillFace(cfg: LauncherConfig, focused: Boolean, onLight: Boolean, modifier: Modifier = Modifier, copy: Boolean = false) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = modifier
            // Home's pill gets its halo from its focus tile; the copy draws its own.

            // The tray's clear glass, so the two read as one material; over light art (a white logo
            // hero) clear glass vanishes, so it is milky white there (light glass: no dark bevel, a faint
            // eased shade at the bottom), and the edge does the separating (see shadow above, rim below).
            // Glass stays put under the focus fill.
            // No drawn border: the old 22% black one read as a hard grey outline on white (P52). Over light art the
            // edge is the soft halo around the capsule (drawHalo) with the glass's own bright rim inside it.
            // Home's pill gets its halo from its focus tile (it lifts with it); the copy draws its own.
            .statusGlass(Shapes.pill, onLight, halo = copy)
            .then(
                if (focused) Modifier.background(palette.focusFill, Shapes.pill) else Modifier,
            )
            .padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
    ) {
        val rest = if (onLight) Color(0xFF0E1015) else Color.White
        val text = if (focused) palette.onFocusFill else rest
        Text(rememberClock(cfg.clock24h), style = Type.body.copy(fontSize = Type.body.fontSize * 0.86f, fontFeatureSettings = "tnum"), color = text, modifier = if (copy) Modifier else Modifier.testTag("clock"))
        Box(
            Modifier
                .then(if (copy) Modifier else Modifier.testTag("status-gear"))
                .size(22.dp)
                .background(if (focused) palette.onFocusFill.copy(alpha = 0.12f) else if (onLight) Color.White.copy(alpha = 0.5f) else rest.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { GearIcon(text, size = 13.dp) }
    }
}

private const val HALO_ALPHA = 0.045f
private const val HALO_STEPS = 7

/**
 * A faint halo round the capsule: [HALO_STEPS] abutting capsule-shaped bands of 1.5 dp, black, strongest at the
 * edge and fading to nothing about 9 dp out (a squared falloff). Centred, with a 1.5 dp drop so it is a touch
 * stronger below. Plain strokes: no bitmap, no blur, no layer.
 */
private fun DrawScope.drawHalo() {
    val band = 1.5.dp.toPx()
    val drop = 1.5.dp.toPx()
    for (i in 0 until HALO_STEPS) {
        val out = band * i + band / 2
        val fall = 1f - i / HALO_STEPS.toFloat()
        val r = size.height / 2 + out
        drawRoundRect(
            Color.Black.copy(alpha = HALO_ALPHA * fall * fall),
            topLeft = Offset(-out, -out + drop),
            size = androidx.compose.ui.geometry.Size(size.width + 2 * out, size.height + 2 * out),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = band),
        )
    }
}

/**
 * The time left on the wallpaper once Home has faded out (P93 #7): the pill's glass without the gear, and it does not
 * take focus (there is nothing to focus while the chrome is gone; any key brings the rest back).
 */
@Composable
private fun IdleClock(cfg: LauncherConfig, onLight: Boolean) {
    val ink = if (onLight) Color(0xFF0E1015) else Color.White
    Text(
        rememberClock(cfg.clock24h),
        style = Type.body.copy(fontSize = Type.body.fontSize * 0.86f, fontFeatureSettings = "tnum"),
        color = ink,
        // The search and dot circles stay over the faded Home in their glass, so the clock keeps the same glass
        // (P333). The old shadowElevation layer had no shape: it cast the text box's rectangle as a dark smear.
        modifier = Modifier
            .testTag("idle-clock")
            .statusGlass(Shapes.pill, onLight, halo = false)
            // As tall as the circles and the full pill (32 dp), the digits centred in it.
            .heightIn(min = 32.dp)
            .wrapContentHeight()
            .padding(horizontal = 14.dp),
    )
}

@Composable
fun rememberClock(h24: Boolean, seconds: Boolean = false, running: Boolean = true): String {
    // 12-hour time says AM or PM. Seconds tick only where asked (Control Center, while it's open): on
    // the always-visible pill they took idle CPU from 0% to 1.2% (measured), so it wakes once a minute.
    val pattern = when {
        h24 && seconds -> "HH:mm:ss"
        h24 -> "HH:mm"
        seconds -> "h:mm:ss a"
        else -> "h:mm a"
    }
    val step = if (seconds) 1_000L else 60_000L
    // P203: a parked Control Center's clock doesn't tick ([running] false); it reads the time again as it opens.
    val text by produceState(format(pattern), pattern, running) {
        while (running) {
            value = format(pattern)
            delay(step - WallClock.now() % step + 20)
        }
    }
    return text
}

/**
 * The time the clock and date widgets show: the system's. Test seam; production never sets it. The JVM shot
 * harness pins it because java.util.Date ignores Robolectric's clock.
 */
@androidx.annotation.VisibleForTesting
internal object WallClock {
    @Volatile var now: () -> Long = System::currentTimeMillis
}

/** Today's date, short ("Tue, Oct 7"). Checked once a minute, like the clock, so it turns over at midnight. */
@Composable
fun rememberDate(): String {
    // US English abbreviations ("Wed, Oct 7"); en_CA adds periods ("Wed., Oct. 7").
    fun today() = SimpleDateFormat(DATE_PATTERN, Locale.US).format(Date(WallClock.now()))
    val text by produceState(today()) {
        while (true) {
            value = today()
            delay(60_000L - WallClock.now() % 60_000L + 20)
        }
    }
    return text
}

private const val DATE_PATTERN = "EEE, MMM d"

// AM/PM as "AM"/"PM" whatever the locale's markers are (en_CA writes "p.m.").
private fun format(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault()).apply {
    dateFormatSymbols = dateFormatSymbols.apply { amPmStrings = arrayOf("AM", "PM") }
}.format(Date(WallClock.now()))

/** The settings gear (Material Symbols, filled): one clean silhouette at every size. */
@Composable
fun GearIcon(color: Color, size: Dp = 20.dp) {
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(dev.glasslauncher.R.drawable.ic_settings),
        contentDescription = null,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color),
        modifier = Modifier.size(size),
    )
}
