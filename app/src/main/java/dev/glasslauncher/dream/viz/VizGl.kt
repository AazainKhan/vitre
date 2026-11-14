package dev.glasslauncher.dream.viz

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.Choreographer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The GPU side of the visualizer (P334): one full-screen quad and the style's fragment shader, rendered at a fixed 1280 x 720 (the GPU scales it
 * up) at about 30 frames a second, only while it is running. The view exists only while music plays, so paused or idle it holds no GPU memory.
 * If a shader does not compile or the framebuffer is not complete, [onFailure] is called once and the host falls back to the Canvas bars.
 */
class VizGlView(context: Context, private val style: VisualizerStyle, private val onFailure: () -> Unit) : GLSurfaceView(context) {
    /** Read each time the saver opens, so `setprop` takes effect without restarting the app. */
    private val small = prop("debug.vitre.viz.small")
    private val stats = prop("debug.vitre.viz.stats")
    private val W = if (small) 960 else 1280
    private val H = if (small) 540 else 720
    private val renderer = Renderer()
    private var running = false
    private var lastRequest = 0L
    /** Every style but Grid keeps the last picture and draws on top of it (the old media-player look): two textures, swapped each frame. */
    private val feedback = style != VisualizerStyle.Grid
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            // about 30 frames a second on a 60 Hz display: every other vsync
            if (now - lastRequest >= FRAME_MS - 4) { lastRequest = now; requestRender() }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        preserveEGLContextOnPause = false
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        holder.setFixedSize(W, H)
    }

    /** The latest audio (the 256 x 3 image and the energies), read by the next frame. */
    fun push(analysis: AudioAnalysis) {
        synchronized(renderer.lock) {
            analysis.texture(renderer.audio)
            renderer.bass = analysis.bass; renderer.mid = analysis.mid; renderer.high = analysis.high; renderer.beat = analysis.beat
        }
    }

    fun tint(r: Float, g: Float, b: Float) = synchronized(renderer.lock) { renderer.tint[0] = r; renderer.tint[1] = g; renderer.tint[2] = b }

    fun start() {
        if (running) return
        running = true
        onResume()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        onPause()
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        val lock = Any()
        val audio = ByteArray(AudioAnalysis.BANDS * 3)
        val tint = floatArrayOf(0.95f, 0.95f, 0.95f)
        var bass = 0f; var mid = 0f; var high = 0f; var beat = 0f
        // what the shaders see: the latest capture (20 a second) eased in each frame (30 a second), so nothing moves in steps
        private val shown = FloatArray(AudioAnalysis.BANDS * 3)
        private var bassS = 0f; private var midS = 0f; private var highS = 0f; private var beatS = 0f
        private var bassSlow = 0f; private var kick = 0f
        private var flow = 0f; private var spin = 0f
        private var lastFrame = 0L
        private var program = 0
        private var blit = 0
        private var audioTex = 0
        private val fboTex = IntArray(2)
        private val fbo = IntArray(2)
        private var ping = 0
        private var warm = true   // set again whenever the surface is (re)created
        private val started = SystemClock.uptimeMillis()
        private var failed = false
        private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        private val audioBytes = ByteBuffer.allocateDirect(AudioAnalysis.BANDS * 3)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            failed = false
            val fragment = when (style) {
                VisualizerStyle.Spectrum -> VizShaders.SPECTRUM
                VisualizerStyle.Tunnel -> VizShaders.TUNNEL
                VisualizerStyle.Warp -> VizShaders.WARP_STEP
                VisualizerStyle.Ribbons -> VizShaders.RIBBONS
                VisualizerStyle.Grid -> VizShaders.GRID
                VisualizerStyle.Scope -> VizShaders.SCOPE
            }
            program = link(VizShaders.VERTEX, fragment)
            if (feedback) blit = link(VizShaders.VERTEX, VizShaders.BLIT)
            if (program == 0 || (feedback && blit == 0)) return fail("shader did not build")
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0); audioTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, audioTex)
            filters()
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, AudioAnalysis.BANDS, 3, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null)
            if (feedback) {
                GLES20.glGenTextures(2, fboTex, 0); GLES20.glGenFramebuffers(2, fbo, 0)
                for (i in 0..1) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i]); filters()
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, W, H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0)
                    GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) return fail("framebuffer incomplete")
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                warm = true; ping = 0
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { GLES20.glViewport(0, 0, width, height) }

        override fun onDrawFrame(gl: GL10?) {
            if (failed) return
            val t0 = SystemClock.uptimeMillis()
            val time = ((t0 - started) % 3_600_000L) / 1000f
            val dt = if (lastFrame == 0L) 0.033f else ((t0 - lastFrame) / 1000f).coerceIn(0.005f, 0.1f)
            lastFrame = t0
            synchronized(lock) {
                // fast to rise (a hit shows on the frame it is heard), a little slower to fall
                for (i in 0 until shown.size) {
                    val target = (audio[i].toInt() and 255) / 255f
                    shown[i] += (target - shown[i]) * (if (target > shown[i]) 0.8f else 0.35f)
                }
                bassS = ease(bassS, bass); midS = ease(midS, mid); highS = ease(highS, high); beatS = ease(beatS, beat)
            }
            for (i in 0 until shown.size) audioBytes.put(i, (shown[i] * 255f).toInt().coerceIn(0, 255).toByte())
            audioBytes.position(0)
            // a kick is the bass rising above where it has been lately; it lands at once and fades in about a quarter of a second
            bassSlow += (bassS - bassSlow) * 0.06f
            kick = maxOf(kick * 0.80f, ((bassS - bassSlow) * 3.5f).coerceIn(0f, 1f))
            // motion that quickens with the music is integrated, never multiplied into the clock, so it speeds up and slows down without a jump
            flow = (flow + dt * (0.35f + 1.6f * bassS)) % FLOW_PERIOD
            spin = (spin + dt * (0.05f + 0.35f * midS)) % TWO_PI
            val tn = synchronized(lock) { tint.copyOf() }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, audioTex)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, AudioAnalysis.BANDS, 3, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, audioBytes)
            if (feedback) {
                // A fresh surface starts from black buffers, and a picture that is built from them takes a second or two to fill the screen:
                // run the step a few times before the first frame is shown, so the saver never opens (or reopens) on black.
                if (warm) { warm = false; repeat(WARM_STEPS) { step(time, tn) } }
                step(time, tn)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); GLES20.glViewport(0, 0, W, H)
                GLES20.glUseProgram(blit)
                GLES20.glUniform3f(GLES20.glGetUniformLocation(blit, "uTint"), tn[0], tn[1], tn[2])
                floor(flow)
                GLES20.glUniform3f(GLES20.glGetUniformLocation(blit, "uFloor"), floorRgb[0], floorRgb[1], floorRgb[2])
                GLES20.glUniform1f(GLES20.glGetUniformLocation(blit, "uVivid"), if (style == VisualizerStyle.Warp) 1f else 0f)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[ping])   // the one step() just wrote
                GLES20.glUniform1i(GLES20.glGetUniformLocation(blit, "uPrev"), 1)
                quad(blit)
            } else {
                use(program, time, tn)
                quad(program)
            }
            if (stats) measure(t0)
        }

        private val floorRgb = FloatArray(3)

        /** A slowly drifting colour (the shaders' hue() in turns of 1/20pi, at a breathing level of 4 to 16 %), added to every pixel in the blit: silence is never black. */
        private fun floor(flow: Float) {
            val t = flow / FLOW_PERIOD * 2f   // two turns of hue per wrap of the flow counter, so the wrap is unseen
            val level = 0.10f + 0.06f * kotlin.math.sin(flow * 0.6f)   // 0.6 x the wrap is 6 whole turns of sine
            for (i in 0..2) {
                val f = (((t + i / 3f) % 1f) + 1f) % 1f
                floorRgb[i] = (kotlin.math.abs(f * 6f - 3f) - 1f).coerceIn(0f, 1f) * level
            }
        }

        /** One feedback pass: the last picture, changed, into the other texture. */
        private fun step(time: Float, tn: FloatArray) {
            val next = 1 - ping
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[next]); GLES20.glViewport(0, 0, W, H)
            use(program, time, tn)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[ping])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPrev"), 1)
            quad(program)
            ping = next
        }

        private fun ease(from: Float, to: Float) = from + (to - from) * (if (to > from) 0.8f else 0.35f)

        private var costSum = 0L; private var costMax = 0L; private var frames = 0

        /** `setprop debug.vitre.viz.stats 1`: how long the GPU takes per frame (a glFinish after the draw, so only while measuring). */
        private fun measure(t0: Long) {
            GLES20.glFinish()
            val cost = SystemClock.uptimeMillis() - t0
            costSum += cost; costMax = maxOf(costMax, cost)
            if (++frames == 120) {
                android.util.Log.i("VitreViz", "$style ${W}x$H: draw+finish avg ${costSum * 10 / 120 / 10f} ms, max $costMax ms over 120 frames")
                costSum = 0; costMax = 0; frames = 0
            }
        }

        private fun use(p: Int, time: Float, tn: FloatArray) {
            GLES20.glUseProgram(p)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uRes"), W.toFloat(), H.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uTime"), time)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uFlow"), flow)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSpin"), spin)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(p, "uTint"), tn[0], tn[1], tn[2])
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uBass"), bassS)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uMid"), midS)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uHigh"), highS)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uBeat"), beatS)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uKick"), kick)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uAudio"), 0)
        }

        private fun quad(p: Int) {
            val a = GLES20.glGetAttribLocation(p, "aPos")
            GLES20.glEnableVertexAttribArray(a)
            GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun filters() {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun link(vs: String, fs: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs); val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            if (v == 0 || f == 0) return 0
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
            val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) { android.util.Log.w("VitreViz", "link: ${GLES20.glGetProgramInfoLog(p)}"); return 0 }
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { android.util.Log.w("VitreViz", "compile: ${GLES20.glGetShaderInfoLog(s)}"); return 0 }
            return s
        }

        private fun fail(why: String) {
            if (failed) return
            failed = true
            android.util.Log.w("VitreViz", "$style: $why")
            post { onFailure() }
        }
    }

    companion object {
        /** 1280 x 720, or 960 x 540 with `setprop debug.vitre.viz.small 1`; `debug.vitre.viz.stats 1` logs the GPU time per frame (VitreViz). */
        private fun prop(name: String) = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java).invoke(null, name, "0") == "1"
        }.getOrDefault(false)
        const val FRAME_MS = 33L
        /** 20 pi: every sine of the flow counter in the shaders (in tenths) and every hue (in whole turns of 1/FP) comes round at once, so the wrap is invisible. */
        private const val FLOW_PERIOD = 62.831852f
        private const val TWO_PI = 6.2831855f
        /** Feedback passes run before a fresh surface's first frame is shown. */
        private const val WARM_STEPS = 10
    }
}
