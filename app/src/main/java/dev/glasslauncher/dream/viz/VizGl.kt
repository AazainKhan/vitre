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
 * The GPU side of the visualizer (P334): one full-screen quad and the style's fragment shader, rendered at a fixed 960 x 540 (the GPU scales it
 * up) at about 30 frames a second, only while it is running. The view exists only while music plays, so paused or idle it holds no GPU memory.
 * If a shader does not compile or the framebuffer is not complete, [onFailure] is called once and the host falls back to the Canvas bars.
 */
class VizGlView(context: Context, private val style: VisualizerStyle, private val onFailure: () -> Unit) : GLSurfaceView(context) {
    private val renderer = Renderer()
    private var running = false
    private var lastRequest = 0L
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
        holder.setFixedSize(WIDTH, HEIGHT)
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
        private var program = 0
        private var blit = 0
        private var audioTex = 0
        private val fboTex = IntArray(2)
        private val fbo = IntArray(2)
        private var ping = 0
        private val started = SystemClock.uptimeMillis()
        private var failed = false
        private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        private val audioBytes = ByteBuffer.allocateDirect(AudioAnalysis.BANDS * 3)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            failed = false
            val fragment = when (style) {
                VisualizerStyle.Bars -> VizShaders.BARS
                VisualizerStyle.Scope -> VizShaders.SCOPE
                VisualizerStyle.BarsWaves -> VizShaders.BARS_WAVES
                VisualizerStyle.Ribbons -> VizShaders.RIBBONS
                VisualizerStyle.Feedback -> VizShaders.FEEDBACK_STEP
            }
            program = link(VizShaders.VERTEX, fragment)
            if (style == VisualizerStyle.Feedback) blit = link(VizShaders.VERTEX, VizShaders.BLIT)
            if (program == 0 || (style == VisualizerStyle.Feedback && blit == 0)) return fail("shader did not build")
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0); audioTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, audioTex)
            filters()
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, AudioAnalysis.BANDS, 3, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null)
            if (style == VisualizerStyle.Feedback) {
                GLES20.glGenTextures(2, fboTex, 0); GLES20.glGenFramebuffers(2, fbo, 0)
                for (i in 0..1) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i]); filters()
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, WIDTH, HEIGHT, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0)
                    GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) return fail("framebuffer incomplete")
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { GLES20.glViewport(0, 0, width, height) }

        override fun onDrawFrame(gl: GL10?) {
            if (failed) return
            val time = (SystemClock.uptimeMillis() - started) / 1000f
            val b: Float; val m: Float; val h: Float; val beatNow: Float; val tn: FloatArray
            synchronized(lock) { audioBytes.clear(); audioBytes.put(audio); audioBytes.position(0); b = bass; m = mid; h = high; beatNow = beat; tn = tint.copyOf() }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, audioTex)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, AudioAnalysis.BANDS, 3, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, audioBytes)
            if (style == VisualizerStyle.Feedback) {
                val next = 1 - ping
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[next]); GLES20.glViewport(0, 0, WIDTH, HEIGHT)
                use(program, time, b, m, h, beatNow, tn)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[ping])
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPrev"), 1)
                quad(program)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); GLES20.glViewport(0, 0, WIDTH, HEIGHT)
                GLES20.glUseProgram(blit)
                GLES20.glUniform3f(GLES20.glGetUniformLocation(blit, "uTint"), tn[0], tn[1], tn[2])
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[next])
                GLES20.glUniform1i(GLES20.glGetUniformLocation(blit, "uPrev"), 1)
                quad(blit)
                ping = next
            } else {
                use(program, time, b, m, h, beatNow, tn)
                quad(program)
            }
        }

        private fun use(p: Int, time: Float, b: Float, m: Float, h: Float, beatNow: Float, tn: FloatArray) {
            GLES20.glUseProgram(p)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uRes"), WIDTH.toFloat(), HEIGHT.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uTime"), time)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(p, "uTint"), tn[0], tn[1], tn[2])
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uBass"), b)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uMid"), m)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uHigh"), h)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uBeat"), beatNow)
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
        const val WIDTH = 960
        const val HEIGHT = 540
        const val FRAME_MS = 33L
    }
}
