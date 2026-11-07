package com.xander.files.modules

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws the player's frames through the Studio's FX shader onto a TextureView with plain OpenGL ES 2: the player
 * decodes into a SurfaceTexture we own, and every frame is drawn once with the current [params]. This is the same
 * path camera and video-filter apps use, and it doesn't depend on media3's effect pipeline (which some phones
 * reject, leaving only colour effects). [onInput] gets the Surface to give the player; [onError] means "play plainly".
 */
@androidx.media3.common.util.UnstableApi
class FxRenderer(
    private val view: TextureView,
    private val params: FloatArray,
    private val onInput: (Surface) -> Unit,
    private val onError: (Throwable) -> Unit,
) : TextureView.SurfaceTextureListener {
    private val thread = HandlerThread("studio-fx").apply { start() }
    private val gl = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var input: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var program = 0
    private var tex = 0
    private var w = 1; private var h = 1
    private val st = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private val t0 = SystemClock.elapsedRealtime()
    private var lastFrame = 0L
    @Volatile private var released = false

    init {
        view.surfaceTextureListener = this
        view.surfaceTexture?.let { onSurfaceTextureAvailable(it, view.width, view.height) }
    }

    /** Redraws the last frame (new FX while paused). */
    fun redraw() = gl.post { draw() }

    override fun onSurfaceTextureAvailable(out: SurfaceTexture, width: Int, height: Int) {
        gl.post {
            runCatching { setUp(out, width, height) }.onFailure { e -> tearDown(); view.post { onError(e) } }
        }
    }
    override fun onSurfaceTextureSizeChanged(out: SurfaceTexture, width: Int, height: Int) { gl.post { w = width; h = height; draw() } }
    override fun onSurfaceTextureDestroyed(out: SurfaceTexture): Boolean {
        // Wait for the GL thread to let go of the window before the view frees it.
        val done = java.util.concurrent.CountDownLatch(1)
        gl.post { releaseWindow(); done.countDown() }
        done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        return true
    }
    override fun onSurfaceTextureUpdated(out: SurfaceTexture) {}

    private fun setUp(out: SurfaceTexture, width: Int, height: Int) {
        w = width; h = height
        if (display == EGL14.EGL_NO_DISPLAY) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize failed" }
        }
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
        val cfg = cfgs[0] ?: error("no EGL config")
        if (context == EGL14.EGL_NO_CONTEXT) {
            context = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed ${EGL14.eglGetError()}" }
        }
        surface = EGL14.eglCreateWindowSurface(display, cfg, out, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed ${EGL14.eglGetError()}" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
        if (program == 0) {
            program = link(VERT, OES_FRAG)
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val sti = SurfaceTexture(tex)
            sti.setOnFrameAvailableListener({ s -> if (!released && surface != EGL14.EGL_NO_SURFACE) { s.updateTexImage(); s.getTransformMatrix(st); lastFrame = SystemClock.elapsedRealtime(); draw() } }, gl)
            input = sti
            val sf = Surface(sti); inputSurface = sf
            view.post { if (!released) onInput(sf) }
            gl.post(animate)
        }
        draw()
    }

    // Grain, glitch, strobe and VHS wave move over time: keep drawing while paused, at 30 fps.
    private val animate = object : Runnable {
        override fun run() {
            if (released) return
            val moving = params[7] > 0f || params[8] > 0f || params[15] > 0f || params.getOrElse(16) { 0f } > 0f
            if (moving && SystemClock.elapsedRealtime() - lastFrame > 40) draw()
            gl.postDelayed(this, 33)
        }
    }

    private fun draw() {
        if (surface == EGL14.EGL_NO_SURFACE || program == 0) return
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "tex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "st"), 1, false, st, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "res"), w.toFloat(), h.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "time"), ((SystemClock.elapsedRealtime() - t0) % 3_600_000L) / 1000f)
        StudioVideo.FX_KEYS.forEachIndexed { i, k -> GLES20.glUniform1f(GLES20.glGetUniformLocation(program, k), params[i]) }
        val pos = GLES20.glGetAttribLocation(program, "aFramePosition")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 4, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(display, surface)
    }

    private fun link(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { s ->
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun releaseWindow() {
        if (surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface); surface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun tearDown() {
        releaseWindow()
        input?.release(); input = null
        inputSurface?.release(); inputSurface = null
        if (context != EGL14.EGL_NO_CONTEXT) { EGL14.eglDestroyContext(display, context); context = EGL14.EGL_NO_CONTEXT }
        if (display != EGL14.EGL_NO_DISPLAY) { EGL14.eglTerminate(display); display = EGL14.EGL_NO_DISPLAY }
        program = 0
    }

    fun release() {
        released = true
        if (view.surfaceTextureListener === this) view.surfaceTextureListener = null
        // Synchronous, so the TextureView's window is free for a plain player right after.
        val done = java.util.concurrent.CountDownLatch(1)
        gl.post { tearDown(); thread.quitSafely(); done.countDown() }
        done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val VERT = "attribute vec4 aFramePosition; varying vec2 uv;\n" +
            "void main(){ gl_Position = aFramePosition; uv = vec2(aFramePosition.x * 0.5 + 0.5, 0.5 - aFramePosition.y * 0.5); }"
        /** The export shader (FX_FRAG), reading the player's external texture through its transform matrix. */
        val OES_FRAG = "#extension GL_OES_EGL_image_external : require\n" + FX_FRAG
            .replace("uniform sampler2D tex;", "uniform samplerExternalOES tex; uniform mat4 st;")
            .replace("vec4 at(vec2 t){ return texture2D(tex, vec2(t.x, 1.0 - t.y)); }", "vec4 at(vec2 t){ return texture2D(tex, (st * vec4(t.x, 1.0 - t.y, 0.0, 1.0)).xy); }")
            .also { check(it.contains("samplerExternalOES") && it.contains("st * vec4")) }
        private val QUAD = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f)); position(0) }
    }
}
