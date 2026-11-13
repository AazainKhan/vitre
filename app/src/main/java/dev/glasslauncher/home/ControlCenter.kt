package dev.glasslauncher.home

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.animateFloat
import android.content.Intent
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glasslauncher.app
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.glasslauncher.R
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.ThemeMode
import dev.glasslauncher.dream.AerialActivity
import dev.glasslauncher.system.BtFound
import dev.glasslauncher.system.BtKind
import dev.glasslauncher.system.BtPairing
import dev.glasslauncher.system.SystemControls
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.GlassMatch
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.currentValueOf
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.LocalMetrics
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type
import dev.glasslauncher.ui.dissolve
import dev.glasslauncher.widgets.rememberClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Blue = Color(0xFF0091FF)

/**
 * Tile sizes grow with the text size (Settings › Display & Text), so labels keep fitting instead of
 * being cut off: at Larger the Wi-Fi network name was truncated in a fixed 128 dp pill.
 */
internal class CcSizes(private val k: Float) {
    /**
     * tvOS 27's Control Center is one grid of square cells: u is a cell, gap the one space between cells.
     * Circles are 1×1, pills 2×1 (as tall as a circle), the big tile 2×2 and square, wide rows 4×1, and the
     * panel 4u + 3·gap. Everything below derives from these two.
     */
    // tvOS 27's own grid (P138, docs/research/cc-spec-gaps.md G1/G2): cell 96 pt = 48 dp, gap 16 pt = 8 dp (FW defaultPlatterDesiredWidth
    // 480 pt = 4 cells + 3 gaps + 2 x 24 pt of padding). The pills' text is tvOS's Caption 2 (11.5 sp) and long values clip or scroll
    // on focus, as tvOS's do, so the cell no longer grows to fit a label at the 13 sp floor (it was 56 dp / 9 dp).
    val u = (48 * k).dp
    val gap = (8 * k).dp
    val round = u
    val pill = u
    val pillWidth = u * 2 + gap
    val big = u * 2 + gap
    val bigWidth = big
    val column = u * 4 + gap * 3
    // An off toggle's glyph sits in a disc: F27 measures the disc at 21 dp (0.44 u) and the moon in it at about 10 dp (KIT: 15 pt in 41 pt).
    val disc = u * 0.44f
    val discGlyph = u * 0.24f
    // 21 dp: tvOS's controlCenterGlyphPointSize (42 pt), the circle's glyph and a pill's bare glyph (on, or without a disc).
    val roundGlyph = (21 * k).dp
    val pillGlyph = (21 * k).dp
    /** A pill's leading column (FW defaultSymbolReservedSpace 86 pt = 43 dp; 40 here so "Bluetooth" and "Connected" fit the 104 dp pill whole, F27 puts the text 42.6 dp in): the glyph or its disc centred in it, the text starts at its edge. */
    val leadColumn = (40 * k).dp
    /** The picker's volume row: FW volumeRowHeight 54 pt. */
    val volumeRow = (27 * k).dp
    // F27: the Power tile's ring is about 50 dp in the 104 dp tile (FW format 0xe: a 113 pt glyph box).
    val bigGlyph = u * 1.04f
    /** The big tile's corner: about 40 px on 215 px in the 27 frame (20 dp), not 26 (G/C16). */
    val bigRadius = (20 * k).dp
    /**
     * How much a focused control grows in total (SK: 96 -> 116 px = 10 dp, an absolute lift, not a ratio) so that the growth on each side
     * (5 dp) is always under the 8 dp gap and a focused control never touches its neighbours (P138: a x1.21 on a 104 dp pill grew 11 dp a side).
     */
    val focusGrowth = (10 * k).dp
    /**
     * The big 2x2 tile (the one focused on every open) grows 6 dp in all, 3 a side (P273): at the 10 dp the small controls get it left 3 dp of
     * the 16 px gap to the Sleep pill below it and read as touching it. Larger items lift less, as tvOS's do.
     */
    val bigFocusGrowth = (6 * k).dp
    fun focusScale(width: Dp, height: Dp = width): Float = 1f + (if (height.value >= 90f * k) bigFocusGrowth else focusGrowth).value / width.value
}

private val LocalCcPageIconSize = androidx.compose.runtime.staticCompositionLocalOf { CC_PAGE_ICON }

/** The menu bar's controls: F27 measures circles of about 48 px (24 dp) at a 60 px (30 dp) pitch, a 6 dp gap (SK's 58 px circles at a 60 px pitch leave 1 dp and read as touching, P138 H1). */
private val CC_PAGE_ICON = 24.dp

internal val LocalCcSizes = androidx.compose.runtime.staticCompositionLocalOf { CcSizes(1f) }

/** Pill text is tvOS's Caption 2 (FW stylingForState: style 7 = 23 px = 11.5 sp, 15 dp lines), Medium, for the title and the value (P138 C10). */
internal val CcPillTitle @Composable get() = Type.caption.copy(fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = (-0.1).sp)
private val CcPillValue @Composable get() = Type.caption.copy(fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = (-0.1).sp)
/** A platter's section header: Caption 1 (12.5 sp). */
internal val CcSectionHeader @Composable get() = Type.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)

/** Whether the tile whose content is being drawn has focus (a pill's long text scrolls only then, F05). */
internal val LocalCcTileFocused = androidx.compose.runtime.compositionLocalOf { false }

/**
 * P206 E: the tiles are the dock's clear glass, so a label can sit on bright art. It gets a subtle shadow of its own
 * (black [LABEL_SHADOW_ALPHA], [LABEL_SHADOW_BLUR] soft), labels only: glyphs have none (user: a dark spot under an icon
 * looked wrong).
 */
/** P206 E: a faint black wash over the dock's clear glass, so white labels keep 4.5:1 at their brightest surround over white art. */
private val E_WASH = androidx.compose.ui.graphics.SolidColor(Color.Black.copy(alpha = 0.18f))
private const val LABEL_SHADOW_ALPHA = 0.45f
private val LABEL_SHADOW_BLUR = 4.dp

@Composable
private fun shadowed(style: androidx.compose.ui.text.TextStyle): androidx.compose.ui.text.TextStyle {
    if (dev.glasslauncher.glass.CcAdapt.variant != "E") return style
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { LABEL_SHADOW_BLUR.toPx() }
    return style.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = LABEL_SHADOW_ALPHA), androidx.compose.ui.geometry.Offset(0f, px / 4f), px))
}

/**
 * A pill's one line of text (F05): never an ellipsis. Text that fits is as it was; text that does not fades out at its
 * right edge, and scrolls (a marquee) only while its tile has focus, as tvOS's labels do.
 */
@Composable
internal fun PillText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier, lines: Int = 1) {
    // A title with no value line under it wraps at its spaces onto a second line, as tvOS's do ("Do Not / Disturb"); never mid-word, never an ellipsis.
    val st = shadowed(style)
    if (lines > 1) { Text(text, style = st, color = color, maxLines = lines, softWrap = true, overflow = TextOverflow.Clip, modifier = modifier); return }
    dev.glasslauncher.ui.MarqueeText(text, st, color, active = LocalCcTileFocused.current, modifier = modifier)
}

/** Platter push and pop (TVSPAnimator): spring mass 1, stiffness 150, damping 20 is a damping ratio of 20 / (2 * sqrt(150)). */
private const val PLATTER_STIFFNESS = 150f
private const val PLATTER_DAMPING = 0.8165f
private const val PLATTER_FROM_SCALE = 0.3f

/** The page switch's fades: in and out run together and sum to one, so the panel is never empty and never doubled. */
private const val PAGE_FADE_MS = 150

/** Where the platter zooms from and to: the pill that opened it, as a fraction of the box the platter fills. Plain fields: no recomposition. */
private class PlatterSource {
    var box: androidx.compose.ui.geometry.Rect? = null
    var id: String? = null
    val pills = HashMap<String, androidx.compose.ui.geometry.Rect>()
    fun origin(): androidx.compose.ui.graphics.TransformOrigin {
        val b = box ?: return androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
        val p = pills[id] ?: return androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
        if (b.width <= 0f || b.height <= 0f) return androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
        return androidx.compose.ui.graphics.TransformOrigin(((p.center.x - b.left) / b.width).coerceIn(0f, 1f), ((p.center.y - b.top) / b.height).coerceIn(0f, 1f))
    }
}

/**
 * A platter grows out of the tile that opened it and goes back into it (tvOS pushPlatterController, P151b): one position
 * and scale morph from that tile's rect to the platter's own, on the platter spring (150/20), driven by the content's
 * enter/exit so Back mid-way reverses from where it is. The fade is the AnimatedContent's (0.25 s). Reduce Motion: none.
 */
@Composable
private fun Modifier.platterMorph(scope: androidx.compose.animation.AnimatedVisibilityScope, source: PlatterSource, reduceMotion: Boolean): Modifier {
    if (reduceMotion) return this
    val p by scope.transition.animateFloat(
        transitionSpec = { androidx.compose.animation.core.spring(PLATTER_DAMPING, PLATTER_STIFFNESS) }, label = "platter-morph",
    ) { if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f }
    val own = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    return this
        .onGloballyPositioned { own[0] = it.boundsInWindow() }
        .graphicsLayer {
            // Read first: a block that returned before reading it was never re-run (the morph didn't play).
            val k = p
            val to = own[0] ?: return@graphicsLayer
            val from = source.pills[source.id] ?: return@graphicsLayer
            if (to.width <= 0f || to.height <= 0f) return@graphicsLayer
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            scaleX = androidx.compose.ui.util.lerp(from.width / to.width, 1f, k)
            scaleY = androidx.compose.ui.util.lerp(from.height / to.height, 1f, k)
            translationX = androidx.compose.ui.util.lerp(from.left - to.left, 0f, k)
            translationY = androidx.compose.ui.util.lerp(from.top - to.top, 0f, k)
        }
}

private fun Modifier.platterAnchor(source: PlatterSource, id: String): Modifier =
    onGloballyPositioned { source.pills[id] = it.boundsInWindow() }

/** The close's progress below which the capsule is the pill again and Home's own pill is shown under it. */
private const val PILL_BACK_AT = 0.02f
/** Home's pill's focused scale (StatusPill's focus tile), so a focused pill's copy matches it. */
private const val PILL_FOCUS_SCALE = 1.08f

/**
 * Control Center's open/close (P49): Home's status pill splits into two liquid drops that become the page icons,
 * and every control grows out of the nearer drop; closing runs it back into the pill. One progress value p
 * (0 = the pill, 1 = open) drives everything, from a spring without bounce, and every stage is a pure function of
 * it, so Back mid-way reverses from wherever it is. Pure, so it's unit-tested.
 *
 * This departs from measured tvOS on purpose (its controls never split or merge, liquid-glass-motion §9): the user
 * asked for it, and it is built the way that reference allows, one baked fill per shape, no metaball shader.
 */
object CcMorph {
    /**
     * Opening: tvOS's overlay spring (zeta 0.9, no bounce), visibly done in about 200 ms with the tiles' stagger (user
     * decision, P105 #1; it was stiffness 260, 350-400 ms, P49). The stagger is a function of p, so it compresses with it.
     */
    val openSpring = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = 0.001f)
    /** Closing: critically damped and quicker, about 250 ms to the pill. */
    val closeSpring = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 900f, visibilityThreshold = 0.001f)
    /**
     * When the close has visibly landed (the spring is within 1% of the pill; the rest is an invisible tail).
     * The overlay window / in-launcher overlay is removed then, so it stops taking input (a test ties it to the spring).
     */
    const val CLOSE_MS = 230

    /** The pill has split into its two drops, and they have reached the page icons' spots, by this much of p. */
    const val SPLIT_END = 0.35f
    /** The neck between the two drops has thinned to nothing by this much of the split. */
    private const val NECK_END = 0.7f
    /** The pill has faded out by this much of the open (tvOS: by about 30%). */
    private const val PILL_GONE = 0.3f
    /** The page icons' glyphs come in over the end of the split, from here to its end (quick: at most two frames half-drawn). */
    private const val GLYPH_FROM = 0.75f
    /** The first controls start to grow at this p; the farthest from their drop start [TILE_SPREAD] later. */
    private const val TILE_FROM = 0.2f
    private const val TILE_SPREAD = 0.15f
    /** The header (time, date, weather) grows from the pill's clock over this much of p, and shows by [HEADER_SHOWN]. */
    private const val HEADER_END = 0.6f
    private const val HEADER_SHOWN = 0.25f
    /** A lone drop (no page icons) is absorbed into the controls between these values of p. */
    private const val LONE_GONE_FROM = 0.35f
    private const val LONE_GONE_TO = 0.8f

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }

    /**
     * The progress (0..1) for the spring's value [e]: the value itself, and closing runs the same path back, never
     * past [from] (where the close began, Back mid-open), so the two directions agree at the turn and nothing jumps.
     */
    fun bubble(e: Float, closing: Boolean, from: Float = 1f): Float =
        if (closing) minOf(from, e).coerceIn(0f, 1f) else e.coerceIn(0f, 1f)

    /** How far the pill has split into its drops (0..1) at progress [p]: eased out, so it moves from the first frame. */
    fun split(p: Float): Float { val x = (p / SPLIT_END).coerceIn(0f, 1f); return 1f - (1f - x) * (1f - x) }

    /** Home's pill (drawn by Control Center at its spot) at progress [p]: whole at 0, gone by [PILL_GONE]. */
    fun pillAlpha(p: Float) = 1f - (p / PILL_GONE).coerceIn(0f, 1f)

    /** The page icons' glyphs at progress [p]: in at the end of the split. */
    fun glyphAlpha(p: Float) = ((p / SPLIT_END - GLYPH_FROM) / (1f - GLYPH_FROM)).coerceIn(0f, 1f)

    /** The header's opacity at progress [p]: it appears early, while it is still small at the pill's clock. */
    fun headerAlpha(p: Float) = (p / HEADER_SHOWN).coerceIn(0f, 1f)

    /** How far the header has travelled from the pill's clock to its place at [p]. */
    fun header(p: Float) = smooth(p / HEADER_END)

    /**
     * The pill's two end circles, where the drops start: the left becomes the Controls icon, the right the Alexa
     * icon. Their centres and their diameter (the pill's height).
     */
    fun pillEnds(pill: androidx.compose.ui.geometry.Rect): Pair<androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset> {
        val r = pill.height / 2
        return androidx.compose.ui.geometry.Offset(pill.left + r, pill.center.y) to androidx.compose.ui.geometry.Offset(pill.right - r, pill.center.y)
    }

    /** A drop on its way from [from] (a circle [fromD] across) to the icon slot [to] at split [q]: its centre and diameter. */
    fun drop(q: Float, from: androidx.compose.ui.geometry.Offset, fromD: Float, to: androidx.compose.ui.geometry.Rect): Pair<androidx.compose.ui.geometry.Offset, Float> =
        androidx.compose.ui.geometry.Offset(lerp(from.x, to.center.x, q), lerp(from.y, to.center.y, q)) to lerp(fromD, minOf(to.width, to.height), q)

    /**
     * The neck between the two drops at split [q], as its half-heights where it meets the drops and at its waist
     * (both [r], the drops' radius, at the start: the pill's capsule). Null once it has parted.
     */
    fun neck(q: Float, r: Float): Pair<Float, Float>? {
        val k = (q / NECK_END).coerceIn(0f, 1f)
        if (k >= 1f) return null
        val ends = r * kotlin.math.sqrt(1f - k)
        val waist = r * (1f - k) * (1f - k)
        return ends to waist
    }

    /** When a control [distance] px from its drop starts to grow (p), with [farthest] the farthest any control is. */
    fun tileStart(distance: Float, farthest: Float) = TILE_FROM + TILE_SPREAD * (distance / farthest.coerceAtLeast(1f)).coerceIn(0f, 1f)

    /** A control's own progress (0..1) at [p], given when it starts. */
    fun tile(p: Float, start: Float) = ((p - start) / (1f - start)).coerceIn(0f, 1f)

    /** A lone drop's radius factor (1..0) as the controls absorb it. */
    fun loneDrop(p: Float) = 1f - smooth((p - LONE_GONE_FROM) / (LONE_GONE_TO - LONE_GONE_FROM))

    /**
     * Where a shape whose slot is [slot] is drawn at its own progress [e], growing out of a drop at [from] that is
     * [fromD] across: uniformly scaled about its centre (from the drop's size) and moved from the drop to the slot.
     */
    fun emerge(e: Float, slot: androidx.compose.ui.geometry.Rect, from: androidx.compose.ui.geometry.Offset, fromD: Float): CcEmergeFrame {
        val s0 = (fromD / minOf(slot.width, slot.height).coerceAtLeast(1f)).coerceAtMost(1f)
        val s = lerp(s0, 1f, e)
        return CcEmergeFrame(s, lerp(from.x - slot.center.x, 0f, e), lerp(from.y - slot.center.y, 0f, e), e)
    }
}

/**
 * Where one Control Center shape is drawn this frame: scaled by [scale] about its slot's centre, moved by
 * ([dx], [dy]) px, and [round] (0..1) of the way from a capsule to its own corners.
 */
data class CcEmergeFrame(val scale: Float, val dx: Float, val dy: Float, val round: Float) {
    val still: Boolean get() = scale == 1f && dx == 0f && dy == 0f && round >= 1f
}

/** Room around the scrolling tiles so a focused tile's growth and shadow aren't clipped. */
internal val CC_BLEED = 16.dp

/** Room above the first row inside the scrolling viewport: the 2x2's growth (104 dp x 0.105) plus a margin, so a focused tile is never cut flat (P138 G6). */
private val CC_TOP_ROOM = 12.dp

/** A device's own page (P186): a controller's or a remote's drawing with its buttons, opened in Settings' full-page layout. */
internal fun openDevicePage(kind: PageKind, name: String, closeAll: () -> Unit, open: (Overlay) -> Unit) {
    dev.glasslauncher.settings.SettingsEntry.page = dev.glasslauncher.settings.SettingsEntry.device(kind, name)
    closeAll(); open(Overlay.Settings)
}

/**
 * "Remote Settings" open Glass's Settings on Remote Buttons: the remote's own settings, not the game controllers page
 * (P187: the pill used to route to Fire's controller settings).
 */
internal fun openRemoteSettings(closeAll: () -> Unit, open: (Overlay) -> Unit) {
    dev.glasslauncher.settings.SettingsEntry.page = dev.glasslauncher.settings.SettingsEntry.REMOTE_BUTTONS
    closeAll(); open(Overlay.Settings)
}

/** Gap between the time/weather header and the title of every sub-panel (platters, confirms, the permission card): one value so they all sit alike (P176). */
internal val CC_PANEL_TOP = 20.dp

/** Where a sub-panel sits under the header: the same offset and top gap for every one. */
internal fun Modifier.ccPanelSlot(): Modifier = offset(x = -CC_BLEED).padding(top = CC_PANEL_TOP, bottom = 4.dp)

/**
 * How edit mode is entered (P101): only from Launcher Settings › Control Center › Edit Layout. That sets
 * [startInEdit] (consumed when Control Center opens already in edit mode) and [fromSettings] (Done or Back then
 * closes Control Center and returns to Settings). There is no edit button and no long-press in Control Center.
 */
internal object CcEditEntry {
    @Volatile var startInEdit = false
    @Volatile var fromSettings = false

    /**
     * Settings' Control Center page is up: Home composes Control Center once, hidden, so the first open's one-off cost
     * (class loading and a cold composition, ~330 ms on the stick) is paid there and not between Select and the
     * first frame of the grid growing. [warmed] stays set for the process: it is a warm-up, not state.
     */
    var warmWanted by androidx.compose.runtime.mutableStateOf(false)
    var warmed by androidx.compose.runtime.mutableStateOf(false)

    /** Done/Back from an edit that came from Settings: Settings waits until Control Center has collapsed. */
    var returning by androidx.compose.runtime.mutableStateOf(false)
}

/** tvOS's bottom inset for the panel (about 78 px at 1080p). */
private val CC_BOTTOM_INSET = 39.dp

/** Edit mode's Add a Control: a pseudo tile id, drawn as a 2x1 cell of the grid after the last tile. */
private const val ADD_TILE = "__add"

/**
 * Control Center's one material: a small bitmap of the scene behind the panel (the backdrop's clear texture,
 * blurred, with a faint tint and the wash's dim baked in), baked with the backdrop itself ([GlassMatch.ccSheet],
 * off the main thread), so an open only picks it up. Every tile, the page discs, the Now Playing card and the
 * bubble draw their part of it: one draw each, mapped by window position, so they read as one sheet of glass and
 * never sample anything per frame.
 */
internal object CcMaterial {
    /** With no baked sheet (no backdrop yet): one fixed smoky colour. */
    val fallback = Color(0xFF2A2A2A) // the glass over a dark scene (CcAdapt.fit of black), so a frame without the sheet looks the same
    /** The rim every tile keeps so it has an edge: a single thin stroke, lit from the top-left. */
    fun rimBrush(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Brush = androidx.compose.ui.graphics.Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.55f),
        0.35f to Color.White.copy(alpha = 0.10f),
        0.7f to Color.White.copy(alpha = 0.05f),
        1f to Color.White.copy(alpha = 0.22f),
        start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(size.width, size.height),
    )

    /**
     * Tests only: the sheet of the latest open (so they can compare the tiles with it). Never set unless a test
     * turns [recordLast] on, so release builds keep no extra reference to a sheet; cleared when the open closes.
     */
    private val edges = HashMap<Triple<Shape, androidx.compose.ui.geometry.Size, Float>, androidx.compose.ui.graphics.ImageBitmap>()

    /**
     * P195: a tile's edge, baked once per shape and size: an 8 dp soft inner shadow (blurred, black 0.18 at the
     * edge) and the rim ([rimBrush]), both inside the shape. Software-drawn (BlurMaskFilter), then a hardware bitmap.
     */
    fun edge(shape: Shape, size: androidx.compose.ui.geometry.Size, dir: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.ImageBitmap =
        synchronized(edges) {
            edges.getOrPut(Triple(shape, size, density.density)) {
                if (edges.size > 48) edges.clear() // shortcut: CC has a handful of tile sizes; clear rather than track use
                val w = kotlin.math.ceil(size.width).toInt().coerceAtLeast(1); val h = kotlin.math.ceil(size.height).toInt().coerceAtLeast(1)
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                val path = androidx.compose.ui.graphics.Path().apply { addOutline(shape.createOutline(size, dir, density)) }.asAndroidPath()
                val band = EDGE_DP * density.density
                c.clipPath(path)
                c.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE; strokeWidth = band; color = Color.Black.copy(alpha = 0.18f).toArgb()
                    maskFilter = android.graphics.BlurMaskFilter(band / 2f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                })
                // The rim: a 2 dp stroke centred on the edge, clipped to its inner 1 dp.
                c.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f * density.density
                    shader = (rimBrush(size) as androidx.compose.ui.graphics.ShaderBrush).createShader(size)
                })
                (bmp.copy(android.graphics.Bitmap.Config.HARDWARE, false)?.also { bmp.recycle() } ?: bmp).asImageBitmap()
            }
        }
    private const val EDGE_DP = 8f

    @androidx.annotation.VisibleForTesting @Volatile var last: CcSheet? = null
    @androidx.annotation.VisibleForTesting @Volatile var recordLast = false
}

/**
 * The material for one open: the backdrop's baked sheet ([baked], owned by the backdrop) placed on a root of
 * [root] pixels, or, with none, the one [flat] colour. Making one does no pixel work.
 */
@androidx.compose.runtime.Stable
internal class CcSheet(val baked: GlassMatch.PanelSheet?, val root: androidx.compose.ui.unit.IntSize, val light: Boolean = false) {
    val bitmap: android.graphics.Bitmap? get() = baked?.bitmap
    /** P316: the same material as [o] (each open wraps the one backdrop bitmap anew), so tiles keep what they baked from it. */
    fun sameAs(o: CcSheet?): Boolean = o != null && (o === this || (o.root == root && o.light == light && o.baked?.bitmap === baked?.bitmap &&
        o.baked?.left == baked?.left && o.baked?.top == baked?.top && o.baked?.right == baked?.right && o.baked?.bottom == baked?.bottom))
    /** The sheet's pixels (tests only: kept when [GlassMatch.keepPixels] is on). */
    val pixels: GlassMatch.Sheet? get() = baked?.pixels
    /** The one colour used when there is no baked sheet. */
    private val flat: Color get() = CcMaterial.fallback
    /** The window rectangle the sheet covers. */
    val panel: androidx.compose.ui.geometry.Rect = baked?.let {
        androidx.compose.ui.geometry.Rect(it.left * root.width, it.top * root.height, it.right * root.width, it.bottom * root.height)
    } ?: androidx.compose.ui.geometry.Rect.Zero

    private fun shader(tx: Float, ty: Float, then: (android.graphics.Matrix.() -> Unit)? = null): android.graphics.BitmapShader? = bitmap?.takeIf { !panel.isEmpty }?.let {
        android.graphics.BitmapShader(it, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setScale(panel.width / it.width, panel.height / it.height); postTranslate(panel.left + tx, panel.top + ty); then?.invoke(this) })
        }
    }
    /**
     * How light the sheet is (0..1, mean luminance of its small bitmap): over a light scene (a white app) a black
     * bevel reads as grey halos around the tiles (P38), so the shade follows it.
     */
    /** The sheet in window coordinates, for the bubble. */
    /** The sheet's one flat colour, for a software canvas, which can't draw the (hardware) sheet bitmap. */
    val flatBrush: androidx.compose.ui.graphics.Brush get() = androidx.compose.ui.graphics.SolidColor(flat)
    val brush: androidx.compose.ui.graphics.Brush by lazy { shader(0f, 0f)?.let { androidx.compose.ui.graphics.ShaderBrush(it) } ?: androidx.compose.ui.graphics.SolidColor(flat) }
    /** The sheet for a surface whose top-left is at [origin] in the window (its own coordinates start there). */
    fun brushAt(origin: androidx.compose.ui.geometry.Offset, height: Float, moved: CcEmergeFrame? = null, slotCentre: androidx.compose.ui.geometry.Offset? = null): androidx.compose.ui.graphics.Brush {
        // A shape drawn moved and scaled (the open's choreography, [CcEmerge]) maps the sheet back through that
        // transform, so its glass shows what is behind where it is now, not a picture it carries along.
        val sheet = (if (moved == null || slotCentre == null) shader(-origin.x, -origin.y) else shader(0f, 0f) {
            postTranslate(-(slotCentre.x + moved.dx), -(slotCentre.y + moved.dy))
            postScale(1f / moved.scale.coerceAtLeast(0.01f), 1f / moved.scale.coerceAtLeast(0.01f))
            postTranslate(slotCentre.x - origin.x, slotCentre.y - origin.y)
        }) ?: return androidx.compose.ui.graphics.SolidColor(flat)
        // P139: the sheet alone. Its bevel is a second plain draw ([bevelBrush]), never a ComposeShader of the two: that
        // dropped the platter for a frame on Fire OS's HWUI during focus blends.
        return androidx.compose.ui.graphics.ShaderBrush(sheet)
    }

    /**
     * The tiles' bevel, drawn over the sheet in the same node (P139: two plain draws, not a ComposeShader; one
     * gradient, four stops, P71 showed the stop count is paid per pixel): a sheen along the top edge and an inner
     * shadow that starts at 70% and deepens to the bottom, so a tile reads as a convex bubble. The sheen ends above
     * 15% of the height on purpose: the tests (and the one-material rule) read a tile's colour at 15-22% of its
     * height, which stays "the sheet" there.
     */
    fun bevelBrush(height: Float): androidx.compose.ui.graphics.Brush = androidx.compose.ui.graphics.ShaderBrush(
        android.graphics.LinearGradient(
            0f, 0f, 0f, height,
            intArrayOf(Color.White.copy(alpha = 0.14f).toArgb(), 0, 0, BEVEL_SHADE.toArgb()),
            floatArrayOf(0f, 0.14f, 0.70f, 1f), android.graphics.Shader.TileMode.CLAMP,
        ),
    )
}

/**
 * Whether this draw goes to the GPU. Control Center's glass is a hardware bitmap, which a software canvas (a picture
 * of the screen taken by drawing it in software) refuses with an exception; Glass crashed that way (P66).
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.hardwareCanvas(): Boolean =
    drawContext.canvas.nativeCanvas.isHardwareAccelerated

/** The tiles' bottom bevel: one faint shade (P188: no luminance branch; the glass is always dark now). */
internal val BEVEL_SHADE = Color.Black.copy(alpha = 0.12f)

/**
 * Holds the open's [CcSheet]. Provided once (it never changes identity), and read only while drawing, so taking or
 * dropping a sheet redraws the surfaces instead of recomposing the whole panel.
 */
@androidx.compose.runtime.Stable
internal class CcSheetRef {
    var sheet by androidx.compose.runtime.mutableStateOf<CcSheet?>(null)
}

internal val LocalCcSheet = androidx.compose.runtime.staticCompositionLocalOf<CcSheetRef?> { null }

/** A Control Center surface: the open's sheet in [shape] with the one thin rim; one draw, no texture of its own. */
internal fun Modifier.ccSurface(shape: Shape, sheet: CcSheetRef?): Modifier = this then CcSurfaceElement(shape, sheet)

private data class CcSurfaceElement(val shape: Shape, val sheet: CcSheetRef?) : androidx.compose.ui.node.ModifierNodeElement<CcSurfaceNode>() {
    override fun create() = CcSurfaceNode(shape, sheet)
    override fun update(node: CcSurfaceNode) { node.shape = shape; node.ref = sheet; node.reset(); node.invalidateDraw() }
}

private class CcSurfaceNode(var shape: Shape, var ref: CcSheetRef?) : Modifier.Node(), androidx.compose.ui.node.DrawModifierNode, androidx.compose.ui.node.GlobalPositionAwareModifierNode,
    androidx.compose.ui.node.CompositionLocalConsumerModifierNode {
    // P203 let a closed Control Center's tiles drop their baked images with its sheet. P316 keeps them (13 small images,
    // ~1 MB): re-baking all of them after every open drew ~130 ms of extra frames once the motion had ended. The next
    // open's sheet replaces them only if it is a different material (sameAs, in draw).
    private var origin: androidx.compose.ui.geometry.Offset? = null
    private var cachedSize = androidx.compose.ui.geometry.Size.Unspecified
    private var outline: androidx.compose.ui.graphics.Outline? = null
    private var brush: androidx.compose.ui.graphics.Brush? = null
    private var brushFor: CcSheet? = null
    private var rim: androidx.compose.ui.graphics.Brush? = null
    private var bevel: androidx.compose.ui.graphics.Brush? = null
    private var edge: androidx.compose.ui.graphics.ImageBitmap? = null
    private var baked: androidx.compose.ui.graphics.ImageBitmap? = null

    fun reset() { brush = null; outline = null; rim = null; bevel = null; edge = null; unbake() }

    override fun onGloballyPositioned(coordinates: androidx.compose.ui.layout.LayoutCoordinates) {
        // The tile's resting top-left, not its scaled one (P188): inside a focused tile's x1.21 layer, positionInWindow is
        // the scaled corner, so the sheet was sampled from a different (lighter) patch of the backdrop and the focused
        // tile read as a lighter, greyer glass. The centre doesn't move under a centred scale: rest = centre - size/2.
        val c = coordinates.localToWindow(androidx.compose.ui.geometry.Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
        val p = androidx.compose.ui.geometry.Offset(c.x - coordinates.size.width / 2f, c.y - coordinates.size.height / 2f)
        // A focused tile's 1.02 lift moves its corner a couple of pixels each frame of the scale; the sheet's cells
        // are ~12 px and blurred, so that is not worth new shaders every frame. Only a real move re-maps it.
        val o = origin
        if (o == null || kotlin.math.abs(p.x - o.x) > MOVE_PX || kotlin.math.abs(p.y - o.y) > MOVE_PX) { origin = p; brush = null; unbake(); invalidateDraw() }
    }

    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        if (cachedSize != size || outline == null) { cachedSize = size; outline = shape.createOutline(size, layoutDirection, this); brush = null; rim = null; bevel = null; edge = null; unbake() }
        val rest = outline ?: return drawContent()
        // Read while drawing: a new sheet redraws this surface (and only that).
        val sheet = ref?.sheet
        if (sheet !== brushFor) { if (sheet == null || !sheet.sameAs(brushFor)) { brush = null; bevel = null; unbake() }; brushFor = sheet }
        // While the open's choreography moves this shape, its glass is mapped through the move every frame (and a
        // rounded tile starts as a capsule); at rest the cached brush and outline are used.
        val emerge = currentValueOf(LocalCcEmerge)
        val moved = emerge?.now()
        val o = if (moved != null && moved.round < 1f) ccRounding(rest, size, moved.round) else rest
        val b = if (moved != null && sheet != null) sheet.brushAt(origin ?: androidx.compose.ui.geometry.Offset.Zero, size.height, moved, emerge.slot?.center)
            else brush ?: (sheet?.brushAt(origin ?: androidx.compose.ui.geometry.Offset.Zero, size.height) ?: androidx.compose.ui.graphics.SolidColor(CcMaterial.fallback)).also { brush = it }
        // P201: at rest a tile's four layers (fill, sheet, bevel, edge) are one image, rasterised once by the GPU
        // (Bitmap.createBitmap(Picture)) and redrawn only when its sheet, place or size changes: the four draws cost
        // ~7 ms a frame across Control Center's tiles on the stick. The open's moving frames draw the layers live.
        // The bake runs off the main thread (a synchronous GPU render per tile, all tiles at once when the open
        // settles, was 80-190 ms frames); until it lands the tile draws its layers live, the same pixels.
        if (moved == null && sheet != null && hardwareCanvas()) {
            baked?.let { drawImage(it); drawContent(); return }
            if (baking == null && !bakeFailed) bakeLater(rest, b, sheet)
        }
        // P188: the one dark fill first, always, so nothing behind can show through a frame where the sheet isn't
        // there yet, samples outside itself mid-move, or is drawn by a canvas that can't draw it. The sheet (capped
        // dark, CcAdapt) goes over it: the same material in every state and on every backdrop.
        drawOutline(o, androidx.compose.ui.graphics.SolidColor(CcMaterial.fallback))
        if (hardwareCanvas()) drawOutline(o, b)
        if (hardwareCanvas() && dev.glasslauncher.glass.CcAdapt.variant == "E") drawOutline(o, E_WASH)
        if (sheet != null && hardwareCanvas()) drawOutline(o, bevel ?: sheet.bevelBrush(size.height).also { bevel = it })
        // P195: Liquid Glass's edge (rom/research/liquid-glass-recipe.md §4A): the soft inner shadow and the lit rim
        // depend only on the shape, so they are baked once per shape and size into one bitmap and drawn as one image
        // (P92: stroked round paths were re-rasterised at every focus scale, ~10 ms a frame on the stick).
        // While the open grows a tile, the baked edge fades in with it and the live rim (of the morphing shape) out.
        val k = if (moved == null) 1f else ((moved.scale - 0.5f) / 0.5f).coerceIn(0f, 1f)
        if (hardwareCanvas() && k > 0f) drawImage(edge ?: CcMaterial.edge(shape, size, layoutDirection, this).also { edge = it }, alpha = k)
        if (k < 1f) drawOutline(o, rim ?: CcMaterial.rimBrush(size).also { rim = it }, alpha = 1f - k, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
        drawContent()
    }

    private var baking: kotlinx.coroutines.Job? = null
    // A bake that failed isn't retried every frame; the live layers draw until the sheet, place or size changes.
    private var bakeFailed = false

    private fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.bakeLater(o: androidx.compose.ui.graphics.Outline, b: androidx.compose.ui.graphics.Brush, sheet: CcSheet) {
        val sz = size; val dir = layoutDirection; val d = androidx.compose.ui.unit.Density(density, fontScale)
        val bv = bevel ?: sheet.bevelBrush(sz.height).also { bevel = it }
        val ed = edge ?: CcMaterial.edge(shape, sz, dir, this).also { edge = it }
        baking = coroutineScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val img = runCatching {
                val w = kotlin.math.ceil(sz.width).toInt(); val h = kotlin.math.ceil(sz.height).toInt()
                if (w <= 0 || h <= 0) null else {
                    val picture = android.graphics.Picture()
                    val canvas = androidx.compose.ui.graphics.Canvas(picture.beginRecording(w, h))
                    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(d, dir, canvas, sz) {
                        drawOutline(o, androidx.compose.ui.graphics.SolidColor(CcMaterial.fallback))
                        drawOutline(o, b)
                        if (dev.glasslauncher.glass.CcAdapt.variant == "E") drawOutline(o, E_WASH)
                        drawOutline(o, bv)
                        drawImage(ed)
                    }
                    picture.endRecording()
                    android.graphics.Bitmap.createBitmap(picture).asImageBitmap()
                }
            }.getOrNull()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                baked = img; bakeFailed = img == null; baking = null; invalidateDraw()
            }
        }
    }

    /** Drops the baked image and any bake in flight (its sheet, place or size changed). */
    private fun unbake() { baking?.cancel(); baking = null; baked = null; bakeFailed = false }

    private companion object { const val MOVE_PX = 4f }
}

/**
 * [rest] (a shape's own outline) part-way from a capsule: [round] 0 is a capsule (half the short side), 1 its own
 * corners. Only rounded rectangles change; circles and capsules already are one.
 */
internal fun ccRounding(rest: androidx.compose.ui.graphics.Outline, size: androidx.compose.ui.geometry.Size, round: Float): androidx.compose.ui.graphics.Outline {
    val rr = (rest as? androidx.compose.ui.graphics.Outline.Rounded)?.roundRect ?: return rest
    val capsule = minOf(size.width, size.height) / 2f
    val own = rr.topLeftCornerRadius.x
    if (own >= capsule) return rest
    val k = round.coerceIn(0f, 1f)
    val r = capsule + (own - capsule) * k * k
    return androidx.compose.ui.graphics.Outline.Rounded(androidx.compose.ui.geometry.RoundRect(rr.left, rr.top, rr.right, rr.bottom, androidx.compose.ui.geometry.CornerRadius(r)))
}

/** Set when the open lands: a focus fill that appears while Control Center is still opening snaps instead of fading (a tile is never half-white). */
internal class CcPhase { var landed = false }
internal val LocalCcPhase = androidx.compose.runtime.staticCompositionLocalOf { CcPhase() }

/** How much Control Center mutes the screen behind it (tvOS 27 dims rather than blurs); its sheet is baked muted by the same amount. */
internal const val CC_DIM_ALPHA = GlassMatch.CC_DIM
/** The time, date and weather share one opacity, slightly muted, as tvOS's header. */
private const val HEADER_ALPHA = 0.6f
/** P137: how long the labels take to change ink when the backdrop flips light/dark under an open Control Center. */
private const val INK_FADE_MS = 250
private val CC_DIM_COLOR = Color.Black.copy(alpha = CC_DIM_ALPHA)
private val CC_DIM_COLOR_LIGHT = Color.Black.copy(alpha = GlassMatch.CC_DIM_LIGHT)

/** Control Center tiles that can be turned off in Settings › Control Center (id to label). */
val CONTROL_CENTER_TILES = listOf(
    "alexa" to "Alexa Page", "wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "launcher" to "Launcher Settings", "airplay" to "AirPlay",
    "controllers" to "Game Controllers", "remotes" to "Remotes", "appearance" to "Theme", "screensaver" to "Screen Saver",
    "switcher" to "App Switcher", "sleep_timer" to "Sleep Timer", "vpn" to "VPN",
    "cleanup" to "Clean Up", "restart" to "Restart", "search" to "Search", "accessibility" to "Accessibility",
)

/**
 * SYS-01, laid out like tvOS 27 Control Center: the time over a right-hand column of glass tiles.
 * A large Settings tile for the TV's own settings (white when focused, focused first), two-line pills
 * for Wi-Fi, Bluetooth and the launcher's settings, then round buttons for game controllers, the theme,
 * the screen saver, the app switcher and AirPlay (when PhairPlay is installed). Glass tiles
 * take their tint from the content behind them; a pill's icon sits in a white disc while it's on.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ControlCenter(edit: ((LauncherConfig) -> LauncherConfig) -> Unit, cfg: LauncherConfig, active: Boolean, open: (Overlay) -> Unit, closeAll: () -> Unit, closeTop: () -> Unit = closeAll) {
    // Only while it is open: a closed Control Center that stays composed (Home parks it, P203; its window keeps it
    // warm) must not hold device banners back.
    val shown = !LocalOverlayExiting.current
    DisposableEffect(shown) {
        if (shown) dev.glasslauncher.system.RemoteNotice.controlCenterOpen = true
        onDispose {
            if (shown) {
                dev.glasslauncher.system.RemoteNotice.controlCenterOpen = false
                // Drawn in Home's window, so the accessibility window is dead weight and a layer (P307). Done here
                // because closing CC does not reliably produce a WINDOW_STATE_CHANGED to hang it on.
                dev.glasslauncher.system.RemoteKeysService.instance?.controlCenter?.detach()
            }
        }
    }
    // One look in either theme, like the pill and tray: white text and glyphs on clear glass over the
    // dimmed screen (tvOS 27 Control Center isn't themed light or dark).
    val outer = LocalPalette.current
    val palette = remember(outer.highContrast) { dev.glasslauncher.ui.Palette(light = false, highContrast = outer.highContrast) }
    // Scrolling moves by whole rows (a row is a cell plus a gap), so a half-cut row never sits at the panel's edge.
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { val k = CcSizes(cfg.textScale); (k.round + k.gap).toPx() }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPalette provides palette,
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(rowPx) { CcRowSnap(rowPx) },
    ) {
        ControlCenterBody(edit, cfg, active, open, closeAll, closeTop)
    }
}

/** Brings a focused tile into view by a whole number of rows, so the tiles always sit on the grid's rows. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private class CcRowSnap(private val row: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset < 0f -> -kotlin.math.ceil(-offset / row) * row
            end > containerSize -> kotlin.math.ceil((end - containerSize) / row) * row
            else -> 0f
        }
    }
}

@Composable
private fun ControlCenterBody(edit: ((LauncherConfig) -> LauncherConfig) -> Unit, cfg: LauncherConfig, active: Boolean, open: (Overlay) -> Unit, closeAll: () -> Unit, closeTop: () -> Unit) {
    val context = LocalContext.current
    val m = LocalMetrics.current
    val palette = LocalPalette.current
    val first = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) { withFrameNanos { }; runCatching { first.requestFocus() } } }
    val screen = dev.glasslauncher.ui.LocalScreenDissolve.current

    // P203: these are read as Control Center opens, never while it is closed or parked (Home keeps it composed).
    val network by produceState("", active) { if (active) { CcOpenReads.count++; value = withContext(Dispatchers.IO) { SystemControls.network(context) } } }
    val wifiOn = rememberWhileOpen(active, false) { SystemControls.wifiConnected(context) }
    val bluetooth = rememberWhileOpen(active, false) { SystemControls.bluetoothOn() }
    val clock = rememberClock(cfg.clock24h, running = active)
    val headerStyle = Type.caption
    val sz = remember(cfg.textScale) { CcSizes(cfg.textScale) }
    val rtl = dev.glasslauncher.ui.isRtl()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // The layout (P83): the stored one, or while edit mode is on the draft being changed (saved when it ends).
    // The Alexa page is only there where the Alexa app is (P155): a saved layout keeps it, but it is not drawn without the app.
    val hasAlexa = remember { dev.glasslauncher.system.DeviceProfile.hasAlexa(context.packageManager) }
    val storedLayout = remember(cfg.ccLayout, cfg.ccHidden, hasAlexa) { CcLayout.resolve(cfg.ccLayout, cfg.ccHidden).let { if (hasAlexa) it else it.withShown("alexa", false) } }
    var draft by remember { androidx.compose.runtime.mutableStateOf<CcLayout?>(null) }
    val layout = draft ?: storedLayout
    val editing = draft != null
    fun shown(id: String) = layout.shown(id)
    // Pages (tvOS 27's icons along the top): 0 = Controls, 1 = Alexa. Opening always shows Controls.
    var pageId by remember(active) { androidx.compose.runtime.mutableStateOf("controls") }
    // While a lifted tile is held over the menu bar (Up from the top row): the page it will drop onto.
    var hoverPage by remember(active) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // The Add a Control gallery (null = closed, "" = the groups, else a group key): the + page icon opens it too.
    var gallery by remember(active) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // The tile lifted in edit mode (the hint bar, outside the scrolling tiles, reads it).
    var lifted by remember(active) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Showcase add-on tiles (debug flag): the Agents card, in place of the grid like the gallery.
    CcShowcase.enabled = cfg.ccDemoTiles || rememberWhileOpen(active, false) { CcShowcase.flag(context) }
    // Real add-ons (A1): their tiles are read once per open; the add-on is bound for that call only.
    LaunchedEffect(active, cfg.addonsEnabled) { if (active) dev.glasslauncher.addons.AddonHost.refresh(context, cfg.addonsEnabled) }
    val agents = remember { CcAgentState() }
    var agentCard by remember(active) { androidx.compose.runtime.mutableStateOf(false) }
    // Restart's confirm is drawn here, over the grid (an overlay opened after Control Center closes was lost).
    var restartAsk by remember(active) { androidx.compose.runtime.mutableStateOf(false) }
    // Remotes opens as a platter pushed over the grid, inside the panel (tvOS pushes modules; P111), not a full page.
    var remotesPlatter by remember(active) { androidx.compose.runtime.mutableStateOf(false) }
    // AirPlay is a menu pill, like tvOS's Audio pill: it opens a platter (receiver on/off + status) instead of
    // toggling in place (P129, REA: AirPlay has no on-state of its own in Control Center).
    var airplayPlatter by remember(active) { androidx.compose.runtime.mutableStateOf(false) }
    // Game Controllers is a platter like Remotes (P146): the connected controllers, then Controller Settings and Back.
    var controllersPlatter by remember(active) { androidx.compose.runtime.mutableStateOf(false) }
    // The Pair page inside the Remotes / Game Controllers platter (P174): Back returns to the platter.
    var devicePairing by remember(active) { androidx.compose.runtime.mutableStateOf(false) }
    // The name of the device that just paired: the list takes focus to it once it shows (P186).
    var justPaired by remember(active) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val platterSource = remember { PlatterSource() }
    // Edit mode's hint (P101): a small glass bar at the bottom centre, up while edit mode is on (P152).
    var hintShown by remember(active) { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (!editing) { hintShown = false; return@LaunchedEffect }
        // Stays up for the whole of edit mode (P152): it is the only place that says Menu resizes, and it went away after 3 s.
        hintShown = true
    }
    // A control that should take focus once it is on screen (a new tile, the one a card or confirm came from).
    var pendingFocus by remember(active) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Page icons show with more than one page, or while editing (the + icon makes a page).
    val alexaPage = layout.pages.size > 1 || editing
    // The focused round button's name, shown under the round buttons (they're icons only).
    var roundLabel by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val dark = cfg.theme != ThemeMode.Light
    // Null without PhairPlay: the pill never shows then. The platter and Settings read the same hub.
    val airPlay = dev.glasslauncher.system.AirPlay.rememberAirPlay(active).takeIf { it.first.availability != dev.glasslauncher.system.AirPlayAvailability.Absent }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rooted by produceState(dev.glasslauncher.system.Root.known, active) { if (active) value = dev.glasslauncher.system.Root.available() }
    var freed by androidx.compose.runtime.remember(active) { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    // Clean Up's result line ("cleaning…", then what it freed, for a few seconds).
    var cleaned by androidx.compose.runtime.remember(active) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Sleep Timer: the end is stored; the pill re-reads it twice a minute (never per frame).
    val sleepNow = rememberWhileOpen(active, 0L) { dev.glasslauncher.system.SleepTimer.remainingMs(context) }
    var sleepLeft by androidx.compose.runtime.remember(active) { androidx.compose.runtime.mutableStateOf(sleepNow) }
    LaunchedEffect(active) { while (active) { sleepLeft = dev.glasslauncher.system.SleepTimer.remainingMs(context); kotlinx.coroutines.delay(30_000) } }
    val vpnConnected = dev.glasslauncher.system.VpnControl.rememberConnected(active)
    val vpnApps = rememberWhileOpen(active, emptyList()) { dev.glasslauncher.system.VpnControl.apps(context) }

    fun system(go: () -> Boolean) { closeAll(); go() }

    // Grows out of the status pill (P49, [CcMorph]): the pill splits into two drops that become the page icons, and
    // every control grows out of the nearer drop, at full opacity. Closing runs the same path back. One Animatable
    // drives every shape and the dim in both directions, so Back mid-open reverses from wherever it is. The tiles'
    // layout never changes: each is drawn moved and scaled ([ccEmerge]), with nothing clipping the panel.
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ControlCenterWindow.pillCovered = false } }
    val exiting = LocalOverlayExiting.current
    val reduceMotion = dev.glasslauncher.ui.LocalUiPrefs.current.reduceMotion
    // Edit mode saves its draft when Control Center closes, and if the window goes away some other way. (Here, not
    // in a page: a page switch disposes the page's content.)
    fun saveDraft() {
        draft?.let { d -> val l = d.withoutEmptyPages(); edit { c -> c.copy(ccLayout = l.toJson(), ccHidden = emptySet()) } }
        draft = null
    }
    LaunchedEffect(exiting) { if (exiting && draft != null) saveDraft() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { saveDraft() } }
    var liftBase by remember(active) { androidx.compose.runtime.mutableStateOf<CcLayout?>(null) }
    fun commitDraft() {
        saveDraft(); lifted = null; liftBase = null
        // Opened from Settings › Control Center › Edit Layout: Done (or Back) returns there.
        if (CcEditEntry.fromSettings) { CcEditEntry.fromSettings = false; CcEditEntry.returning = true; closeTop() }
    }
    LaunchedEffect(active) {
        if (active && CcEditEntry.startInEdit) {
            CcEditEntry.startInEdit = false
            if (draft == null) draft = storedLayout
        }
    }
    fun cancelLift() { liftBase?.let { draft = it }; lifted = null; liftBase = null }
    // Back: closes the gallery (a level at a time), drops a lift, else saves and leaves edit mode (Home and
    // closing Control Center save too).
    androidx.compose.runtime.DisposableEffect(editing, lifted, gallery, agentCard, restartAsk, remotesPlatter, airplayPlatter, controllersPlatter, devicePairing) {
        val mine: (() -> Boolean)? = if (editing || agentCard || restartAsk || remotesPlatter || airplayPlatter || controllersPlatter) ({
            when {
                devicePairing -> devicePairing = false
                airplayPlatter -> { airplayPlatter = false; pendingFocus = "airplay" }
                remotesPlatter -> { remotesPlatter = false; pendingFocus = "remotes" }
                controllersPlatter -> { controllersPlatter = false; pendingFocus = "controllers" }
                restartAsk -> { restartAsk = false; pendingFocus = "restart" }
                // Back on the permission card is Deny.
                agentCard -> { agents.waiting = 0; agents.result = "Denied"; agentCard = false; pendingFocus = "addon:claude/agents" }
                gallery == "" -> gallery = null
                gallery != null -> gallery = ""
                lifted != null -> cancelLift()
                else -> commitDraft()
            }
            true
        }) else null
        ControlCenterWindow.editBack = mine
        // A page switch disposes the old page's effect after the new one set its own: only clear what is ours.
        onDispose { if (ControlCenterWindow.editBack === mine) ControlCenterWindow.editBack = null }
    }
    val exitingNowEarly = androidx.compose.runtime.rememberUpdatedState(exiting)
    val exitingNow = exitingNowEarly
    // Where the close began (read once, here, so it isn't a per-frame dependency): the bubble turns from there.
    val closeFrom = remember(exiting) { if (exiting) androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { enter.value }.coerceIn(0.01f, 1f) else 1f }
    val phase = remember { CcPhase() }
    val closeFromNow = androidx.compose.runtime.rememberUpdatedState(closeFrom)
    val reduceMotionNow = androidx.compose.runtime.rememberUpdatedState(reduceMotion)
    val motion = remember {
        CcMotion({ CcMorph.bubble(enter.value, exitingNowEarly.value, closeFromNow.value) }, { !reduceMotionNow.value })
    }
    val glassState = LocalBackdrop.current
    var panel by remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // The material of this open: the backdrop's own sheet, baked with the backdrop (it covers the right half of
    // the screen, so every page and text size), taken as the open starts and never re-keyed by a later backdrop
    // swap: a tile never changes colour once it has appeared. Nothing is baked or waited for here.
    val sheetRef = remember { CcSheetRef() }
    val pill = ControlCenterWindow.pillBounds
    // P137: the labels' ink follows the sheet in use (0 white, 1 dark): it lands with the sheet at open, and crossfades
    // (250 ms) if the backdrop flips while Control Center is open.
    // P173 (user): one Control Center style, clear glass with white labels over any scene; no light variant.
    val inkTarget = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val inkAnim = remember { androidx.compose.animation.core.Animatable(inkTarget.floatValue) }
    val inkPalette = remember(palette, inkAnim.value) { palette.copy(ink = inkAnim.value) }
    val headerColor = inkPalette.primary
    // The time sits on the dim scrim, not the sheet: over a light scene the scrim leaves ~Y 0.3, where dark ink needs full
    // opacity for 4.5:1 (5.7:1), so the muted alpha fades to 1 with the ink.
    val headerAlpha = HEADER_ALPHA + (1f - HEADER_ALPHA) * inkAnim.value
    fun takeSheet() {
        val root = glassState.rootSize
        // P206 E (user: "the dock's glass is better"): the dock's own clear texture in its text-safe range (clearLegible: lightly blurred art, its colour
        // kept), the one the tray samples, instead of Control Center's neutral heavy-blur sheet. Taken once per open, so
        // it stays still while Control Center is up, as the sheet did.
        // Reduce Transparency is the one accessibility setting that must reach EVERY surface, and Control Center's
        // glass is drawn from this sheet rather than through Glass, so it never consulted the flag (P93 #8): with the
        // setting on, the dock went solid and Control Center stayed frosted. A null material makes CcSheet fall back
        // to its one flat colour, which is the same solid the panels use, so the two agree.
        val material = if (glassState.reduceTransparency) null
        else if (dev.glasslauncher.glass.CcAdapt.variant == "E") glassState.backdrop?.let { b ->
            val bmp = b.clearLegible.asAndroidBitmap()
            // Tests read the material's pixels (GlassMatch.keepPixels); a hardware bitmap can't be read, the JVM's can.
            val px = if (GlassMatch.keepPixels && bmp.config != android.graphics.Bitmap.Config.HARDWARE)
                GlassMatch.Sheet(IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }, bmp.width, bmp.height) else null
            GlassMatch.PanelSheet(bmp, 0f, 0f, 1f, 1f, px)
        } else glassState.backdrop?.sheetFor(dev.glasslauncher.glass.CcAdapt.variant)
        sheetRef.sheet = if (root == androidx.compose.ui.unit.IntSize.Zero) null else CcSheet(material, root, light = false)
        inkTarget.floatValue = 0f
        if (CcMaterial.recordLast) CcMaterial.last = sheetRef.sheet
    }
    // The window keeps this composition across opens, so everything per open starts here, not in remember.
    LaunchedEffect(exiting) {
        if (!exiting) {
            phase.landed = false
            // Re-opening during a close that is still visible keeps the sheet it has; otherwise take the backdrop's.
            if (sheetRef.sheet == null || enter.value < 0.02f) takeSheet()
            // The window's first full-size frame lays the tree out; the spring starts on the next, so the two don't stack on one
            // frame (that frame was 33-47 ms of layout plus 8-19 ms of animation on the stick, P253).
            if (enter.value < 0.02f) androidx.compose.runtime.withFrameNanos { }
        }
        // Reduce Motion: no growing bubble, a plain fade (a bounce would pulse the opacity).
        if (reduceMotion) enter.animateTo(if (exiting) 0f else 1f, dev.glasslauncher.ui.Motion.overlay())
        else if (exiting) enter.animateTo(0f, CcMorph.closeSpring)
        else enter.animateTo(1f, CcMorph.openSpring)
        phase.landed = true
        if (exiting) ControlCenterWindow.pillCovered = false
        // Closed: let go of this open's sheet (the cached composition would otherwise hold it until the next open).
        if (exiting && enter.value < 0.02f) { sheetRef.sheet = null; CcMaterial.last = null }
    }
    // A backdrop (or the window's size) that arrives before the open has visibly started is still taken; once the
    // bubble is under way the open keeps what it has.
    LaunchedEffect(Unit) {
        // Keyed on the backdrop, not its sheet: the default variant (E) has no sheet and takes the backdrop's clear texture.
        var taken: dev.glasslauncher.glass.Backdrop? = null
        androidx.compose.runtime.snapshotFlow { glassState.backdrop to glassState.rootSize }.collect { (offered, root) ->
            val have = sheetRef.sheet
            if (!exitingNow.value && enter.value < 0.02f && offered != null && (offered !== taken || have?.root != root)) { takeSheet(); taken = offered }
        }
    }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { inkTarget.floatValue }.collectLatest { t ->
            if (enter.value < 0.02f) inkAnim.snapTo(t) else inkAnim.animateTo(t, androidx.compose.animation.core.tween(INK_FADE_MS))
        }
    }
    // The column is as tall as the screen allows on every page (P138 P1: tvOS's platters fill it), but the open's stagger measures
    // its reach from where the controls end, not from the empty rest of the column: the motion's panel is clamped to the content.
    val colBounds = remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val contentBottom = remember { floatArrayOf(0f, 0f) }  // [0] the page or platter, [1] the Now Playing card (0 = none)
    fun syncMotionPanel() {
        val b = colBounds.value ?: return
        val end = maxOf(contentBottom[0], contentBottom[1])
        val bottom = if (end > b.top) minOf(b.bottom, end) else b.bottom
        motion.panel = androidx.compose.ui.geometry.Rect(b.left, b.top, b.right, bottom)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides inkPalette, LocalCcSizes provides sz, LocalCcSheet provides sheetRef, LocalCcPhase provides phase, LocalCcMotion provides motion) {
    // P202: inside Glass, Home under Control Center is the sharp capture taken as it opened, and Home itself isn't
    // drawn meanwhile (as for folders, SnapshotBackdrop): Home's whole tree was replayed under the tiles every frame.
    // Its glass is frozen while Control Center is open anyway (P188). As Control Center leaves, Home draws again.
    val homeState = dev.glasslauncher.glass.LocalBackdrop.current
    // Not in Motion mode or mid-crossfade between scenes: there the backdrop alone can't stand in for the chrome
    // (P207 below), so Home stays drawn as before P202.
    val homeShot = homeState.overlaySharp.takeIf { cfg.background != dev.glasslauncher.data.BackgroundMode.Motion && homeState.previous == null }
    androidx.compose.runtime.DisposableEffect(homeShot, exiting) {
        val hide = homeShot != null && !exiting
        if (hide) homeState.homeHidden = true
        onDispose { if (hide) homeState.homeHidden = false }
    }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            if (homeShot != null && !exiting) {
                drawImage(homeShot, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
                // P207: the capture holds Home's status pill and search circle as they were. Once the capsule covers
                // them (the moment the live pill fades, ControlCenterWindow.pillCovered), the scene behind them is
                // drawn over that part of the capture, so Control Center's header isn't drawn on top of them.
                val chrome = ControlCenterWindow.chromeBounds
                // The same ladder step Home draws at its current blur (dock: sharp; grid: blurred).
                val scene = homeState.backdrop?.ladder?.let { l -> l[(homeState.wallpaperBlur.value * l.lastIndex).roundToInt().coerceIn(0, l.lastIndex)] }
                if (ControlCenterWindow.pillCovered && chrome != null && scene != null) {
                    val pad = 16.dp.toPx()
                    val r = androidx.compose.ui.geometry.Rect(chrome.left - pad, chrome.top - pad, chrome.right + pad, chrome.bottom + pad)
                        .intersect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    val sx = scene.width / size.width; val sy = scene.height / size.height
                    drawImage(
                        scene,
                        srcOffset = androidx.compose.ui.unit.IntOffset((r.left * sx).toInt(), (r.top * sy).toInt()),
                        srcSize = androidx.compose.ui.unit.IntSize((r.width * sx).toInt().coerceAtLeast(1), (r.height * sy).toInt().coerceAtLeast(1)),
                        dstOffset = androidx.compose.ui.unit.IntOffset(r.left.toInt(), r.top.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(r.width.toInt(), r.height.toInt()),
                    )
                }
            }
            // tvOS 27 mutes what's behind with a dark wash rather than blurring it: one translucent rect whose alpha
            // follows the spring (drawn, not a layer with alpha, which would be a full-screen offscreen pass), and
            // over another app (an overlay window) the system composites it without redrawing anything.
            val e = enter.value.coerceIn(0f, 1f)
            if (e > 0f) drawRect(if (dark) CC_DIM_COLOR else CC_DIM_COLOR_LIGHT, alpha = e)
            // The open's material from its very first frame: the backdrop is in place before Control Center
            // attaches, but the effect that takes the sheet only runs after a frame has drawn (bare icons, P29).
            if (!exiting && sheetRef.sheet == null) takeSheet()
            // Home's pill goes once this capsule covers it, not before (a frame of neither read as a blink).
            if (!exiting && (reduceMotion || sheetRef.sheet != null)) ControlCenterWindow.pillCovered = true
            // Closing: back at the pill's size (well before the window goes), Home's pill returns under the capsule.
            if (exiting && enter.value < PILL_BACK_AT) ControlCenterWindow.pillCovered = false
            // The drops start as the pill's two ends; over an app there is no pill.
            motion.pill = pill?.takeIf { ControlCenterWindow.homeStarted }
            if (reduceMotion) return@Canvas
            val material = sheetRef.sheet ?: return@Canvas
            drawCcDrops(motion, if (hardwareCanvas()) material.brush else material.flatBrush, alexaPage)
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .onGloballyPositioned {
                    val b = it.boundsInWindow()
                    panel = b
                    colBounds.value = b
                    syncMotionPanel()
                    // Where a lone drop settles when there are no page icons: where they would be.
                    val right = b.right - m.chromeInset.value * density - 14 * density
                    val top = b.top + m.chromeInset.value * density
                    motion.loneSpot = androidx.compose.ui.geometry.Rect(right - CC_PAGE_ICON.value * density, top, right, top + CC_PAGE_ICON.value * density)
                }
                // Reduce Motion: a plain fade of the whole panel. Never the tiles without their material (they would
                // show bare on the open's first frame).
                .graphicsLayer {
                    if (reduceMotion) { alpha = enter.value.coerceIn(0f, 1f); compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; return@graphicsLayer }
                    alpha = if (sheetRef.sheet == null) 0f else 1f
                }
                // Never over the dock: the panel ends where the tray begins, and its tiles scroll inside that.
                // The panel is the screen's full height down to the bottom inset (P138; tvOS's platter is 860 pt, FW
                // platterSizeForPlatterController): the grid page keeps its own height at the top of it and scrolls inside it,
                // a pushed platter (Remotes, AirPlay) fills it.
                .height(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - CC_BOTTOM_INSET)
                .padding(top = m.chromeInset, end = m.chromeInset + 14.dp - CC_BLEED)
                .trapFocus(active)
                .semantics { paneTitle = "Control Center" }
                .testTag("control-center"),
        ) {
            // One band above the tiles (tvOS 27): the time with seconds, the date and the weather on the
            // left, lined up with the tiles; the page icons on the right (focusing one shows its page).
            // One row on one midline (P196, tvOS): the time, the weather and the page icons are vertically centred together.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.width(sz.column + CC_BLEED * 2).padding(start = CC_BLEED + 4.dp, end = CC_BLEED, bottom = 16.dp - CC_BLEED),
            ) {
                // The time, date and weather grow out of the pill's clock (tvOS slides the time out of the pill).
                val headerEmerge = remember(motion) { CcEmerge(motion, CcRole.Header) }
                // In the gallery the time and the title sit over the bare cover art: they get the panel's glass behind them.
                Column(
                    Modifier.weight(1f).ccEmerge(headerEmerge).graphicsLayer { alpha = if (motion.enabled) CcMorph.headerAlpha(motion.p) else 1f }
                        .then(if (gallery != null) Modifier.ccSurface(RoundedCornerShape(18.dp), LocalCcSheet.current).padding(horizontal = 10.dp, vertical = 6.dp) else Modifier),
                ) {
                    // One line, small and muted, as tvOS 27's header: the time without seconds, then the weather when there is
                    // one. No date line (F03).
                    // The Add a Control gallery has a section title in the header's place, never the clock (P152).
                    if (gallery != null && !restartAsk && !agentCard) Text(
                        ccGalleryTitle(gallery), style = headerStyle.copy(fontWeight = FontWeight.SemiBold), color = headerColor,
                        maxLines = 1, softWrap = false, modifier = Modifier.testTag("cc-gallery-title"),
                    ) else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(clock, style = headerStyle.copy(fontFeatureSettings = "tnum"), color = headerColor.copy(alpha = headerAlpha), maxLines = 1, softWrap = false, modifier = Modifier.testTag("cc-clock"))
                        cfg.weather?.let { dev.glasslauncher.widgets.WeatherLabel(it, headerColor.copy(alpha = headerAlpha), Type.caption) }
                    }
                }
                // The page icons are the two drops the pill splits into; their glyphs come in as they land.
                // Three or more icons (a page of your own, or the + while editing) are a little smaller so the time keeps its room.
                val iconCount = layout.pages.size + if (editing) 1 else 0
                val iconSize = CC_PAGE_ICON
                androidx.compose.runtime.CompositionLocalProvider(LocalCcPageIconSize provides iconSize) {
                if (alexaPage) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 16.dp)) {
                    // The Alexa app's own icon (loaded once per open); the mic if it isn't installed.
                    val alexaIcon = rememberWhileOpen(active, null) { AlexaIcon.load(context.packageManager, 52)?.asImageBitmap() }
                    // Not editable, so out of the strip (and the walk to +) while editing.
                    layout.pages.filter { !(editing && it.id == "alexa") }.forEachIndexed { i, pg ->
                        val lit = pg.id == (hoverPage ?: pageId)
                        val show = { if (hoverPage == null) pageId = pg.id }
                        // Play/Pause on a page icon in edit mode deletes the page; its tiles go to Controls (nothing is lost).
                        val delete: (() -> Unit)? = if (editing && i > 0 && pg.id != "alexa") ({
                            draft = draft?.let { d -> d.page(pg.id)?.tiles.orEmpty().fold(d) { acc, t -> acc.moveToPage(pg.id, t.id, "controls") }.copy(pages = d.pages.filter { it.id != pg.id }) }
                            if (pageId == pg.id) pageId = "controls"
                        }) else null
                        if (pg.id == "alexa" && alexaIcon != null) PageIcon(ccPageLabel(pg), lit, i, onShow = show, onPlay = delete) {
                            Image(alexaIcon, null, modifier = Modifier.size(CC_PAGE_ICON * 0.62f).clip(CircleShape))
                        } else PageIcon(ccPageIcon(pg), ccPageLabel(pg), selected = lit, index = i, onShow = show, onPlay = delete)
                    }
                    if (editing) PageIcon(R.drawable.ic_add, "New page", selected = false, index = minOf(layout.pages.size - 1, 7), onShow = {}, onPlay = null, onSelect = {
                        draft?.let { d ->
                            val id = "page${d.pages.size}-${System.currentTimeMillis() % 100000}"
                            draft = d.copy(pages = d.pages + CcPage(id, "auto", emptyList())); pageId = id
                            gallery = ""
                        }
                    })
                }
                }
            }
            val np by (dev.glasslauncher.widgets.NowPlayingFixture.state ?: context.app.nowPlaying.state).collectAsStateWithLifecycle()
            // Confirms and the permission card are laid out in the panel itself, under the header, never inside the
            // scrolling tiles: a scrolled offset hid the card's top once, and a security prompt must be whole.
            if (restartAsk || agentCard) Box(Modifier.ccPanelSlot()) {
            if (restartAsk) {
                val canReboot = rooted || dev.glasslauncher.system.RootFeatures.systemAppState(context) == dev.glasslauncher.system.RootFeatures.SystemApp.On
                CcConfirmCard(
                    sz,
                    title = if (canReboot) "Restart Fire TV?" else "Restart from Settings",
                    body = if (canReboot) "Apps that are open will close." else "Fire TV can restart from Settings › My Fire TV.",
                    confirm = if (canReboot) "Restart" else "Settings",
                    destructive = canReboot,
                    onCancel = { restartAsk = false; pendingFocus = "restart" },
                ) {
                    restartAsk = false
                    closeAll()
                    if (canReboot) scope.launch { if (!dev.glasslauncher.system.RootFeatures.reboot(context)) SystemControls.open(context, Settings.ACTION_SETTINGS) }
                    else SystemControls.open(context, Settings.ACTION_SETTINGS)
                }
            } else if (agentCard) CcAgentCard(sz) { allowed ->
                agents.waiting = 0; agents.result = if (allowed) "Allowed" else "Denied"; agentCard = false
                pendingFocus = "addon:claude/agents"
            }
            }
            // The tiles scroll when they're taller than the screen (Now Playing, large text), as tvOS's do;
            // CC_BLEED of room on each side keeps a focused tile's growth and shadow from being clipped.
            val scrollState = androidx.compose.foundation.rememberScrollState()
            // A platter is pushed as tvOS pushes a module (pushPlatterController:...sourceIdentifier:): it zooms out of the
            // pill it came from and zooms back into it on pop. Spring mass 1, stiffness 150, damping 20 (ratio 0.8165), rows
            // and fades 0.25 s. Reduce Motion: a plain cross-fade, nothing scales. Never a cut either way.
            androidx.compose.animation.AnimatedContent(
                // Which platter, not just whether one: the leaving content is drawn from its own state, so a closing
                // Game Controllers platter no longer turned into Remotes as its flag cleared (P151b).
                targetState = when { airplayPlatter -> "airplay"; controllersPlatter -> "controllers"; remotesPlatter -> "remotes"; else -> null },
                modifier = Modifier.weight(1f, fill = remotesPlatter || airplayPlatter || controllersPlatter).onGloballyPositioned {
                    platterSource.box = it.boundsInWindow()
                    contentBottom[0] = it.boundsInWindow().bottom
                    syncMotionPanel()
                },
                transitionSpec = {
                    val push = targetState != null
                    val fade = androidx.compose.animation.core.tween<Float>(TOGGLE_FADE_MS)
                    if (reduceMotion) androidx.compose.animation.ContentTransform(
                        targetContentEnter = androidx.compose.animation.fadeIn(fade),
                        initialContentExit = androidx.compose.animation.fadeOut(fade),
                        sizeTransform = androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.tween(TOGGLE_FADE_MS) },
                    ) else {
                        // Fades only here: the platter itself morphs from the tile's rect to its own ([platterMorph], P151b). A
                        // height SizeTransform used to dominate (the platter grew from the bottom up) and the scale's origin
                        // fell back to top-centre; the container now takes the new size at once, under the fades.
                        androidx.compose.animation.ContentTransform(
                            targetContentEnter = androidx.compose.animation.fadeIn(fade),
                            initialContentExit = androidx.compose.animation.fadeOut(fade),
                            // The platter is always the one on top: arriving over the grid, leaving over it.
                            targetContentZIndex = if (push) 1f else -1f,
                            sizeTransform = null,
                        )
                    }
                },
                label = "cc-platter",
            ) { shownPlatter ->
            val morphScope = this
            if (shownPlatter == "airplay") Box(Modifier.ccPanelSlot()) { Box(Modifier.platterMorph(morphScope, platterSource, reduceMotion)) {
                CcAirPlayPlatter(sz, airPlay, cfg.speakers, cfg.muteTvOnSonos, edit, active, onBack = { airplayPlatter = false; pendingFocus = "airplay" }, onName = {
                    val page = dev.glasslauncher.system.AirPlayPageModel.of(airPlay!!.first)
                    closeAll()
                    open(Overlay.TextInput("AirPlay Name", page.name, "How this TV appears in the AirPlay list, up to ${page.nameMax} characters.") { typed ->
                        val name = typed.trim()
                        if (name.length > page.nameMax) dev.glasslauncher.system.RemoteNotice.show(context, "A name can be up to ${page.nameMax} characters.", 3_000)
                        else airPlay.second.setSetting(dev.glasslauncher.system.AirPlayProtocol.SettingKey.DisplayName, name)
                    })
                })
            } } else if (shownPlatter == "controllers") Box(Modifier.ccPanelSlot()) { Box(Modifier.platterMorph(morphScope, platterSource, reduceMotion)) {
                CcControllersPlatter(sz, devicePairing, justPaired, { justPaired = null }, onSettings = { system { SystemControls.openGameControllers(context) } }, onPair = { devicePairing = true }, onBluetooth = { system { SystemControls.openBluetooth(context) } }, onPaired = { justPaired = it; devicePairing = false }, onSelect = { openDevicePage(PageKind.Gamepad, it.name, closeAll, open) })
            } } else if (shownPlatter == "remotes") Box(Modifier.ccPanelSlot()) { Box(Modifier.platterMorph(morphScope, platterSource, reduceMotion)) {
                CcRemotesPlatter(sz, devicePairing, justPaired, { justPaired = null }, onSettings = { openRemoteSettings(closeAll, open) }, onPair = { devicePairing = true }, onBluetooth = { system { SystemControls.openBluetooth(context) } }, onPaired = { justPaired = it; devicePairing = false },
                    onSelect = { openDevicePage(PageKind.Remote, it.name, closeAll, open) })
            } } else if (!restartAsk && !agentCard) Column(
                verticalArrangement = Arrangement.spacedBy(sz.gap),
                modifier = Modifier
                    // The viewport starts 14 dp below the header's bottom while the tiles keep their place (content
                    // starts 2 dp inside it), so a scrolled row is cut under the header, not over its text.
                    .padding(top = CC_BLEED - CC_TOP_ROOM)
                    // No hard cut line (P101, tvOS): the viewport's top and bottom edges fade out while there is more to
                    // scroll that way (one baked alpha gradient, applied only when the content overflows).
                    .ccEdgeFade(scrollState)
                    .verticalScroll(scrollState)
                    .padding(start = CC_BLEED, end = CC_BLEED, top = CC_TOP_ROOM, bottom = CC_BLEED)
                    .padding(bottom = m.chromeInset)
                    .width(sz.column),
            ) {
            // Switching modules from the menu bar (FW TVSSMenuItemPanelHostingViewController setHostedViewController:focusHeading:): the whole
            // panel slides in from the direction focus was heading, on the platter springs (150/20), while the old one leaves the other
            // way. One transition, interruptible; the two fades are complementary (in + out = 1), so there is never a frame with neither
            // page showing, and the leaving page is never at full strength once the new one has begun. Reduce Motion: a plain fade.
            androidx.compose.animation.AnimatedContent(
                targetState = pageId,
                transitionSpec = {
                    val fade = androidx.compose.animation.core.tween<Float>(PAGE_FADE_MS, easing = androidx.compose.animation.core.LinearEasing)
                    if (reduceMotion) androidx.compose.animation.ContentTransform(
                        targetContentEnter = androidx.compose.animation.fadeIn(fade), initialContentExit = androidx.compose.animation.fadeOut(fade),
                        sizeTransform = androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() },
                    ) else {
                        val dir = if (layout.pages.indexOfFirst { it.id == targetState } >= layout.pages.indexOfFirst { it.id == initialState }) 1 else -1
                        val slide = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(PLATTER_DAMPING, PLATTER_STIFFNESS, androidx.compose.ui.unit.IntOffset(1, 1))
                        androidx.compose.animation.ContentTransform(
                            targetContentEnter = androidx.compose.animation.slideIn(slide) { androidx.compose.ui.unit.IntOffset(dir * it.width / 3, 0) } + androidx.compose.animation.fadeIn(fade),
                            initialContentExit = androidx.compose.animation.slideOut(slide) { androidx.compose.ui.unit.IntOffset(-dir * it.width / 6, 0) } + androidx.compose.animation.fadeOut(fade),
                            sizeTransform = androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.spring(PLATTER_DAMPING, PLATTER_STIFFNESS, androidx.compose.ui.unit.IntSize(1, 1)) },
                        )
                    }
                },
                label = "cc-page",
            ) { shownPage ->
            Column(verticalArrangement = Arrangement.spacedBy(sz.gap)) {
            if (shownPage == "alexa") {
                Box(Modifier) { AlexaPage(sz, closeAll) }
            } else {
            // The Controls page (P83): the layout's ordered {id, size} list, packed into the 4-column grid by
            // CcPacking (next-fit, so it reproduces the fixed layout this was). Edit mode changes a draft of it.
            val u = sz.round
            val pid = layout.page(shownPage)?.id ?: layout.pages.first().id
            val avail: (String) -> Boolean = { id -> when (id) { "airplay" -> airPlay != null; "vpn" -> vpnApps.isNotEmpty(); else -> !CcApps.isApp(id) || CcApps.installed(context, id) } }
            // Edit mode (P101): Add a Control is a 2x1 cell of the grid itself, after the last tile (first fit, same pitch),
            // not a pill floating under it. It is not part of the layout: only drawn and focusable.
            val tilesNow = layout.page(pid)?.tiles.orEmpty().filter { avail(it.id) } +
                (if (editing && lifted == null && gallery == null) listOf(CcTile(ADD_TILE, CcSize.S2x1)) else emptyList())
            val placed = remember(tilesNow) { CcPacking.pack(tilesNow) }
            val rows = placed.maxOfOrNull { it.row + it.tile.size.h } ?: 0
            var focusedId by remember(active) { androidx.compose.runtime.mutableStateOf<String?>(null) }
            fun enterEdit(refocus: Boolean = false) {
                if (draft == null) draft = storedLayout
                // The Alexa page isn't editable: edit mode starts on Controls.
                if (pageId == "alexa") pageId = "controls"
                // From the Edit pill (which goes away): focus lands on the first tile.
                if (refocus) scope.launch { withFrameNanos { }; runCatching { first.requestFocus() } }
            }
            fun liftToggle(id: String) {
                if (lifted == null) { lifted = id; liftBase = draft; return }
                // Dropped over a page icon: the tile goes to the end of that page (and that page shows).
                val target = hoverPage
                if (target != null && target != pid) { draft = draft?.moveToPage(pid, id, target); pageId = target }
                hoverPage = null; lifted = null; liftBase = null
            }
            fun moveLifted(dx: Int, dy: Int) {
                val d = draft ?: return; val id = lifted ?: return
                val all = d.page(pid)?.tiles ?: return
                val from = all.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
                val to = if (dy != 0) d.neighbour(pid, from, dy, avail) else {
                    val vis = all.filter { avail(it.id) }
                    vis.getOrNull(vis.indexOfFirst { it.id == id } + dx)?.let { n -> all.indexOfFirst { it.id == n.id } }
                } ?: return
                draft = d.move(pid, from, to)
            }
            fun removeTile(id: String) {
                val d = draft ?: return
                if (!CcControls.removable(id)) return
                draft = d.remove(pid, id); if (lifted == id) { lifted = null; liftBase = null }
                scope.launch { withFrameNanos { }; runCatching { first.requestFocus() } }
            }
            // The Add a Control gallery, in place of the grid (it's a window of its own, so the draft stays here):
            // null = closed, "" = the three groups, else a group key ("glass", "addons", "byapp").
            val requesters = remember { mutableMapOf<String, FocusRequester>() }
            LaunchedEffect(pendingFocus, placed) {
                val id = pendingFocus ?: return@LaunchedEffect
                if (placed.none { it.tile.id == id }) return@LaunchedEffect
                withFrameNanos { }
                runCatching { (if (id == "fire_settings") first else requesters[id])?.requestFocus() }
                pendingFocus = null
            }
            fun addControl(id: String) {
                val d = draft ?: return
                // A page holds seven rows (no scrolling, P101): what doesn't fit goes to the next page, made if need be.
                draft = d.addFitting(pid, id, CcControls.MAX_ROWS)
                gallery = null
                pendingFocus = id
            }

            // Edit mode's jiggle (P152, tvOS): only the lifted tile, +/-1.62 degrees over 0.325 s, rotationZ read in the layer
            // phase. Every tile used to wobble on a layer of its own, and those layers dropped the glass for a frame as
            // focus left a tile (the dark flash in edit mode).
            val wobbleOn = false
            val wobbling = editing && lifted != null && !reduceMotion
            // Only while editing is there a clock at all: outside edit mode nothing here ticks (the open/close is
            // GPU-bound on this stick, and an idle infinite transition kept frames coming).
            val wobbleClock: androidx.compose.runtime.State<Float> = if (wobbling) androidx.compose.animation.core.rememberInfiniteTransition(label = "cc-wobble").animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(325, easing = androidx.compose.animation.core.LinearEasing)),
                label = "cc-wobble-t",
            ) else remember { androidx.compose.runtime.mutableStateOf(0f) }
            LaunchedEffect(agents.result) { if (agents.result != null) { kotlinx.coroutines.delay(2_000); agents.result = null } }
            if (gallery != null) CcGallery(gallery!!, layout, sz, { gallery = it }, ::addControl) else {
            Box(
                Modifier
                    .width(sz.column)
                    .height(if (rows == 0) 0.dp else u * rows + sz.gap * (rows - 1))
                    .onPreviewKeyEvent { e ->
                        val k = e.nativeKeyEvent
                        if (!editing) {
                            // On the 2x2 Now Playing, Left and Right are previous and next (wide enough that focus
                            // already arrived from a neighbour); at 2x1 and 1x1 they move focus as usual.
                            val playing = np
                            val isBig = placed.firstOrNull { it.tile.id == "now_playing" }?.tile?.size == CcSize.S2x2
                            if (focusedId == "now_playing" && isBig && playing != null &&
                                (k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                                if (k.action == android.view.KeyEvent.ACTION_DOWN) {
                                    if (k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) playing.previous() else playing.next()
                                }
                                return@onPreviewKeyEvent true
                            }
                            return@onPreviewKeyEvent false
                        }
                        val down = k.action == android.view.KeyEvent.ACTION_DOWN
                        if (lifted != null) when (k.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val dx = dev.glasslauncher.ui.horizontalStep(k.keyCode, rtl)
                                if (down) {
                                    val h = hoverPage
                                    if (h != null) {
                                        val ids = layout.pages.map { it.id }.filter { it != "alexa" }
                                        hoverPage = ids.getOrNull((ids.indexOf(h) + dx).coerceIn(0, ids.lastIndex)) ?: h
                                    } else moveLifted(dx, 0)
                                }
                                true
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                if (down) {
                                    if (hoverPage == null) {
                                        // From the top row, Up goes to the menu bar: the page icons to drop onto.
                                        val before = draft
                                        moveLifted(0, -1)
                                        if (draft === before && layout.pages.size > 1) hoverPage = pid
                                    }
                                }
                                true
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) { if (hoverPage != null) hoverPage = null else moveLifted(0, 1) }; true }
                            // Menu resizes a lifted control too: the next size it allows, then it keeps moving.
                            android.view.KeyEvent.KEYCODE_MENU -> { if (down) lifted?.let { id -> draft = draft?.resize(pid, id) }; true }
                            else -> false
                        } else if (k.keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                            // Menu: the next size the focused control allows; neighbours repack.
                            if (down) focusedId?.let { id -> draft = draft?.resize(pid, id); pendingFocus = id }
                            true
                        } else if (k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && focusedId != null && focusedId != ADD_TILE) {
                            // The 2-wide Add cell may not sit under the tile Down is pressed on (a short last row): from a
                            // tile in the row above it, Down still lands on it, as it did when it was a full-width pill.
                            val me = placed.firstOrNull { it.tile.id == focusedId }
                            val add = placed.firstOrNull { it.tile.id == ADD_TILE }
                            if (me != null && add != null && me.row + me.tile.size.h == add.row) {
                                if (down) runCatching { requesters[ADD_TILE]?.requestFocus() }
                                true
                            } else false
                        } else false
                    },
            ) {
                for (pl in placed) androidx.compose.runtime.key(pl.tile.id) {
                    val tile = pl.tile; val id = tile.id
                    val w = u * tile.size.w + sz.gap * (tile.size.w - 1)
                    val h = u * tile.size.h + sz.gap * (tile.size.h - 1)
                    val pitchPx = with(androidx.compose.ui.platform.LocalDensity.current) { (u + sz.gap).roundToPx() }
                    val off by androidx.compose.animation.core.animateIntOffsetAsState(
                        androidx.compose.ui.unit.IntOffset(pitchPx * pl.col, pitchPx * pl.row),
                        // tvOS's overlay spring (about 90% in 130 ms, settled by 200 ms), not a timed tween.
                        if (reduceMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(0.9f, 700f, androidx.compose.ui.unit.IntOffset(1, 1)),
                        label = "cc-slot",
                    )
                    val isLifted = lifted == id
                    val liftScale by androidx.compose.animation.core.animateFloatAsState(
                        if (isLifted) 1.28f else 1f,
                        if (reduceMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(0.65f, 850f), label = "cc-lift",
                    )
                    val label: (String?) -> Unit = { roundLabel = it }
                    // Select acts, or in edit mode lifts and drops; hold Select enters edit mode; Play/Pause removes there.
                    fun tap(go: () -> Unit): () -> Unit = { if (editing && id != ADD_TILE) liftToggle(id) else go() }
                    // No long-press to edit (P101): edit mode is only entered from Settings › Control Center.
                    val hold: (() -> Unit)? = null
                    fun playOr(go: () -> Unit): () -> Unit = { if (editing) removeTile(id) else go() }
                    val requester = if (id == "fire_settings") first else remember(id) { requesters.getOrPut(id) { FocusRequester() } }
                    val focusMod = Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) focusedId = id }
                    Box(
                        Modifier
                            .offset { off }
                            .size(w, h)
                            .zIndex(if (isLifted) 2f else if (focusedId == id) 1f else 0f)
                            // A layer only for the lifted tile (or one settling a lift): the rest draw as they do outside edit mode.
                            .then(if (isLifted || liftScale != 1f) Modifier.graphicsLayer {
                                scaleX = liftScale; scaleY = liftScale
                                if (wobbling && isLifted) rotationZ = 1.62f * kotlin.math.sin((wobbleClock.value * 2f * Math.PI).toFloat())
                            } else Modifier)
                            .testTag(if (id == ADD_TILE) "cc-add" else "cc-tile:$id"),
                    ) {
                        val one = tile.size == CcSize.S1x1
                        when (id) {
                            ADD_TILE -> CcTile("Add a Control", null, Shapes.pill, w, h, onClick = { gallery = "" }, modifier = focusMod) { fg ->
                                // 2x1 is 104 dp wide: "Add a Control" doesn't fit at the text floor, so the cell says "Add" (its
                                // accessibility name is still "Add a Control", from the tile's description).
                                PillContent(R.drawable.ic_add, "Add", null, fg, on = false, accent = fg)
                            }
                            "fire_settings" -> CcTile("Settings", "Fire TV", RoundedCornerShape(sz.bigRadius), w, h, modifier = focusMod,
                                onClick = tap { closeAll(); open(Overlay.TvSettings) }, onLongClick = hold, onPlay = playOr { closeAll(); open(Overlay.TvSettings) }, wiggle = wobbleOn) { fg ->
                                BigIcon(R.drawable.ic_settings, "Settings", fg)
                            }
                            "wifi" -> {
                                val go = tap { system { SystemControls.open(context, Settings.ACTION_WIFI_SETTINGS) } }
                                val icon = if (wifiOn) R.drawable.ic_wifi else R.drawable.ic_wifi_off
                                if (one) Round(icon, "Wi-Fi, $network", on = wifiOn, onLabel = label, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn, onClick = go)
                                else PillOrBig(tile.size, w, h, "Wi-Fi", network, icon, wifiOn, Blue, go, focusMod, hold, playOr { go() }, wobbleOn)
                            }
                            "bluetooth" -> {
                                val btValue = when (bluetooth) { true -> "On"; false -> "Off"; null -> "Devices" }
                                val go = tap { system { SystemControls.openBluetooth(context) } }
                                val icon = if (bluetooth == false) R.drawable.ic_bluetooth_disabled else R.drawable.ic_bluetooth
                                if (one) Round(icon, "Bluetooth, $btValue", on = bluetooth == true, onLabel = label, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn, onClick = go)
                                else PillOrBig(tile.size, w, h, "Bluetooth", btValue, icon, bluetooth == true, Blue, go, focusMod, hold, playOr { go() }, wobbleOn)
                            }
                            "launcher" -> {
                                val go = tap { closeAll(); open(Overlay.Settings) }
                                // 1x1 (the default): the sliders glyph.
                                if (one) Round(R.drawable.ic_tune, "Launcher Settings", onLabel = label, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn, onClick = go)
                                else CcTile("Launcher Settings", null, Shapes.pill, w, h, onClick = go, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn) { fg ->
                                    PillContent(R.drawable.ic_tune, "Launcher Settings", null, fg, on = false, accent = fg)
                                }
                            }
                            "remotes" -> SizedRound(tile.size, w, h, "Remotes", null, R.drawable.ic_settings_remote, "Remotes", onLabel = label, modifier = focusMod.platterAnchor(platterSource, "remotes"), onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { platterSource.id = "remotes"; remotesPlatter = true }) { tap { platterSource.id = "remotes"; remotesPlatter = true }() }
                            "controllers" -> SizedRound(tile.size, w, h, "Gamepad", null, R.drawable.ic_sports_esports, "Game Controllers", onLabel = label, modifier = focusMod.platterAnchor(platterSource, "controllers"), onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { platterSource.id = "controllers"; controllersPlatter = true }) { tap { platterSource.id = "controllers"; controllersPlatter = true }() }
                            "appearance" -> SizedRound(tile.size, w, h, "Theme", if (dark) "Dark" else "Light", if (dark) R.drawable.ic_dark_mode else R.drawable.ic_light_mode, if (dark) "Theme, Dark" else "Theme, Light", onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn,
                                onPlay = playOr { screen.dissolve { edit { it.copy(theme = if (dark) ThemeMode.Light else ThemeMode.Dark) } } }) {
                                tap { screen.dissolve { edit { it.copy(theme = if (dark) ThemeMode.Light else ThemeMode.Dark) } } }()
                            }
                            "screensaver" -> SizedRound(tile.size, w, h, "Saver", null, R.drawable.ic_landscape, "Screen Saver", onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { closeAll(); AerialActivity.start(context) }) { tap { closeAll(); AerialActivity.start(context) }() }
                            "switcher" -> SizedRound(tile.size, w, h, "Apps", null, R.drawable.ic_app_switcher, "App Switcher", onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { closeAll(); open(Overlay.AppSwitcher) }) { tap { closeAll(); open(Overlay.AppSwitcher) }() }
                            // P164: tvOS's Control Center has Search and Accessibility.
                            "search" -> SizedRound(tile.size, w, h, "Search", null, R.drawable.ic_search, "Search", onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { closeAll(); open(Overlay.Search) }) { tap { closeAll(); open(Overlay.Search) }() }
                            "accessibility" -> SizedRound(tile.size, w, h, "Access", null, R.drawable.ic_accessibility, "Accessibility", onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { system { SystemControls.open(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) } }) { tap { system { SystemControls.open(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) } }() }
                            "downloads" -> {
                                val downloads by remember { dev.glasslauncher.downloads.Downloads.queue(context).items }.collectAsState()
                                val value = dev.glasslauncher.downloads.DownloadText.summary(downloads)
                                val go = { closeAll(); open(Overlay.Downloads) }
                                SizedRound(tile.size, w, h, "Downloads", value, R.drawable.ic_download, value?.let { "Downloads, $it" } ?: "Downloads", on = value != null, onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { go() }) { tap { go() }() }
                            }
                            "airplay" -> airPlay?.let { (state, _) ->
                                // A menu pill, not a toggle (P129): Select opens the platter (receiver on/off, status),
                                // like tvOS's Audio pill. The pill shows the connection, not an on/off disc.
                                val value = when { state.sender != null -> "Connected to ${state.sender}"; state.running -> "On"; else -> "Off" }
                                // P141: while audio goes to a Sonos speaker the control wears its glyph (solid blue) and room name, as tvOS's Audio pill does.
                                val route by remember { dev.glasslauncher.sonos.SonosRoutes.state(context) }.collectAsStateWithLifecycle()
                                val pill = dev.glasslauncher.sonos.AirPlayPill.of(route, dev.glasslauncher.sonos.AirPlayPill.roomLabel(cfg.speakers))
                                androidx.compose.runtime.CompositionLocalProvider(LocalRouteInk provides inkAccent(Blue)) {
                                    SizedRound(tile.size, w, h, pill.title, null, if (pill.speaker) R.drawable.ic_speaker else R.drawable.ic_airplay, if (pill.speaker) pill.title else "AirPlay, $value", onLabel = label, modifier = focusMod.platterAnchor(platterSource, "airplay"), onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { platterSource.id = "airplay"; airplayPlatter = true }) { tap { platterSource.id = "airplay"; airplayPlatter = true }() }
                                }
                            }
                            "now_playing" -> NowPlayingTile(np, tile.size, w, h, focusMod, hold, wobbleOn, ::tap, ::playOr, label)
                            "sleep_timer" -> {
                                val value = dev.glasslauncher.system.SleepTimer.label(sleepLeft)
                                val go = tap {
                                    val next = dev.glasslauncher.system.SleepTimer.next(dev.glasslauncher.system.SleepTimer.choice(context))
                                    if (next > 0 && !dev.glasslauncher.system.SleepTimer.canSleep(context)) {
                                        // No root and the accessibility service is off: it could not sleep the stick. Say so and open the page.
                                        dev.glasslauncher.system.RemoteNotice.show(context, "Sleep Timer needs VITRE turned on in Accessibility", 4_000)
                                        runCatching {
                                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }
                                    } else {
                                        dev.glasslauncher.system.SleepTimer.set(context, next)
                                        sleepLeft = dev.glasslauncher.system.SleepTimer.remainingMs(context)
                                    }
                                }
                                val on = sleepLeft > 0
                                // 1x1: while it runs, the minutes left ("28") replace the glyph (spec: a short value may).
                                if (one && on) CcTile("Sleep Timer, $value", null, androidx.compose.foundation.shape.CircleShape, w, h, onClick = go, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn, on = true) { fg ->
                                    androidx.compose.foundation.text.BasicText("${(sleepLeft + 59_999) / 60_000}", style = Type.heading.copy(color = fg))
                                }
                                else if (one) Round(R.drawable.ic_do_not_disturb_on, "Sleep Timer, $value", on = on, onLabel = label, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn, onClick = go)
                                // "Sleep Timer" doesn't fit a 2x1 pill at the 13 sp floor: the pill says "Sleep", the tile's description keeps the full name.
                                else PillOrBig(tile.size, w, h, "Sleep", value, R.drawable.ic_do_not_disturb_on, on, Blue, go, focusMod, hold, playOr { go() }, wobbleOn, name = "Sleep Timer", fillOn = on)
                            }
                            "vpn" -> {
                                val value = if (vpnConnected) "Connected" else "Off"
                                val go = tap { vpnApps.firstOrNull()?.let { (pkg, _) -> system { dev.glasslauncher.system.VpnControl.open(context, pkg) } } }
                                if (one) Round(R.drawable.ic_vpn, "VPN, $value", on = vpnConnected, onLabel = label, modifier = focusMod, onLongClick = hold, onPlay = playOr { go() }, wiggle = wobbleOn, onClick = go)
                                else PillOrBig(tile.size, w, h, "VPN", value, R.drawable.ic_vpn, vpnConnected, Blue, go, focusMod, hold, playOr { go() }, wobbleOn, fillOn = vpnConnected)
                            }
                            "restart" -> {
                                val ask = { restartAsk = true; pendingFocus = null }
                                SizedRound(tile.size, w, h, "Restart", null, R.drawable.ic_restart, "Restart", onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { }) { tap { ask() }() }
                            }
                            "power" -> {
                                val go = tap {
                                    if (dev.glasslauncher.system.PowerControl.mode(context) == dev.glasslauncher.system.PowerControl.Mode.Unavailable) {
                                        dev.glasslauncher.system.RemoteNotice.show(context, "Power Off needs VITRE turned on in Accessibility", 4_000)
                                    } else {
                                        closeAll()
                                        scope.launch {
                                            dev.glasslauncher.system.PowerControl.standby(context) {
                                                // CEC didn't go out (no HDMI_CEC grant, or the call failed): the stick sleeps, the TV stays on.
                                                dev.glasslauncher.system.RemoteNotice.show(context, "Couldn't turn off the TV: sleeping the stick only", 4_000)
                                            }
                                        }
                                    }
                                }
                                SizedRound(tile.size, w, h, "Power", null, R.drawable.ic_power_settings_new, "Power Off", onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { }) { go() }
                            }
                            "cleanup" -> SizedRound(tile.size, w, h, "Clean Up", cleaned, R.drawable.ic_cleaning_services, cleaned?.let { "Clean Up, $it" } ?: "Clean Up", onLabel = label, modifier = focusMod, onLongClick = hold, wiggle = wobbleOn, onPlay = playOr { }) {
                                tap {
                                    if (cleaned != "cleaning…") scope.launch {
                                        cleaned = "cleaning…"
                                        val r = dev.glasslauncher.system.RootFeatures.cleanUp(context)
                                        freed = r.memoryMb
                                        cleaned = when {
                                            r.cachesMb >= 1024 -> "%.1f GB".format(r.cachesMb / 1024f)
                                            r.cachesMb > 0 -> "${r.cachesMb} MB"
                                            r.memoryMb > 0 -> "${r.memoryMb} MB memory"
                                            r.bulkCaches -> "Nothing to clean"
                                            else -> "Freed no memory"
                                        }
                                        kotlinx.coroutines.delay(4_000)
                                        cleaned = null
                                    }
                                }()
                            }
                        }
                        if (CcShowcase.isShowcase(id)) ShowcaseTile(
                            id, tile.size, w, h, agents, { agentCard = true }, focusMod, hold, wobbleOn, ::tap, ::playOr, label,
                        )
                        else if (id.startsWith("addon:")) AddonTile(id, tile.size, w, h, focusMod, wobbleOn, ::tap, ::playOr, label)
                        else if (CcApps.isApp(id)) AppShortcutTile(id, tile.size, w, h, focusMod, wobbleOn, ::tap, ::playOr, label, closeAll)
                        // Edit mode's remove legend (the action is Play/Pause on the focused tile); Fire TV Settings has none.
                        if (editing && id != ADD_TILE && CcControls.removable(id)) Box(
                            Modifier.align(Alignment.TopStart).offset(x = (-3).dp, y = (-3).dp).size(18.dp)
                                // The badges arrive after the tiles have (P101 continuity): they are not part of the tiles'
                                // entrance, so they wait for it instead of showing on Home first.
                                .graphicsLayer { alpha = if (motion.enabled) ((motion.p - 0.8f) / 0.2f).coerceIn(0f, 1f) else 1f }
                                .background(Color(0xE61C1C1E), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(width = 8.dp, height = 2.dp).background(Color.White, CircleShape)) }
                    }
                }
            }
            }
            }
            }
            }
            // What's playing, with controls (any app with a media session: Spotify, Amazon Music, YouTube…).
            if (np == null) contentBottom[1] = 0f
            np?.let {
                val e = remember(motion) { CcEmerge(motion, CcRole.Control) }
                Box(Modifier.ccEmerge(e).onGloballyPositioned { contentBottom[1] = it.boundsInWindow().bottom; syncMotionPanel() }) { androidx.compose.runtime.CompositionLocalProvider(LocalCcEmerge provides e) { dev.glasslauncher.widgets.NowPlayingCard(it, sz.column, cfg.textScale) } }
            }
            }
            }
        }
        // The focused round button's name (P288). The rounds are icons only, so the caption is the only thing that
        // says what one is; roundLabel was written by every Round and read nowhere. Sits under the tiles and above
        // the edit hint, and is suppressed while editing, where the hint already owns the bottom.
        val caption = roundLabel
        androidx.compose.animation.AnimatedVisibility(
            visible = caption != null && !editing,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (hintShown && gallery == null) 78.dp else 30.dp),
        ) {
            Text(
                caption.orEmpty(),
                style = Type.caption,
                color = LocalPalette.current.primary.copy(alpha = 0.78f),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag("cc-round-label"),
            )
        }
        // Edit mode's hint (P101): one toast on the panel's own glass, at the bottom centre, never over the tiles.
        CcEditHint(shown = hintShown && gallery == null, lifted = lifted != null, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
        // Home's pill itself on the first frame (same glass, clock and gear, at its spot), fading as the drops form
        // and back as they merge, so neither end cuts from the pill to Control Center (tvOS: the pill fades out by
        // about 30% of the open). Drawn over the panel, so the drops stay under it until it has gone. Only over
        // Home: over an app there is no pill to match.
        val pillAt = pill
        if (pillAt != null && ControlCenterWindow.homeStarted && !reduceMotion) {
            val onLight = glassState.backdrop?.artLight(0.86f, 0.03f, 0.98f, 0.09f) == true
            val focusedPill = remember(exiting) { ControlCenterWindow.pillFocused }
            dev.glasslauncher.widgets.PillFace(
                cfg, focused = focusedPill, onLight = onLight, copy = true,
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(pillAt.left.roundToInt(), pillAt.top.roundToInt()) }
                    .size(with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.unit.DpSize(pillAt.width.toDp(), pillAt.height.toDp()) })
                    .graphicsLayer {
                        alpha = CcMorph.pillAlpha(CcMorph.bubble(enter.value, exiting, closeFrom))
                        if (focusedPill) { scaleX = PILL_FOCUS_SCALE; scaleY = PILL_FOCUS_SCALE }
                    },
            )
        }
    }
    }
}

@Composable
private fun Glyph(@DrawableRes icon: Int, tint: Color, modifier: Modifier = Modifier) {
    val routeInk = LocalRouteInk.current
    if (routeInk == null) {
        Image(painterResource(icon), contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = modifier)
    } else {
        // P141: the AirPlay control's glyph crossfades (0.25 s) between the Audio glyph and the routed speaker's, which is solid blue.
        val focused = LocalCcTileFocused.current
        androidx.compose.animation.Crossfade(icon, animationSpec = androidx.compose.animation.core.tween(TOGGLE_FADE_MS), label = "route-glyph") { id ->
            Image(painterResource(id), contentDescription = null, colorFilter = ColorFilter.tint(if (id == R.drawable.ic_speaker && !focused) routeInk else tint), modifier = modifier)
        }
    }
}

/** Set around the AirPlay control only: its glyph crossfades and tints [Color] for a routed speaker (P141). */
private val LocalRouteInk = androidx.compose.runtime.compositionLocalOf<Color?> { null }

@Composable
private fun BigIcon(@DrawableRes icon: Int, title: String, fg: Color, value: String? = null, on: Boolean = false, accent: Color = fg) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.5.dp)) {
        Glyph(icon, toggleTint(on, fg, accent), Modifier.size(LocalCcSizes.current.bigGlyph))
        Text(title, style = shadowed(Type.caption), color = fg)
        value?.let { Text(it, style = shadowed(Type.caption), color = fg.copy(alpha = 0.7f), maxLines = 1) }
    }
}

/** One of the page icons along the top: a small glass disc, white while its page shows or it's focused. */
@Composable
private fun PageIcon(
    @DrawableRes icon: Int, label: String, selected: Boolean, index: Int, onShow: () -> Unit,
    onPlay: (() -> Unit)? = null, onSelect: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    PageIcon(label, selected, index, onShow, onPlay, onSelect) { lit ->
        Image(painterResource(icon), null, colorFilter = ColorFilter.tint(if (lit) palette.onFocusFill else palette.primary), modifier = Modifier.size(CC_PAGE_ICON * 0.5f))
    }
}

/** The same disc around any [content] (given whether the disc is lit), for art that keeps its own colours. */
@Composable
private fun PageIcon(
    label: String, selected: Boolean, index: Int, onShow: () -> Unit, onPlay: (() -> Unit)? = null, onSelect: (() -> Unit)? = null,
    content: @Composable (lit: Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    val motion = LocalCcMotion.current
    val emerge = remember(motion, index) { motion?.let { CcEmerge(it, CcRole.Icon(index)) } }
    androidx.compose.runtime.CompositionLocalProvider(LocalCcEmerge provides emerge) {
        FocusTile(
            label = label, onClick = onSelect ?: onShow, shape = CircleShape, focusedScale = 1.08f, shadow = false,
            onPlay = onPlay,
            // Focusing an icon shows its page (the + icon makes one on Select instead).
            onFocusChange = { if (it && onSelect == null) onShow() },
            modifier = Modifier.size(LocalCcPageIconSize.current).ccEmerge(emerge),
        ) { focused ->
            val lit = focused || selected
            Box(
                Modifier.fillMaxSize().testTag("cc-page-icon:$index").ccSurface(CircleShape, LocalCcSheet.current)
                    // A drop is plain glass on its way; the selected page's white comes with the glyph as it lands.
                    .then(if (lit) Modifier.drawBehind { drawCircle(palette.focusFill, alpha = if (motion?.enabled == true) CcMorph.glyphAlpha(motion.p) else 1f) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                // The glyph comes in as the drop lands (a drop is plain glass on its way).
                Box(Modifier.graphicsLayer { alpha = if (motion?.enabled == true) CcMorph.glyphAlpha(motion.p) else 1f }) { content(lit) }
            }
        }
    }
}

/** Alexa shortcuts, in the same tiles as the Controls page. Each opens Amazon's own screen and closes. */
@Composable
private fun AlexaPage(sz: CcSizes, closeAll: () -> Unit) {
    val context = LocalContext.current
    fun go(open: (android.content.Context) -> Boolean) { closeAll(); open(context) }
    Row(horizontalArrangement = Arrangement.spacedBy(sz.gap)) {
        CcTile("Smart Home", null, RoundedCornerShape(sz.bigRadius), sz.bigWidth, sz.big, onClick = { go(SystemControls::openSmartHome) }) { fg ->
            BigIcon(R.drawable.ic_home, "Smart Home", fg)
        }
        Column(verticalArrangement = Arrangement.spacedBy(sz.gap)) {
            CcTile("Ask Alexa", null, Shapes.pill, sz.pillWidth, sz.pill, onClick = { go(SystemControls::askAlexa) }) { fg ->
                PillContent(R.drawable.ic_mic, "Ask Alexa", null, fg, on = false, accent = fg)
            }
            // Alexa's settings screen is permission-guarded; without root there's no way in.
            if (dev.glasslauncher.system.Root.known) CcTile("Alexa Settings", null, Shapes.pill, sz.pillWidth, sz.pill, onClick = { go(SystemControls::openAlexaSettings) }) { fg ->
                // Two lines, like Wi-Fi's: "Alexa Settings" didn't fit a pill.
                PillContent(R.drawable.ic_settings, "Alexa", "Settings", fg, on = false, accent = fg)
            }
        }
    }
}

private const val FILL_FADE_MS = 120

/** tvOS swaps a toggle between off (glyph in a disc) and on (accent glyph) over 0.25 s. */
private const val TOGGLE_FADE_MS = 250

/** The accent as is: the glass is always dark (P188), so the glyph keeps its contrast without deepening. */
private fun inkAccent(c: Color): Color = c

/**
 * The glyph's colour: it fades to [accent] over tvOS's 0.25 s when a toggle turns on. Focus does not change it (P219): an active
 * glyph stays the accent, focused or not, and an inactive one follows [fg] (which is the focus ink while focused).
 */
@Composable
internal fun toggleTint(on: Boolean, fg: Color, accent: Color): Color {
    val mix by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, androidx.compose.animation.core.tween(TOGGLE_FADE_MS), label = "toggle-tint")
    return androidx.compose.ui.graphics.lerp(fg, inkAccent(accent), mix)
}

/** Icon disc on the left, one or two lines of text on the right, as in tvOS Control Center pills. */
@Composable
internal fun PillContent(
    @DrawableRes icon: Int,
    title: String,
    value: String?,
    fg: Color,
    on: Boolean,
    accent: Color,
    disc: Boolean = true,
) {
    val sz = LocalCcSizes.current
    // The leading column is tvOS's 86 pt = 43 dp (FW defaultSymbolReservedSpace) with the glyph centred in it; the text starts at its edge.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(start = 0.dp, end = 1.dp)) {
        // On: no disc, the glyph alone takes the accent colour, at tvOS's full glyph size. Off: the glyph sits smaller in a
        // translucent white disc (white @ 0.15, tvOS's own value, not the control's tint) -- P129, REA's tvOS read of Control Center
        // states. The glyph's size follows the disc over the same 0.25 s, so a toggle never pops.
        val fade = androidx.compose.animation.core.tween<androidx.compose.ui.unit.Dp>(TOGGLE_FADE_MS)
        val discAlpha by androidx.compose.animation.core.animateFloatAsState(if (on || !disc) 0f else 0.15f, androidx.compose.animation.core.tween(TOGGLE_FADE_MS), label = "disc")
        val glyphSize by androidx.compose.animation.core.animateDpAsState(if (on || !disc) sz.pillGlyph else sz.discGlyph, fade, label = "pill-glyph")
        Box(Modifier.width(sz.leadColumn).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(sz.disc).then(if (disc) Modifier.background(Color.White.copy(alpha = discAlpha), CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) { Glyph(icon, toggleTint(on, fg, accent), Modifier.size(glyphSize)) }
        }
        Column(Modifier.weight(1f)) {
            PillText(title, CcPillTitle, fg, lines = if (value == null) 2 else 1)
            value?.let { PillText(it, CcPillValue, fg.copy(alpha = 0.7f)) }
        }
    }
}

@Composable
internal fun Round(
    @DrawableRes icon: Int, label: String,
    /** Kept for callers' own state in [label] (e.g. "Wi-Fi, Connected"); round controls never draw an on-state (P129). */
    on: Boolean = false, onLabel: (String?) -> Unit = {},
    modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onPlay: (() -> Unit)? = null, wiggle: Boolean = false,
    onClick: () -> Unit,
) {
    val sz = LocalCcSizes.current
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    // The caption follows the button while focused, including its state (Theme · Light → Dark).
    LaunchedEffect(focused, label) { if (focused) onLabel(label) }
    // Round (1x1) controls never show an on-state (P129, REA: tvOS's own round buttons -- Game Controllers,
    // Accessibility, Restrictions, Search -- have none at all); the state still reaches the user through the
    // spoken label its callers build ("Wi-Fi, Connected"). A control whose on-state matters resizes to a pill (F01).
    CcTile(label, null, CircleShape, sz.round, sz.round, onClick = onClick, modifier = modifier, onLongClick = onLongClick, onPlay = onPlay ?: onClick, wiggle = wiggle, on = false, onFocusChange = { f ->
        focused = f
        if (!f) onLabel(null)
    }) { fg ->
        Glyph(icon, fg, Modifier.size(sz.roundGlyph))
    }
}

/**
 * A control as the layout sized it (F01): a circle with only its glyph (the name is its description), a pill with glyph,
 * title and value, or a big tile (glyph over title and value), as iOS 18's Control Center resizes its controls.
 */
@Composable
internal fun SizedRound(
    size: CcSize, w: Dp, h: Dp, title: String, value: String?,
    @DrawableRes icon: Int, label: String, on: Boolean = false, onLabel: (String?) -> Unit = {},
    modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onPlay: (() -> Unit)? = null, wiggle: Boolean = false,
    accent: Color = Blue,
    onClick: () -> Unit,
) {
    if (size == CcSize.S1x1) {
        Round(icon, label, on, onLabel, modifier, onLongClick, onPlay, wiggle, onClick)
    } else {
        // The pill shows the short title; the tile's description keeps the control's full name.
        PillOrBig(size, w, h, title, value, icon, on, accent, onClick, modifier, onLongClick, onPlay, wiggle, name = label.substringBefore(", "))
    }
}

/** The pill (2x1) or big (2x2) form of a control: the same tile, a different shape and content. */
@Composable
internal fun PillOrBig(
    size: CcSize, w: Dp, h: Dp, title: String, value: String?, @DrawableRes icon: Int, on: Boolean, accent: Color,
    onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onPlay: (() -> Unit)? = null, wiggle: Boolean = false,
    name: String = title,
    /** The tile's white "on" fill (Sleep Timer and VPN had it); the others show "on" as #15's bare accent glyph only. */
    fillOn: Boolean = false,
) {
    val big = size == CcSize.S2x2
    CcTile(name, value, if (big) RoundedCornerShape(LocalCcSizes.current.bigRadius) else Shapes.pill, w, h, onClick = onClick, modifier = modifier, onLongClick = onLongClick,
        onPlay = onPlay ?: onClick, wiggle = wiggle, on = fillOn) { fg ->
        if (big) BigIcon(icon, title, fg, value, on, accent) else PillContent(icon, title, value, fg, on = on, accent = accent)
    }
}

@Composable
internal fun CcTile(
    title: String,
    value: String?,
    shape: Shape,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = onClick,
    wiggle: Boolean = false,
    content: @Composable (Color) -> Unit,
) {
    val palette = LocalPalette.current
    val motion = LocalCcMotion.current
    val emerge = remember(motion) { motion?.let { CcEmerge(it, CcRole.Control) } }
    androidx.compose.runtime.CompositionLocalProvider(LocalCcEmerge provides emerge) {
    FocusTile(
        label = if (value != null) "$title, $value" else title,
        onClick = onClick,
        onLongClick = onLongClick,
        onPlay = onPlay,
        wiggle = wiggle,
        onFocusChange = onFocusChange,
        shape = shape,
        // tvOS's focus is a clear lift with a shadow under it (F06): x1.21 (SK: 96 -> 116 px, F27's 2x2 about +21%, P138 F1).
        // A wide tile grows 11 dp each side, past the 8 dp gap, so the grid raises the focused tile above its neighbours
        // (zIndex) and keeps 12 dp of room above the first row inside the scroll viewport.
        focusedScale = LocalCcSizes.current.focusScale(width, height),
        shadow = true,
        shapedShadow = true,
        // P188: no wide focus shadow; it darkened the tiles around the focused one, which read as tiles changing colour.
        focusShadow = false,
        // Grows out of its drop as Control Center opens ([CcEmerge]).
        modifier = modifier.size(width, height).ccEmerge(emerge),
    ) { focused ->
        // A toggle that's on sits white with a blue glyph, like tvOS's Control Center toggles.
        // Focus and "on" fills fade (never pop), so a focus move cross-fades the white from one tile to the next.
        val fade = androidx.compose.animation.core.tween<Float>(FILL_FADE_MS)
        // The first focus lands while Control Center is still opening: the white appears at once, so the tile
        // is never revealed half-grey (later focus moves cross-fade).
        val phase = LocalCcPhase.current
        // P173: focus no longer fills white, so it fades in even while Control Center is opening (a snap was a jump).
        val focusA by androidx.compose.animation.core.animateFloatAsState(if (focused) 1f else 0f, fade, label = "focus")
        val onA by androidx.compose.animation.core.animateFloatAsState(if (on && !focused) 1f else 0f, fade, label = "on")
        // The text and glyph colour follows the FILL, not the focus: it used to flip the moment focus moved while the
        // white fill took 120 ms to fade, so a tile showed white content on a half-white fill (a washed-out cream,
        // P88). It switches where the content has the same contrast on either side (CcFocusPhase), read through
        // derived state so it recomposes once per flip, not per frame.
        val litByFocus by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { focusA >= CcFocusPhase.FLIP } }
        val litByOn by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { onA >= CcFocusPhase.FLIP } }
        // P173: the same glass focused or not; content stays white (blue glyph when on). Focus is the lift, the shadow and
        // the glass catching more light.
        val fg = if (litByOn && !litByFocus) inkAccent(Blue) else palette.primary
        Box(
            Modifier
                .fillMaxSize()
                // The shared fill stays put and the focus or "on" fill draws over it (swapping modifiers made a
                // fresh node on every focus change, which could draw a frame before it knew where it was).
                .ccSurface(shape, LocalCcSheet.current)
                .drawWithCache {
                    // The fills follow the tile's corners while it grows from a capsule (read per frame then).
                    val rest = shape.createOutline(size, layoutDirection, this)
                    val round = emerge?.now()?.round ?: 1f
                    val outline = if (round < 1f) ccRounding(rest, size, round) else rest
                    onDrawBehind {
                        // "On" is the accent glyph on the control's glass, never a white fill (F09): white means focus only.
                        // P188: focus changes nothing of the tile's look; only the lift and the shadow (no rim, no sheen: user).
                    }
                },
            contentAlignment = Alignment.Center,
        ) { androidx.compose.runtime.CompositionLocalProvider(LocalCcTileFocused provides litByFocus) { content(fg) } }
    }
    }
}


/**
 * The "Add a Control" gallery (P83 M3), drawn in place of the grid while edit mode is on: three groups (Glass,
 * Add-ons, By App), then the controls of one, each a pill with its icon, name and a line (what it does, or "Added").
 * Select adds the control at the end of the page and returns to the grid with focus on it; one already on the page
 * (and not parameterized) just returns.
 */
@Composable
private fun CcGallery(group: String, layout: CcLayout, sz: CcSizes, setGroup: (String?) -> Unit, add: (String) -> Unit) {
    val first = remember(group) { FocusRequester() }
    LaunchedEffect(group) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(sz.gap), modifier = Modifier.width(sz.column).testTag("cc-gallery")) {
        if (group == "") {
            val groups = listOf(
                Triple("glass", "VITRE", R.drawable.ic_tune),
                Triple("addons", "Add-ons", R.drawable.ic_widgets),
                Triple("byapp", "By App", R.drawable.ic_apps),
                Triple("apps", "Apps", R.drawable.ic_app_switcher),
            )
            groups.forEachIndexed { i, (key, name, icon) ->
                CcTile(name, null, Shapes.pill, sz.column, sz.pill, onClick = { setGroup(key) }, modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).testTag("cc-gallery-group:$key")) { fg ->
                    PillContent(icon, name, null, fg, on = false, accent = fg)
                }
            }
        } else if (group == "apps") CcAppGallery(layout, sz, first, setGroup, add)
        else {
            val entries: List<Pair<String?, CcCatalogEntry>> = when (group) {
                "glass" -> CcCatalog.glass.map { null to it }
                "addons" -> CcCatalog.addons.map { null to it }
                else -> CcCatalog.byApp().flatMap { (app, list) -> list.map { app to it } }
            }
            if (entries.isEmpty()) Text("No add-ons installed", style = Type.caption, color = LocalPalette.current.primary.copy(alpha = 0.7f), modifier = Modifier.padding(start = 4.dp))
            entries.forEachIndexed { i, (app, e) ->
                val added = layout.shown(e.id) && '#' !in e.id
                // Rows are only pills (no label rows between them), so they stay on the grid's row pitch and the
                // whole-row scrolling holds; By App names the app in the line instead.
                val line = when {
                    added -> "Added"
                    e.tier.tag != null -> "${e.tier.tag} · ${e.description}"
                    app != null -> "$app · ${e.description}"
                    else -> e.description
                }
                CcTile(e.name, line, Shapes.pill, sz.column, sz.pill, onClick = { if (added) setGroup(null) else add(e.id) },
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).testTag("cc-gallery:${e.id}")) { fg ->
                    PillContent(e.icon, e.name, line, fg, on = added, accent = Blue)
                }
            }
        }
    }
}

internal fun ccGalleryTitle(group: String?): String = when (group) { "" -> "Add a Control"; "glass" -> "VITRE"; "addons" -> "Add-ons"; "apps" -> "Apps"; else -> "By App" }

/** The Apps group (P152): every installed app with a launcher entry, each addable as a launch control. One already on the page just returns. */
@Composable
private fun CcAppGallery(layout: CcLayout, sz: CcSizes, first: FocusRequester, setGroup: (String?) -> Unit, add: (String) -> Unit) {
    val context = LocalContext.current
    val apps by remember { context.app.apps.apps() }.collectAsStateWithLifecycle(emptyList())
    if (apps.isEmpty()) Text("No apps installed", style = Type.caption, color = LocalPalette.current.primary.copy(alpha = 0.7f), modifier = Modifier.padding(start = 4.dp))
    apps.sortedBy { it.label.lowercase() }.forEachIndexed { i, app ->
        val id = CcApps.id(app.packageName)
        val added = layout.shown(id)
        CcTile(app.label, if (added) "Added" else "Launches ${app.label}", Shapes.pill, sz.column, sz.pill, onClick = { if (added) setGroup(null) else add(id) },
            modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).testTag("cc-gallery:$id")) { fg ->
            val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.packageName) { value = CcApps.icon(context.packageManager, app.packageName) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.width(sz.leadColumn).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    icon?.let { Image(it, null, Modifier.size(sz.pillGlyph)) }
                }
                Column(Modifier.weight(1f)) {
                    PillText(app.label, CcPillTitle, fg)
                    PillText(if (added) "Added" else "Launches the app", CcPillValue, fg.copy(alpha = 0.7f))
                }
            }
        }
    }
}

/** A page's menu-bar icon: Alexa and the named ones, else the glyph of its first control (spec 4), else the tune glyph. */
@DrawableRes
private fun ccPageIcon(page: CcPage): Int = when (page.icon) {
    "music" -> R.drawable.ic_music_note
    "home" -> R.drawable.ic_home
    "alexa" -> R.drawable.ic_mic
    "more" -> R.drawable.ic_apps
    "grid" -> R.drawable.ic_tv
    // A page of your own: its first control's glyph; a neutral one while it is empty (so it isn't mistaken for Controls).
    else -> page.tiles.firstNotNullOfOrNull { CcCatalog.entry(it.id)?.icon } ?: R.drawable.ic_widgets
}

/** The page's name for the icon's description: Controls and Alexa as before, the rest by their first control or "Page". */
private fun ccPageLabel(page: CcPage): String = when (page.id) {
    "controls" -> "Controls"
    "more" -> "More"
    "alexa" -> "Alexa"
    else -> page.tiles.firstNotNullOfOrNull { CcCatalog.entry(it.id)?.name }?.let { "$it page" } ?: "New page"
}

/**
 * Where a tile's content flips between its resting colour (light) and the focus colour (dark) while the white fill
 * fades in or out (P88). The fill is white at alpha a over dark glass of luminance Lg: content has equal contrast on
 * both sides where the blended fill's luminance is ~0.22, which for tvOS-dark glass (Lg 0.05-0.2) is a ~ 0.13-0.2.
 */
internal object CcFocusPhase {
    const val FLIP = 0.15f

    /** WCAG contrast of [dark] (true) or light content on the white fill at [alpha] over glass of luminance [glass]. */
    fun contrast(alpha: Float, glass: Float, dark: Boolean): Float {
        val fill = glass + alpha * (1f - glass)
        val ink = if (dark) 0.02f else 1f
        val hi = maxOf(fill, ink) + 0.05f; val lo = minOf(fill, ink) + 0.05f
        return hi / lo
    }

    /** The contrast the content actually has at [alpha] with the flip at [FLIP]. */
    fun chosen(alpha: Float, glass: Float): Float = contrast(alpha, glass, dark = alpha >= FLIP)
}

/**
 * Fades the viewport's top and bottom edges while there is more to scroll that way (tvOS: no hard cut line). The
 * mask is one baked gradient drawn DstIn over the layer, and only when the content overflows (otherwise no layer).
 */
private fun Modifier.ccEdgeFade(state: androidx.compose.foundation.ScrollState, fade: androidx.compose.ui.unit.Dp = 24.dp): Modifier =
    this.then(Modifier.graphicsLayer {
        compositingStrategy = if (state.maxValue > 0) androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            else androidx.compose.ui.graphics.CompositingStrategy.Auto
    }).then(Modifier.drawWithContent {
        drawContent()
        if (state.maxValue <= 0) return@drawWithContent
        val f = fade.toPx().coerceAtMost(size.height / 2f)
        val top = (state.value > 0)
        val bottom = (state.value < state.maxValue)
        if (!top && !bottom) return@drawWithContent
        val k = f / size.height
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to if (top) Color.Transparent else Color.Black,
                k to Color.Black,
                (1f - k) to Color.Black,
                1f to if (bottom) Color.Transparent else Color.Black,
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    })

/**
 * The edit-mode hint: each action its own key-cap and word, spaced apart ([Select] Move  [≡] Size  [⏯] Remove
 * [Back] Done), on the same baked glass as the tiles. Muted: it is a hint, not a control.
 */
@Composable
private fun CcEditHint(shown: Boolean, lifted: Boolean, modifier: Modifier = Modifier) {
    val a by androidx.compose.animation.core.animateFloatAsState(
        if (shown) 1f else 0f, androidx.compose.animation.core.spring(0.9f, 700f), label = "cc-hint",
    )
    if (!shown && a <= 0.01f) return
    val items = if (lifted) listOf("Select" to "Drop", "≡" to "Size", "Back" to "Cancel")
        else listOf("Select" to "Move", "≡" to "Size", "⏯" to "Remove", "Back" to "Done")
    Row(
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer { alpha = a * 0.92f; translationY = (1f - a) * 10.dp.toPx() }
            .ccSurface(RoundedCornerShape(26.dp), LocalCcSheet.current)
            .padding(horizontal = 22.dp, vertical = 11.dp)
            .testTag("cc-edit-hints"),
    ) {
        items.forEach { (key, word) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                ) { Text(key, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = LocalPalette.current.primary.copy(alpha = 0.85f), maxLines = 1, softWrap = false) }
                Text(word, style = Type.caption, color = LocalPalette.current.primary.copy(alpha = 0.62f), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** One device in a device platter: its name, one line of state, and whether it is connected (an accent glyph). */
internal data class CcDeviceRow(val name: String, val line: String, val connected: Boolean, val id: String = "")

/**
 * A device platter (Remotes, Game Controllers; P111, P146, P174): pushed over the grid inside the panel, as tvOS pushes a module.
 * Order: Pair, Settings, then the connected devices (each opens its own page). No empty-state pill: with nothing connected the
 * page is just the two actions. The layout rule for every sub-panel: every pill is the full column wide, and there is no Back
 * button: the remote's Back key pops the platter, as in tvOS. Same width and pitch as the grid, so it replaces it without the
 * panel resizing.
 */
@Composable
internal fun CcDevicePlatter(
    sz: CcSizes, title: String, tag: String, rows: List<CcDeviceRow>, deviceIcon: Int,
    settingsLabel: String, onSettings: () -> Unit, onPair: () -> Unit = {}, onSelect: (CcDeviceRow) -> Unit = {},
    /** A device that just paired (P186): focus goes to its row as soon as the list has it, then [onFocused] clears the request. */
    focusName: String? = null, onFocused: () -> Unit = {},
) {
    val first = remember { FocusRequester() }
    val rowRequesters = remember { HashMap<String, FocusRequester>() }
    LaunchedEffect(focusName, rows.map { it.name }) {
        val target = focusName?.let { n -> rows.firstOrNull { it.name == n } } ?: return@LaunchedEffect
        withFrameNanos { }
        if (runCatching { rowRequesters.getOrPut(target.name) { FocusRequester() }.requestFocus() }.getOrDefault(false)) onFocused()
    }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(sz.gap), modifier = Modifier.width(sz.column + CC_BLEED * 2).padding(horizontal = CC_BLEED).testTag(tag)) {
        Text(title, style = CcSectionHeader, color = LocalPalette.current.primary, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        CcTile("Pair a device\u2026", null, Shapes.pill, sz.column, sz.pill, onClick = onPair, modifier = Modifier.focusRequester(first).testTag("cc-action-pair")) { fg ->
            PillContent(R.drawable.ic_bluetooth, "Pair a device\u2026", null, fg, on = false, accent = fg)
        }
        CcTile(settingsLabel, null, Shapes.pill, sz.column, sz.pill, onClick = onSettings, modifier = Modifier.testTag("cc-action-settings")) { fg ->
            PillContent(R.drawable.ic_tune, settingsLabel, null, fg, on = false, accent = fg)
        }
        rows.forEachIndexed { i, r ->
            CcTile(r.name, r.line, Shapes.pill, sz.column, sz.pill, onClick = { onSelect(r) }, modifier = Modifier.focusRequester(remember(r.name) { rowRequesters.getOrPut(r.name) { FocusRequester() } }).testTag("cc-device-$i")) { fg ->
                PillContent(deviceIcon, r.name, r.line, fg, on = r.connected, accent = Blue)
            }
        }
    }
}

/**
 * The pairing page (P174): nearby devices of this page's kind (controllers or remotes) found by Bluetooth discovery; selecting one
 * bonds it. When Glass can't scan (no permission and no root to grant it), the only row is the system Bluetooth page.
 */
@Composable
internal fun CcPairPlatter(
    sz: CcSizes, title: String, tag: String, found: List<BtFound>, scanning: Boolean, canScan: Boolean, deviceIcon: Int,
    onPair: (BtFound) -> Unit, onSystem: () -> Unit,
) {
    val first = remember { FocusRequester() }
    // Again when Glass finds out it can't scan: the row that had focus ("Nearby") is then replaced by another, and focus would be lost (P213).
    LaunchedEffect(canScan) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(sz.gap), modifier = Modifier.width(sz.column + CC_BLEED * 2).padding(horizontal = CC_BLEED).testTag(tag)) {
        Text(title, style = CcSectionHeader, color = LocalPalette.current.primary, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        if (!canScan) {
            CcTile("Open Bluetooth settings", "VITRE can't scan from here", Shapes.pill, sz.column, sz.pill, onClick = onSystem, modifier = Modifier.focusRequester(first).testTag("cc-pair-system")) { fg ->
                PillContent(R.drawable.ic_bluetooth, "Open Bluetooth settings", "VITRE can't scan from here", fg, on = false, accent = fg)
            }
        } else {
            val status = if (scanning) "Searching\u2026" else if (found.isEmpty()) "Nothing found. Put the device in pairing mode." else "Choose a device"
            CcTile("Nearby", status, Shapes.pill, sz.column, sz.pill, onClick = {}, modifier = Modifier.focusRequester(first).testTag("cc-pair-status")) { fg ->
                PillContent(R.drawable.ic_bluetooth, "Nearby", status, fg, on = scanning, accent = Blue)
            }
            found.forEachIndexed { i, f ->
                val line = if (f.bonding) "Pairing\u2026" else "Select to pair"
                CcTile(f.name, line, Shapes.pill, sz.column, sz.pill, onClick = { onPair(f) }, modifier = Modifier.testTag("cc-pair-$i")) { fg ->
                    PillContent(deviceIcon, f.name, line, fg, on = f.bonding, accent = Blue)
                }
            }
        }
    }
}

@Composable
private fun rememberPairing(): BtPairing {
    val context = LocalContext.current
    val pairing = remember { BtPairing(context) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { pairing.stop() } }
    return pairing
}

/** Remotes and Game Controllers share one pairing page, filtered to the kind each was opened for. */
@Composable
private fun CcPairing(sz: CcSizes, title: String, tag: String, kind: BtKind, deviceIcon: Int, onBluetooth: () -> Unit, onPaired: (String) -> Unit) {
    val context = LocalContext.current
    val pairing = rememberPairing()
    var canScan by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(Unit) {
        canScan = (pairing.grantWithRoot() || !pairing.permissionsMissing()) && pairing.start()
    }
    // A bonded device ends the search: back to the list, with the new device waiting to be focused (P186).
    val bonded by pairing.bonded.collectAsStateWithLifecycle()
    LaunchedEffect(bonded) { bonded?.let { onPaired(it.name) } }
    val all by pairing.found.collectAsStateWithLifecycle()
    val scanning by pairing.scanning.collectAsStateWithLifecycle()
    // A class-less device (many remotes advertise none) is shown on both pages; a classified one only where it belongs.
    val found = remember(all) { all.filter { it.kind == kind || it.kind == BtKind.Other } }
    CcPairPlatter(sz, title, tag, found, scanning, canScan, deviceIcon, onPair = { pairing.pair(it.address) }, onSystem = onBluetooth)
}

@Composable
private fun CcRemotesPlatter(sz: CcSizes, pairing: Boolean, focusName: String?, onFocused: () -> Unit, onSettings: () -> Unit, onPair: () -> Unit, onBluetooth: () -> Unit, onPaired: (String) -> Unit, onSelect: (CcDeviceRow) -> Unit) {
    val context = LocalContext.current
    var tick by remember { androidx.compose.runtime.mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5_000); tick++ } }
    if (pairing) { CcPairing(sz, "Pair a Remote", "cc-pair-remotes", BtKind.Remote, R.drawable.ic_settings_remote, onBluetooth, onPaired); return }
    val rows = remember(tick) { dev.glasslauncher.system.RemoteList.read(context).map { CcDeviceRow(it.name, it.line, it.line.startsWith("Connected"), it.address) } }
    CcDevicePlatter(sz, "Remotes", "cc-remotes", rows, R.drawable.ic_settings_remote, "Remote Settings", onSettings, onPair, onSelect, focusName, onFocused)
}

/** The Game Controllers platter (P146, P174): Pair, Controller Settings (Fire's own page, as the tile opened before), then the connected controllers. */
@Composable
private fun CcControllersPlatter(sz: CcSizes, pairing: Boolean, focusName: String?, onFocused: () -> Unit, onSettings: () -> Unit, onPair: () -> Unit, onBluetooth: () -> Unit, onPaired: (String) -> Unit, onSelect: (CcDeviceRow) -> Unit) {
    val context = LocalContext.current
    var tick by remember { androidx.compose.runtime.mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5_000); tick++ } }
    if (pairing) { CcPairing(sz, "Pair a Controller", "cc-pair-controllers", BtKind.Controller, R.drawable.ic_sports_esports, onBluetooth, onPaired); return }
    val rows = remember(tick) { dev.glasslauncher.system.ControllerList.read(context).map { CcDeviceRow(it.name, it.line, true, it.id.toString()) } }
    CcDevicePlatter(sz, "Game Controllers", "cc-controllers", rows, R.drawable.ic_sports_esports, "Controller Settings", onSettings, onPair, onSelect, focusName, onFocused)
}

/**
 * AirPlay's platter (P129, REA: tvOS's Audio pill opens a panel rather than toggling in place): the receiver's
 * on/off switch and, while a sender is connected, its name. No install prompts here (that pill never shows unless
 * PhairPlay is installed: [airPlay] is null without it).
 */
@Composable
private fun CcAirPlayPlatter(
    sz: CcSizes,
    airPlay: Pair<dev.glasslauncher.system.AirPlayUi, dev.glasslauncher.system.AirPlayHub>?,
    speakers: List<dev.glasslauncher.data.AudioOutputConfig>,
    muteTv: Boolean,
    edit: ((LauncherConfig) -> LauncherConfig) -> Unit,
    active: Boolean,
    onBack: () -> Unit,
    onName: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Discovery runs only while this platter is on screen: the cached speakers at once, then one refresh (at most one
    // scan per 10 s, off Main). No network or no speakers leaves "This TV" alone.
    val found by produceState(dev.glasslauncher.sonos.SonosScan.get(context).cached, active) {
        if (active) value = dev.glasslauncher.sonos.SonosScan.get(context).refresh()
    }
    // Paired Bluetooth audio devices and which one is the system's A2DP sink: local reads, polled only while this platter is shown.
    val bt by produceState(emptyList<dev.glasslauncher.sonos.BtOutput>(), active) {
        while (active) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.glasslauncher.system.BtOutputs.read(context) }
            kotlinx.coroutines.delay(3_000)
        }
    }
    val rows = remember(found, speakers, bt) { dev.glasslauncher.sonos.OutputPicker.build(found, speakers, bt) }
    val canBtRoute = remember { dev.glasslauncher.system.BtRoute.canRoute(context) }
    val pickScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val reduceMotion = dev.glasslauncher.ui.LocalUiPrefs.current.reduceMotion
    // More rows than the screen holds (receiver controls, speakers, a volume row): scrolls like the grid, edges faded.
    val scroll = androidx.compose.foundation.rememberScrollState()
    Column(verticalArrangement = Arrangement.spacedBy(sz.gap), modifier = Modifier.ccEdgeFade(scroll).verticalScroll(scroll).width(sz.column + CC_BLEED * 2).padding(horizontal = CC_BLEED, vertical = 6.dp).testTag("cc-airplay")) {
        Text("AirPlay", style = CcSectionHeader, color = LocalPalette.current.primary, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        if (airPlay == null) {
            CcTile("AirPlay unavailable", "Install PhairPlay to receive AirPlay", Shapes.pill, sz.column, sz.pill, onClick = onBack, modifier = Modifier.focusRequester(first)) { fg ->
                PillContent(R.drawable.ic_airplay, "AirPlay unavailable", "Install PhairPlay to receive AirPlay", fg, on = false, accent = fg)
            }
        } else {
            val (ui, hub) = airPlay
            val page = dev.glasslauncher.system.AirPlayPageModel.of(ui)
            CcTile("AirPlay", if (ui.running) "On" else "Off", Shapes.pill, sz.column, sz.pill, onClick = { hub.setOn(!ui.running) }, modifier = Modifier.focusRequester(first), on = ui.running) { fg ->
                PillContent(R.drawable.ic_airplay, "AirPlay", if (ui.running) "On" else "Off", fg, on = ui.running, accent = Blue)
            }
            ui.sender?.let { sender ->
                CcTile("Connected", sender, Shapes.pill, sz.column, sz.pill, onClick = {}) { fg ->
                    PillContent(R.drawable.ic_airplay, "Connected", sender, fg, on = true, accent = Blue)
                }
            }
            // The Name and quality pills need PhairPlay's settings (protocol 2); an old one gets the switch only.
            if (page.kind == dev.glasslauncher.system.AirPlayPageModel.Kind.Full) {
                val name = dev.glasslauncher.system.AirPlayPageModel.nameValue(page.name)
                CcTile("Name", name, Shapes.pill, sz.column, sz.pill, onClick = onName) { fg ->
                    PillContent(R.drawable.ic_airplay, "Name", name, fg, on = false, accent = fg)
                }
                val quality = dev.glasslauncher.system.AirPlayPageModel.qualityValue(page.highQuality)
                CcTile("Video Quality", quality, Shapes.pill, sz.column, sz.pill, onClick = { hub.setSetting(dev.glasslauncher.system.AirPlayProtocol.SettingKey.HighResolution, !page.highQuality) }) { fg ->
                    PillContent(R.drawable.ic_airplay, "Video Quality", quality, fg, on = false, accent = fg)
                }
            }
        }
        // The tvOS picker (TVSSAudioRoutingPanelPlatterController): one list under Audio Output: This TV, then every other output
        // (paired Bluetooth headphones and speakers, network speakers), headphones first. A click applies at once; network speakers
        // multi-select, each selected one with its volume row (and the mute rule) under it. Bluetooth is one sink at a time, set through the system's A2DP.
        Text("Audio Output", style = CcSectionHeader, color = LocalPalette.current.primary, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp))
        val route by dev.glasslauncher.sonos.SonosRoutes.state(context).collectAsStateWithLifecycle()
        val canMute = remember { dev.glasslauncher.sonos.SonosRoutes.canMuteTv(context) }
        fun pickNetwork(row: dev.glasslauncher.sonos.PickerRow) {
            val change = dev.glasslauncher.sonos.OutputToggle.click(speakers, row.network)
            edit { it.copy(audioOutputs = change.choice, audioOutput = null) }
            for (c in change.commands) when (c) {
                dev.glasslauncher.sonos.OutputCommand.Stop -> dev.glasslauncher.sonos.SonosRoutes.stop(context)
                // Consent is asked every time a route starts; the speaker's volume is never set here.
                is dev.glasslauncher.sonos.OutputCommand.Start -> dev.glasslauncher.sonos.SonosRoutes.start(context, c.speakers, muteTv && canMute)
                is dev.glasslauncher.sonos.OutputCommand.Add -> dev.glasslauncher.sonos.SonosRoutes.addSpeaker(context, c.speaker)
                is dev.glasslauncher.sonos.OutputCommand.Remove -> dev.glasslauncher.sonos.SonosRoutes.removeSpeaker(context, c.host)
            }
        }
        fun pickBluetooth(row: dev.glasslauncher.sonos.PickerRow) {
            when (val a = dev.glasslauncher.sonos.OutputPicker.click(row, rows, canBtRoute)) {
                null -> Unit
                dev.glasslauncher.sonos.BtAction.OpenSettings -> {
                    dev.glasslauncher.system.RemoteNotice.show(context, "Connect it in Bluetooth settings", 4_000)
                    dev.glasslauncher.system.SystemControls.openBluetooth(context)
                }
                else -> pickScope.launch {
                    if (!dev.glasslauncher.system.BtRoute.run(context, a)) dev.glasslauncher.system.RemoteNotice.show(context, "Couldn't switch the Bluetooth output", 4_000)
                }
            }
        }
        fun pick(row: dev.glasslauncher.sonos.PickerRow) {
            when {
                row.bt != null -> pickBluetooth(row)
                row.section == dev.glasslauncher.sonos.OutputSection.ThisTv -> {
                    pickNetwork(row)
                    // This TV takes the sound back: a connected Bluetooth sink is let go where Glass may do that.
                    val live = rows.mapNotNull { it.bt }.filter { it.connected }
                    if (live.isNotEmpty()) {
                        if (canBtRoute) pickScope.launch { for (d in live) dev.glasslauncher.system.BtRoute.run(context, dev.glasslauncher.sonos.BtAction.Disconnect(d)) }
                        else dev.glasslauncher.system.RemoteNotice.show(context, "Disconnect the Bluetooth device in Settings to hear the TV", 4_000)
                    }
                }
                else -> pickNetwork(row)
            }
        }
        // One list under "Audio Output" (P172): headphones and speakers are not separate sections, each row has its own glyph.
        for (row in rows) {
            Column {
                CcRouteRow(sz, row, dev.glasslauncher.sonos.AudioOutputs.subtitle(row, route)) { pick(row) }
                val speaker = row.network
                if (speaker != null) androidx.compose.animation.AnimatedVisibility(
                    visible = row.selected,
                    enter = if (reduceMotion) androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(TOGGLE_FADE_MS)) else androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(TOGGLE_FADE_MS)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(TOGGLE_FADE_MS)),
                    exit = if (reduceMotion) androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(TOGGLE_FADE_MS)) else androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(TOGGLE_FADE_MS)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(TOGGLE_FADE_MS)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(sz.gap), modifier = Modifier.padding(top = sz.gap)) {
                        CcVolumeRow(sz, speaker)
                    }
                }
            }
        }
        if (rows.any { it.network != null && it.selected }) Column {
            val busy = route != dev.glasslauncher.sonos.RouteState.Idle && route !is dev.glasslauncher.sonos.RouteState.Failed
            // HDMI is a fixed-volume output on Fire TV: Android ignores a local mute there (the TV owns its volume over
            // CEC), so a toggle would promise something it can't do. Offer it only where a local mute works.
            if (canMute) {
                CcTile("Mute TV while playing on Sonos", if (muteTv) "On" else "Off", Shapes.pill, sz.column, sz.pill, onClick = { edit { it.copy(muteTvOnSonos = !muteTv) }; dev.glasslauncher.sonos.SonosRoutes.setMuteTv(context, !muteTv) },
                    modifier = Modifier.testTag("cc-audio-mute"), on = muteTv) { fg ->
                    PillContent(R.drawable.ic_tv, "Mute TV while playing on Sonos", if (muteTv) "On" else "Off", fg, on = muteTv, accent = Blue)
                }
            } else if (busy) {
                CcTile("Use your TV remote to mute the TV", null, Shapes.pill, sz.column, sz.pill, onClick = {},
                    modifier = Modifier.testTag("cc-audio-mute-note"), on = false) { fg ->
                    PillContent(R.drawable.ic_tv, "TV sound", "Use your TV remote to mute", fg, on = false, accent = fg)
                }
            }
        }
        // Pairing stays in Settings (tvOS: Remotes and Devices > Bluetooth); the picker only selects among paired devices. Its own row, set
        // apart from the outputs by a gap and a hairline (P172).
        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(LocalPalette.current.primary.copy(alpha = 0.18f)))
        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
        CcRouteRow(sz, dev.glasslauncher.sonos.PickerRow("pair", dev.glasslauncher.sonos.OutputSection.ThisTv, "Pair a device…", "Opens Bluetooth settings", dev.glasslauncher.sonos.OutputGlyph.Tv, false),
            "Opens Bluetooth settings", glyphRes = R.drawable.ic_bluetooth, mark = false) { dev.glasslauncher.system.SystemControls.openBluetooth(context) }
    }
}

/** One picker row (TVSSAudioRouteRenderer, format 8): glyph, name, a one-line subtitle, and checkmark.circle.fill or circle. */
@Composable
private fun CcRouteRow(sz: CcSizes, row: dev.glasslauncher.sonos.PickerRow, subtitle: String?, glyphRes: Int? = null, mark: Boolean = true, onClick: () -> Unit) {
    CcTile(row.title, listOfNotNull(subtitle, if (row.selected) "selected" else null).joinToString(", ").ifEmpty { null }, Shapes.pill, sz.column, sz.pill, onClick = onClick,
        modifier = Modifier.testTag("cc-audio-" + row.key), on = false) { fg ->
        val focused = LocalCcTileFocused.current
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(start = 2.dp, end = 12.dp)) {
            // Format 8 (FW): the device's own glyph at tvOS's 42 pt in the 43 dp column, no disc.
            Box(Modifier.width(sz.leadColumn).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Glyph(glyphRes ?: when (row.glyph) { dev.glasslauncher.sonos.OutputGlyph.Tv -> R.drawable.ic_tv; dev.glasslauncher.sonos.OutputGlyph.Headphones -> R.drawable.ic_headphones; dev.glasslauncher.sonos.OutputGlyph.Speaker -> R.drawable.ic_speaker }, fg, Modifier.size(sz.pillGlyph))
            }
            Column(Modifier.weight(1f)) {
                PillText(row.title, CcPillTitle, fg, lines = if (subtitle == null) 2 else 1)
                subtitle?.let { PillText(it, CcPillValue, fg.copy(alpha = 0.7f)) }
            }
            if (mark) {
                // The mark changes over 0.25 s (tvOS's row property animator), never pops.
                androidx.compose.animation.Crossfade(row.selected, animationSpec = androidx.compose.animation.core.tween(TOGGLE_FADE_MS), label = "route-mark") { sel ->
                    Glyph(if (sel) R.drawable.ic_check_circle_fill else R.drawable.ic_circle, if (sel && !focused) inkAccent(Blue) else fg, Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * The selected speaker's volume row. It reads GetVolume once when it appears and sets the volume only when the user
 * presses Left or Right on it (throttled); appearing or selecting never sets anything.
 */
@Composable
private fun CcVolumeRow(sz: CcSizes, speaker: dev.glasslauncher.data.AudioOutputConfig) {
    val reduceMotion = dev.glasslauncher.ui.LocalUiPrefs.current.reduceMotion
    val control = remember(speaker.host) { dev.glasslauncher.sonos.SonosControl("http://${speaker.host}:1400") }
    val sender = remember(speaker.host) {
        dev.glasslauncher.sonos.ThrottledVolume(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO), 150) { v -> runCatching { control.setVolume(v) } }
    }
    val model = remember(speaker.host) { dev.glasslauncher.sonos.VolumeRowModel { sender.set(it) } }
    androidx.compose.runtime.DisposableEffect(sender) { onDispose { sender.close() } }
    LaunchedEffect(speaker.host) { runCatching { dev.glasslauncher.sonos.AudioOutputs.volumeReader(speaker.host) }.onSuccess { model.load(it) } }
    val level = model.volume
    val rtl = dev.glasslauncher.ui.isRtl()
    CcTile("Volume", level?.let { "$it%" }, Shapes.pill, sz.column, sz.volumeRow, onClick = {}, onPlay = null,
        modifier = Modifier.testTag("cc-audio-volume").onPreviewKeyEvent { e ->
            val k = e.nativeKeyEvent.keyCode
            if (k != android.view.KeyEvent.KEYCODE_DPAD_LEFT && k != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return@onPreviewKeyEvent false
            if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) model.nudge(dev.glasslauncher.ui.horizontalStep(k, rtl))
            true
        }) { fg ->
        val shown by androidx.compose.animation.core.animateFloatAsState((level ?: 0) / 100f, if (reduceMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(120), label = "volume")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(start = 2.dp, end = 14.dp)) {
            Box(Modifier.width(sz.leadColumn).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_volume_up, fg, Modifier.size(sz.pillGlyph))
            }
            Canvas(Modifier.weight(1f).height(6.dp)) {
                val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                drawRoundRect(fg.copy(alpha = 0.28f), cornerRadius = r)
                if (level != null) drawRoundRect(fg, size = androidx.compose.ui.geometry.Size(size.width * shown, size.height), cornerRadius = r)
            }
        }
    }
}

/**
 * P203: [read] runs as Control Center opens ([active] turns true), never while it is closed or parked; closed (and
 * through its close animation) it returns what the last open read, so nothing on screen changes as it leaves.
 */
@Composable
internal fun <T> rememberWhileOpen(active: Boolean, initial: T, read: () -> T): T {
    val held = remember { arrayOf<Any?>(initial) }
    @Suppress("UNCHECKED_CAST")
    return remember(active) { if (active) read().also { held[0] = it; CcOpenReads.count++ } else held[0] as T }
}

/** Tests only: how many open-time reads Control Center has made (P203: none while it is parked). */
internal object CcOpenReads { @Volatile var count = 0 }
