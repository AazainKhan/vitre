package dev.glasslauncher.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Blur for small, downscaled bitmaps. Uses RenderScript's native gaussian (ScriptIntrinsicBlur) where
 * the platform still ships it (deprecated, but present through Android 11 on Fire TV): it is ~50x
 * faster than the Kotlin fallback, which made every top-shelf slide cost ~300 ms of CPU. Falls back
 * to three box-blur passes (also used by the JVM screenshot tests) with the same apparent strength.
 */
@Suppress("DEPRECATION")
object Blur {
    private var rs: android.renderscript.RenderScript? = null
    private var intrinsic: android.renderscript.ScriptIntrinsicBlur? = null

    /** Call once from Application.onCreate; without it (or if it fails) the CPU fallback is used. */
    fun init(context: android.content.Context) {
        runCatching {
            val r = android.renderscript.RenderScript.create(context.applicationContext)
            intrinsic = android.renderscript.ScriptIntrinsicBlur.create(r, android.renderscript.Element.U8_4(r))
            rs = r
        }
    }

    fun backdrop(source: Bitmap, width: Int, height: Int, radius: Int, saturation: Float = 1.35f): Bitmap {
        val small = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // A colour filter forces Skia's slow per-pixel path (139 ms of a ladder bake on the stick); the native
        // saturation after a plain downscale is the same matrix in a quarter of the time.
        val native = saturation != 1f && NativeImage.ok
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            if (saturation != 1f && !native) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) })
        }
        Canvas(small).drawBitmap(
            source,
            android.graphics.Rect(0, 0, source.width, source.height),
            android.graphics.Rect(0, 0, width, height),
            paint,
        )
        if (native && !NativeImage.saturate(small, saturation)) {
            Canvas(small).drawBitmap(small, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) }) })
        }
        blurInPlace(small, radius)
        return small
    }

    fun blurInPlace(bitmap: Bitmap, radius: Int) {
        if (radius <= 0) return
        if (gpuBlur(bitmap, radius)) return
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val scratch = IntArray(pixels.size)
        repeat(3) {
            boxHorizontal(pixels, scratch, width, height, radius)
            boxVertical(scratch, pixels, width, height, radius)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /** Tests only: skip RenderScript (Robolectric's copy blurs nothing), so the CPU path's result can be measured. */
    @Volatile var forceCpu = false

    /**
     * Three box passes of [boxRadius] approximate a gaussian with sigma = sqrt(((2r+1)^2 - 1) / 4);
     * RenderScript's radius maps to sigma = 0.4 r + 0.6, so this keeps the look identical.
     */
    @Synchronized
    private fun gpuBlur(bitmap: Bitmap, boxRadius: Int): Boolean {
        if (forceCpu) return false
        val r = rs ?: return false
        val blur = intrinsic ?: return false
        if (bitmap.config != Bitmap.Config.ARGB_8888 || !bitmap.isMutable) return false
        val sigma = kotlin.math.sqrt((((2 * boxRadius + 1) * (2 * boxRadius + 1)) - 1) / 4.0)
        val rsRadius = ((sigma - 0.6) / 0.4).toFloat().coerceIn(0.5f, 25f)
        return runCatching {
            val alloc = android.renderscript.Allocation.createFromBitmap(r, bitmap)
            val out = android.renderscript.Allocation.createTyped(r, alloc.type)
            blur.setRadius(rsRadius)
            blur.setInput(alloc)
            blur.forEach(out)
            out.copyTo(bitmap)
            alloc.destroy(); out.destroy()
            true
        }.getOrDefault(false)
    }

    private fun boxHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[row + (x - r).coerceIn(0, w - 1)]
                val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                a += (inn ushr 24) - (out ushr 24)
                rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun boxVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[(y - r).coerceIn(0, h - 1) * w + x]
                val inn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += (inn ushr 24) - (out ushr 24)
                rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    /** Mean perceived luminance 0..1, used to pick light or dark text over an image. */
    /**
     * Makes glass a safe background for text, per pixel: in dark appearance nothing brighter than 45%
     * (white text keeps at least 4.5:1 contrast on any art), in light appearance nothing darker than 50%
     * (the same for dark text). [strength] 0..1 blends in from the untouched image.
     */
    fun legible(bitmap: Bitmap, light: Boolean, strength: Float = 1f) {
        if (strength <= 0f) return
        val scale = if (light) 1f - 0.5f * strength else 1f - 0.55f * strength
        val offset = if (light) 255f * 0.5f * strength else 0f
        // Native: within 1 level of the matrix below on opaque pixels (translucent ones differ by premultiplied rounding), 62 -> 13 ms over a ladder bake on the stick.
        if (NativeImage.ok && NativeImage.scaleOffset(bitmap, scale, offset)) return
        val m = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, offset,
            0f, scale, 0f, 0f, offset,
            0f, 0f, scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        ))
        Canvas(bitmap).drawBitmap(bitmap, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
    }

    /** Average luminance of a [cols]×[rows] grid over the image, row by row (for text placed on art). */
    fun lumaGrid(source: Bitmap, cols: Int, rows: Int): FloatArray {
        val small = Bitmap.createScaledBitmap(source, cols, rows, true)
        val px = IntArray(cols * rows).also { small.getPixels(it, 0, cols, 0, 0, cols, rows) }
        if (small !== source) small.recycle()
        return FloatArray(px.size) { i -> val p = px[i]; ((0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f) }
    }

    /** A pale pastel of the image's average colour: white with a hint of its hue, for light-appearance washes. */
    fun pastel(bitmap: Bitmap): Int {
        val w = bitmap.width; val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        var r = 0L; var g = 0L; var b = 0L
        for (p in px) { r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF }
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV((r / px.size).toInt(), (g / px.size).toInt(), (b / px.size).toInt(), hsv)
        // Same hue, little saturation, near-white: a lavender, mint or sky page rather than grey.
        return android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 0.6f).coerceIn(0.06f, 0.16f), 0.97f))
    }

    fun luminance(bitmap: Bitmap): Float {
        val w = bitmap.width; val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        var sum = 0.0
        for (p in px) sum += (0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)) / 255.0
        return (sum / px.size).toFloat()
    }
}
