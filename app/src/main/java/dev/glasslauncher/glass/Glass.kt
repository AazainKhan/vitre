package dev.glasslauncher.glass

import kotlinx.coroutines.isActive
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawOutline
import android.graphics.BitmapShader
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** The rim is this much brighter than each style's [GlassStyle.rim] (§10-B: brighter and softer). */
private const val RIM_GAIN = 1.3f

/** Peak alpha of light glass's bottom-edge shade (a cool mid-tone, not black): it stays lighter than the page. */
private const val LIGHT_EDGE_SHADE = 0.03f

/** Shared state for every glass surface: the current wallpaper and the size of the screen it covers. */
@Stable
class BackdropState {
    /**
     * Drawn in a translucent window over another app (Control Center's overlay): there's nothing to
     * sample, but the window really is see-through, so glass draws a translucent tint and the system
     * composites the app behind it.
     */
    var translucentWindow = false
    /** The scene behind this glass is dimmed by this much (Control Center's wash): glass samples it dimmed too. */
    var sceneDim by androidx.compose.runtime.mutableFloatStateOf(0f)
    var backdrop by mutableStateOf<Backdrop?>(null)
        private set
    /** The backdrop being faded out after a scene change; only non-null for the duration of [fade]. */
    var previous by mutableStateOf<Backdrop?>(null)
        private set
    val fade = Animatable(1f)
    /** Glass surfaces cross-fade with the backdrop; off for quick scene changes (they snap: half the drawing). */
    var glassFades by mutableStateOf(true)

    /** Swaps in a new scene. The old one fades out (two passes) only for the short cross-fade. */
    /** [fadeMs]: the slow dissolve for title slides; app heroes, which change as focus moves, use a short one. */
    suspend fun swap(next: Backdrop, animate: Boolean, fadeMs: Int = 550) {
        val old = backdrop
        if (old == null || !animate) {
            previous = null
            backdrop = next
            fade.snapTo(1f)
            return
        }
        previous = old
        backdrop = next
        try {
            fade.snapTo(0f)
            fade.animateTo(1f, androidx.compose.animation.core.tween(fadeMs))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // fade.snapTo(1f) from elsewhere (Control Center opening, P320) ends the fade early: that is a finished
            // swap, so the caller goes on. Only a real cancellation of this coroutine propagates.
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw e
        } finally {
            // Also when a newer swap cancels this one mid-fade: a previous left behind made every glass
            // surface (and the backdrop) draw twice for good, and kept its bitmaps alive (perf-gate: 12%
            // janky frames, +26 MB).
            previous = null
        }
    }

    /** Drops the scene (Control Center's capture of the screen, when it closes). */
    fun clear() { previous = null; backdrop = null }

    var rootSize by mutableStateOf(IntSize.Zero)
    /** 0 = sharp wallpaper, 1 = fully blurred (grid scrolled). Animated by the home screen. */
    val wallpaperBlur = Animatable(0f)
    /**
     * How much of the glass texture shows once the blur is back at 0: Home fades it in after a scroll
     * lands, so the tray and folders frost back instead of snapping (blending during the scroll itself
     * cost ~6% janky frames).
     */
    val textureIn = Animatable(1f)
    /** Optional frozen, blurred snapshot of the screen used behind overlays such as folders. */
    var overlay by mutableStateOf<ImageBitmap?>(null)
    /** Accessibility: glass becomes nearly opaque. */
    var reduceTransparency by mutableStateOf(false)
    /** Light appearance: overlay snapshots get the same text-safe range as baked glass. */
    var light = false
    /** The screen with the dock's soft blur, taken when an overlay opens (shown behind Control Center). */
    var overlaySoft by mutableStateOf<ImageBitmap?>(null)
    /** Home as it was when Control Center opened; drawn instead of Home while Control Center is up. */
    var overlaySharp by mutableStateOf<ImageBitmap?>(null)
    /** Home is fully covered (Control Center's backdrop): skip drawing it. */
    var homeHidden by mutableStateOf(false)
}

val LocalBackdrop = staticCompositionLocalOf { BackdropState() }

data class GlassStyle(
    val tint: Color,
    val highlight: Float,
    val rim: Float,
    val useOverlay: Boolean = false,
    /**
     * Clear glass (the dock tray): samples a lightly blurred copy so the art stays readable through
     * it, and bends the content along the edge. Frosted glass (panels) samples the heavy blur.
     */
    val clear: Boolean = false,
    /** Clear glass that carries text: samples the text-safe copy of the clear texture. */
    val legible: Boolean = false,
    /**
     * Light glass over a light page has no dark side to define its edge: a faint cool band just inside it (P18).
     * Two extra strokes, so only the few large surfaces that need it set it (the tray, menus and panels), never
     * the many small ones (Control Center tiles, the status pill).
     */
    val edgeBand: Boolean = false,
) {
    companion object {
        // tvOS 27: milky glass in light appearance, smoky in dark; tint comes from the blurred content.
        fun panel(light: Boolean) = if (light) GlassStyle(Color.White.copy(alpha = 0.30f), 0.22f, 0.45f, edgeBand = true)
        else GlassStyle(Color(0xFF1A1D24).copy(alpha = 0.14f), 0.10f, 0.30f)

        fun shelf(light: Boolean) = if (light) GlassStyle(Color.White.copy(alpha = 0.12f), 0.16f, 0.5f, clear = true, edgeBand = true)
        else GlassStyle(Color.White.copy(alpha = 0.03f), 0.08f, 0.32f, clear = true)

        fun overlay(light: Boolean) = panel(light).copy(useOverlay = true)

        /**
         * Control Center tiles: the dock's clear glass (lightly blurred art, rim), sampled
         * from the text-safe copy of that texture so labels keep 4.5:1 on any art.
         */
        fun control(light: Boolean) = if (light) GlassStyle(Color.White.copy(alpha = 0.2f), 0.22f, 0.55f, clear = true, legible = true)
        else GlassStyle(Color.White.copy(alpha = 0.06f), 0.12f, 0.4f, clear = true, legible = true)
    }
}

/**
 * Liquid-glass surface that works on any API level: draws the pre-blurred wallpaper (or overlay
 * snapshot) that sits behind this element, then a tint, a specular top highlight and a lit rim.
 */
fun Modifier.glass(state: BackdropState, shape: Shape, style: GlassStyle): Modifier =
    this then GlassElement(state, shape, style)

private data class GlassElement(
    val state: BackdropState,
    val shape: Shape,
    val style: GlassStyle,
) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(state, shape, style)
    override fun update(node: GlassNode) {
        node.state = state
        node.shape = shape
        node.style = style
        node.invalidateDraw()
    }
}

private class GlassNode(
    var state: BackdropState,
    var shape: Shape,
    var style: GlassStyle,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private var origin = Offset.Zero
    private var cachedSize = Size.Unspecified
    private var cachedOutline: Outline? = null
    private var shaderSource: ImageBitmap? = null
    private var backdropShader: BitmapShader? = null
    private var prevShaderSource: ImageBitmap? = null
    private var prevShader: BitmapShader? = null
    private val prevMatrix = Matrix()
    private var overlayShader: Shader? = null
    private var overlayKey: Any? = null
    private val matrix = Matrix()

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInWindow()
        if (p != origin) {
            origin = p
            invalidateDraw()
        }
    }

    // The whole surface is one fill: backdrop BitmapShader (mapped to screen space), tint and specular
    // gradient are composed into a single shader. Fill-rate is the bottleneck on TV-stick GPUs, so
    // stacking separate translucent passes is avoided.
    override fun ContentDrawScope.draw() {
        if (size != cachedSize) {
            cachedSize = size
            cachedOutline = shape.createOutline(size, layoutDirection, this)
        }
        val outline = cachedOutline ?: return drawContent()
        val root = state.rootSize
        val backdrop = state.backdrop
        val blur = state.wallpaperBlur.value
        // Once Home's backdrop is blurring (scrolling toward, or resting on, the grid), frosted glass over
        // it looks the same as its tint alone, so skip sampling the texture and draw flat. The glass was
        // most of the GPU time on the dock-to-grid scroll (25% janky frames before, ~1% after).
        // Flat while the backdrop is blurred or blurring; the texture fades back in after the scroll lands.
        // Glass that carries text (overlays, Control Center) always keeps its texture: a flat tint fails
        // contrast, and a tile drawn mid-scroll could stay see-through until something redrew it.
        // Clear glass (the tray, the status pill) follows the blur (P57): textured over the sharp backdrop, flat over the
        // blurred grid, and cross-fading between on the blur's own clock. Drawn flat for the whole scroll, it looked
        // different moving than at rest and snapped when its texture faded back after landing. Only these two small
        // surfaces draw both while the blur moves, so the scroll stays on budget.
        val texture = if (style.useOverlay || style.legible) 1f
            else if (style.clear && !state.reduceTransparency) 1f - blur
            else if (blur > 0f) 0f else state.textureIn.value
        val overBlur = texture < 1f
        // Clear glass follows the blur behind it: light over the sharp hero, frosted once the grid is up.
        val clear = style.clear && !state.reduceTransparency
        val source = when {
            style.useOverlay -> state.overlay ?: backdrop?.blurredSoftware
            clear -> if (style.legible) backdrop?.clearLegible else backdrop?.clearSoftware
            else -> backdrop?.blurredSoftware
        }

        val opaque = state.reduceTransparency
        val key = listOf(style, size, source, opaque)
        if (key != overlayKey) {
            overlayKey = key
            val tint = (if (opaque) solidTint(style.tint) else style.tint).toArgb()
            val tintShader = LinearGradient(0f, 0f, 0f, 1f, tint, tint, Shader.TileMode.CLAMP)
            // Light glass (milky tint) has no black bevel: even 5% black over its lower part read as a grey
            // band, darker than the page behind (P14). Its bottom edge is a faint cool shade that eases in
            // over the lower 40% (smoothstep stops), so there's no start line and it never goes below the page.
            val lightGlass = style.tint.luminance() > 0.5f && style.tint.alpha >= 0.1f
            val gloss = Color.White.copy(alpha = style.highlight).toArgb()
            val clearColor = Color.Transparent.toArgb()
            val highlight = if (lightGlass) {
                // Four stops, like the dark glass: gloss at the top, a clear middle, then a faint cool shade ramping in
                // over the lower 40% (no start line, never darker than the page). An eased 7-stop version looked the
                // same but made light mode ~4-5% janky on the stick: across the tray, the stop count is per pixel (P71).
                LinearGradient(
                    0f, 0f, 0f, size.height,
                    intArrayOf(gloss, clearColor, clearColor, Color(0xFF7A84A6).copy(alpha = LIGHT_EDGE_SHADE).toArgb()),
                    floatArrayOf(0f, 0.35f, 0.6f, 1f),
                    Shader.TileMode.CLAMP,
                )
            } else LinearGradient(
                0f, 0f, 0f, size.height,
                // Gloss near the top, clear middle, soft inner shadow at the bottom (the bevel).
                intArrayOf(gloss, clearColor, clearColor, Color.Black.copy(alpha = 0.16f).toArgb()),
                floatArrayOf(0f, 0.35f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
            overlayShader = ComposeShader(tintShader, highlight, PorterDuff.Mode.SRC_OVER)
        }
        if (overBlur) {
            // Flat tint: over a blurred backdrop this is what frosted glass looks like (tvOS's grid tray is
            // a flat translucent slab), and texture shaders here tipped the GPU over budget.
            val flat = when {
                state.reduceTransparency -> solidTint(style.tint)
                state.translucentWindow -> Color(0x7A2A2E37)
                else -> Color.White.copy(alpha = if (style.tint.luminance() > 0.5f) style.tint.alpha else 0.09f)
            }
            drawOutline(outline, flat, alpha = 1f - texture)
        }
        if (source != null && root != IntSize.Zero && texture > 0f) {
            val fill: Shader = run {
                if (source !== shaderSource) {
                    shaderSource = source
                    backdropShader = BitmapShader(source.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                }
                matrix.setScale(root.width / source.width.toFloat(), root.height / source.height.toFloat())
                matrix.postTranslate(-origin.x, -origin.y)
                backdropShader!!.setLocalMatrix(matrix)
                backdropShader!!
            }
            // While the backdrop cross-fades to a new slide, the glass does too, on the same clock: the
            // old picture underneath, the new one fading in over it. Otherwise the tray switches first.
            val previous = state.previous.takeIf { state.glassFades }
            val fade = state.fade.value
            val previousSource = previous?.let { if (style.useOverlay) null else if (clear) (if (style.legible) it.clearLegible else it.clearSoftware) else it.blurredSoftware }
            if (previousSource != null && fade < 1f) {
                val prevFill: Shader = run {
                    if (previousSource !== prevShaderSource) {
                        prevShaderSource = previousSource
                        prevShader = BitmapShader(previousSource.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    }
                    prevMatrix.setScale(root.width / previousSource.width.toFloat(), root.height / previousSource.height.toFloat())
                    prevMatrix.postTranslate(-origin.x, -origin.y)
                    prevShader!!.setLocalMatrix(prevMatrix)
                    prevShader!!
                }
                // The old picture is the one the tray was baked from (P215): drawn as that image, not composed per pixel again.
                val prevBaked = baked?.takeIf { (bakedKey as? List<*>)?.firstOrNull() === previousSource }
                if (prevBaked != null) drawImage(prevBaked, alpha = texture)
                else drawOutline(outline, ShaderBrush(ComposeShader(prevFill, overlayShader!!, PorterDuff.Mode.SRC_OVER)), alpha = texture)
                drawOutline(outline, ShaderBrush(ComposeShader(fill, overlayShader!!, PorterDuff.Mode.SRC_OVER)), alpha = fade * texture)
            } else {
                prevShaderSource = null; prevShader = null
                val baked = if (style.clear && !style.legible && !style.useOverlay) bakedFill(outline, fill, source) else null
                if (baked != null) drawImage(baked, alpha = texture)
                else drawOutline(outline, ShaderBrush(ComposeShader(fill, overlayShader!!, PorterDuff.Mode.SRC_OVER)), alpha = texture)
            }
            // Glass over a dimmed scene (Control Center's) samples the scene as dimmed, like everything around
            // it: without this its tiles looked brighter and more colourful than the muted screen beside them.
            if (state.sceneDim > 0f) drawOutline(outline, Color.Black.copy(alpha = state.sceneDim * texture))
        } else if (!overBlur) {
            // Nothing to sample yet (backdrop baking, layout not measured): glass that carries text draws a
            // solid frosted tint rather than its 6-20% tint alone, which read as a see-through tile.
            // (The tint's own alpha counts: the tray's 3% white is clear glass, not a milky white fill.)
            if (state.translucentWindow) drawOutline(outline, if (style.tint.luminance() * style.tint.alpha > 0.1f) Color.White.copy(alpha = 0.24f) else Color(0x7A2A2E37))
            else if (style.legible || style.useOverlay) drawOutline(outline, solidTint(style.tint))
            drawOutline(outline, ShaderBrush(overlayShader!!))
        }
        if (texture < 0.5f) drawOutline(outline, Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
        else drawRim(outline)
        drawContent()
    }

    // P215: the clear glass's fill (backdrop texture + tint + gloss, composed per pixel) was most of the GPU time on every
    // animated Home frame (the tray: janky 13% -> 2% without it). At rest it is the same picture every frame, so it is drawn
    // once into a bitmap of its own shape and drawn as one plain image; it is baked again only when the backdrop, size or
    // place changes. While the surface is moving (the expand slide) the live fill is used, not a bake per frame.
    private var bakedKey: Any? = null
    private var baked: ImageBitmap? = null
    private var lastMoveMs = 0L
    private var lastOrigin = Offset.Unspecified

    private fun ContentDrawScope.bakedFill(outline: Outline, fill: Shader, source: ImageBitmap): ImageBitmap? {
        val now = android.os.SystemClock.uptimeMillis()
        val placed = Offset(origin.x.roundToInt().toFloat(), origin.y.roundToInt().toFloat())
        if (placed != lastOrigin) { lastOrigin = placed; lastMoveMs = now }
        if (now - lastMoveMs < BAKE_AFTER_STILL_MS) { invalidateDraw(); return null }
        val key = listOf(source, size, placed, overlayKey)
        if (key == bakedKey) return baked
        if (key == bakingKey) return null
        val w = size.width.toInt(); val h = size.height.toInt()
        if (w <= 0 || h <= 0) return null
        // Baked off the main thread (on the stick a bake on the frame was a 20-60 ms stall); the live fill draws until it lands.
        bakingKey = key
        // The tint and gloss shader is never changed once built (a new key builds a new one), so the baker may share it.
        val overlay = overlayShader!!
        val m = android.graphics.Matrix().also { fill.getLocalMatrix(it) }
        val path = android.graphics.Path().also { p -> when (outline) {
            is Outline.Rounded -> outline.roundRect.let { r -> p.addRoundRect(r.left, r.top, r.right, r.bottom, floatArrayOf(r.topLeftCornerRadius.x, r.topLeftCornerRadius.y, r.topRightCornerRadius.x, r.topRightCornerRadius.y, r.bottomRightCornerRadius.x, r.bottomRightCornerRadius.y, r.bottomLeftCornerRadius.x, r.bottomLeftCornerRadius.y), android.graphics.Path.Direction.CW) }
            is Outline.Rectangle -> outline.rect.let { r -> p.addRect(r.left, r.top, r.right, r.bottom, android.graphics.Path.Direction.CW) }
            is Outline.Generic -> p.set(outline.path.asAndroidPath())
        } }
        val sourceBitmap = source.asAndroidBitmap()
        BAKER.execute {
            // Its own shader objects: the live ones keep being re-pointed on the main thread.
            val own = BitmapShader(sourceBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { it.setLocalMatrix(m) }
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply { this.shader = ComposeShader(own, overlay, PorterDuff.Mode.SRC_OVER) }
            // The backdrop texture can be a HARDWARE bitmap, which a software canvas can't draw: the fill is recorded and
            // rendered by the GPU straight into a HARDWARE bitmap (no upload either). A software source (the JVM tests)
            // is drawn in software. A bake that fails leaves the live fill for that key.
            val image = runCatching {
                if (sourceBitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
                    val picture = android.graphics.Picture()
                    picture.beginRecording(w, h).drawPath(path, paint)
                    picture.endRecording()
                    android.graphics.Bitmap.createBitmap(picture, w, h, android.graphics.Bitmap.Config.HARDWARE)
                } else android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { android.graphics.Canvas(it).drawPath(path, paint) }
            }.onFailure { android.util.Log.w("GlassFill", "bake failed", it) }.getOrNull()?.asImageBitmap()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (bakingKey == key) {
                    // The bake this replaces is this node's alone (P216): a HARDWARE buffer, released now rather than at the next
                    // collection (the renderer keeps its own reference while a frame still uses it). Not mid-crossfade: the fade draws it as its old layer.
                    val old = baked
                    bakingKey = null; bakedKey = key; baked = image
                    if (state.previous == null) old?.asAndroidBitmap()?.takeIf { it.config == android.graphics.Bitmap.Config.HARDWARE && it !== image?.asAndroidBitmap() }?.recycle()
                    if (isAttached) invalidateDraw()
                }
            }
        }
        return null
    }

    private var bakingKey: Any? = null

    private fun solidTint(tint: Color): Color =
        if (tint.luminance() > 0.5f) Color(0xF2F4F5F8) else Color(0xF21A1D25)

    private fun DrawScope.drawRim(outline: Outline) {
        // Edges are defined by light, not lines: bright along the top, fading out down the sides, and a
        // little light caught by the bottom edge too (tvOS 27's glossier rim). One 1 dp pass: a soft 3 dp glow
        // under it and the drawBitmapMesh lens edge (§10-B) together took perf-gate p90 from 12 to 19 ms.
        val rim = (style.rim * RIM_GAIN).coerceAtMost(1f)
        fun brush(a: Float) = Brush.verticalGradient(
            0f to Color.White.copy(alpha = a),
            0.3f to Color.White.copy(alpha = a * 0.14f),
            0.8f to Color.White.copy(alpha = a * 0.05f),
            1f to Color.White.copy(alpha = a * 0.3f),
        )
        // Light glass over a light page: a faint cool band just inside the edge (two overlapping strokes, so it
        // fades inward and is never a line), then the bright rim on top (P18). Only on styles that ask for it.
        if (style.edgeBand) {
            drawOutline(outline, EDGE_BAND_WIDE, style = Stroke(width = 4.dp.toPx()))
            drawOutline(outline, EDGE_BAND_NARROW, style = Stroke(width = 2.dp.toPx()))
        }
        drawOutline(outline, brush(rim), style = Stroke(width = 1.dp.toPx()))
    }
}

private const val BAKE_AFTER_STILL_MS = 120L

private val BAKER = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "glass-fill").apply { priority = Thread.MIN_PRIORITY } }

private val EDGE_BAND_WIDE = Color(0xFF5C6688).copy(alpha = 0.022f)
private val EDGE_BAND_NARROW = Color(0xFF5C6688).copy(alpha = 0.035f)
