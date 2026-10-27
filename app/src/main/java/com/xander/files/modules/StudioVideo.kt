package com.xander.files.modules

import android.content.Context
import android.net.Uri
import android.opengl.GLES20
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.xander.files.data.media.FsUri
import com.xander.files.data.media.RoutingDataSourceFactory
import com.xander.files.domain.fs.FileSystemRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

/**
 * The Studio's video deck plays through the phone's own video engine (ExoPlayer, the same one as Xander's video
 * player) in a native view laid over the page, instead of a WebView <video>: WebView playback froze on phones while
 * the native player plays the same files. Video FX are one GPU shader in the player's effect pipeline, so they cost
 * no per-frame copies. The page drives it with JSON commands and gets state back in `window.__xanderVideo.state`.
 */
@UnstableApi
class StudioVideo(private val context: Context, private val registry: FileSystemRegistry, private val files: HostFiles) {
    /** Pushes a state JSON to the page (set by the screen that owns the WebView). */
    @Volatile var push: (String) -> Unit = {}
    /** Where the page wants the video, in pixels relative to the WebView; null hides it. */
    val rect = MutableStateFlow<IntArray?>(null)
    val view: TextureView by lazy { TextureView(context) }

    private val main = Handler(Looper.getMainLooper())
    private val fx = FloatArray(FX_KEYS.size).also { defaults(it) }
    private var player: ExoPlayer? = null
    private var file: HostFile? = null
    private var announced = false
    private var seeking = false

    fun command(json: String) = main.post {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return@post
        runCatching { handle(o) }.onFailure { state("error", JSONObject().put("error", it.message ?: "video error")) }
    }

    private fun handle(o: JSONObject) {
        when (o.optString("op")) {
            "open" -> open(o.getString("token"))
            "play" -> player?.play()
            "pause" -> player?.pause()
            "seek" -> player?.let { seeking = true; it.seekTo((o.getDouble("t") * 1000).toLong().coerceAtLeast(0)) }
            "rate" -> player?.playbackParameters = PlaybackParameters(o.getDouble("r").toFloat().coerceIn(0.25f, 4f))
            "volume" -> player?.volume = if (o.optBoolean("muted")) 0f else o.optDouble("v", 1.0).toFloat().coerceIn(0f, 1f)
            "fx" -> setFx(o.getJSONObject("fx"))
            "rect" -> rect.value = if (o.optBoolean("show")) intArrayOf(o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h")) else null
            "close" -> release()
        }
    }

    private fun uriOf(f: HostFile): Uri = when (val s = f.source) {
        is HostSource.Fs -> if (s.path.isLocal) Uri.fromFile(File(s.path.path)) else FsUri.of(s.path)
        is HostSource.Content -> s.uri
        is HostSource.Local -> Uri.fromFile(s.file)
    }

    private fun open(token: String) {
        val f = files.get(token) ?: error("Unknown file")
        file = f; announced = false
        val p = player ?: ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSourceFactory(context, registry)))
            .build().also { p ->
                p.setVideoEffects(listOf<Effect>(FxEffect(fx)))
                p.setVideoTextureView(view)
                p.addListener(listener)
                player = p
            }
        p.setMediaItem(MediaItem.fromUri(uriOf(f)))
        p.prepare()
        p.playWhenReady = false
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying) { state(null); main.postDelayed(this, 250) }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(s: Int) {
            val p = player ?: return
            if (s == Player.STATE_READY && !announced) { announced = true; state("loadedmetadata") }
            else if (s == Player.STATE_READY && seeking) { seeking = false; state("seeked") }
            else if (s == Player.STATE_ENDED) { p.pause(); state("ended") }
        }
        override fun onIsPlayingChanged(playing: Boolean) {
            state(if (playing) "play" else "pause")
            if (playing) { main.removeCallbacks(ticker); main.postDelayed(ticker, 250) }
        }
        override fun onVideoSizeChanged(size: VideoSize) { if (announced) state(null) }
        override fun onPlayerError(e: PlaybackException) {
            ModuleLog.write(context, "studio", "native video error ${e.errorCodeName}: ${e.message}")
            state("error", JSONObject().put("error", e.errorCodeName).put("message", e.message ?: ""))
        }
    }

    private fun state(event: String?, extra: JSONObject = JSONObject()) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 }?.let { it / 1000.0 }
        extra.put("t", p.currentPosition / 1000.0).put("playing", p.isPlaying).put("rate", p.playbackParameters.speed.toDouble())
            .put("w", p.videoSize.width).put("h", p.videoSize.height).put("ready", announced)
        if (dur != null) extra.put("dur", dur)
        if (event != null) extra.put("event", event)
        push(extra.toString())
    }

    private fun setFx(o: JSONObject) {
        FX_KEYS.forEachIndexed { i, k -> if (o.has(k)) fx[i] = o.getDouble(k).toFloat() }
        // A paused frame is not redrawn by itself; re-seek to show the new look.
        player?.let { if (!it.isPlaying && announced) it.seekTo(it.currentPosition) }
    }

    /** Renders [startSec]..[endSec] of the open video with the current FX to an MP4 in Movies/Groovesmith/Clips. */
    fun export(name: String, startSec: Double, endSec: Double, done: (JSONObject?, String?) -> Unit) = main.post {
        val f = file ?: return@post done(null, "No video open")
        val uri = uriOf(f)
        if (uri.scheme == FsUri.SCHEME) return@post done(null, "Clip export works for videos stored on the phone")
        val dir = listOfNotNull(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), context.getExternalFilesDir(Environment.DIRECTORY_MOVIES))
            .map { File(it, "Groovesmith/Clips") }.firstOrNull { it.isDirectory || it.mkdirs() } ?: return@post done(null, "No folder to save into")
        val safe = name.replace(Regex("[^\\w\\- ]+"), "").trim().replace(Regex("\\s+"), "-").ifEmpty { "clip" }
        val out = generateSequence(0) { it + 1 }.map { File(dir, if (it == 0) "$safe.mp4" else "$safe-$it.mp4") }.first { !it.exists() }
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs((startSec * 1000).toLong()).setEndPositionMs((endSec * 1000).toLong()).build()).build()
        val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), listOf<Effect>(FxEffect(fx.copyOf())))).build()
        Transformer.Builder(context).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) { done(JSONObject().put("path", out.absolutePath), null) }
            override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                out.delete(); ModuleLog.write(context, "studio", "export failed: ${e.errorCodeName} ${e.message}"); done(null, e.message ?: "Export failed")
            }
        }).build().start(edited, out.absolutePath)
    }

    fun release() {
        main.removeCallbacks(ticker)
        player?.release(); player = null; file = null; rect.value = null
    }

    companion object {
        // Same order and defaults as VIDEO_FX in modules-web/www/modules/studio/videofx.js.
        val FX_KEYS = listOf("brightness", "contrast", "saturation", "hue", "rgbShift", "pixelate", "scanlines", "noise",
            "glitch", "posterize", "vignette", "invert", "mirror", "thermal", "zoom", "strobe")
        fun defaults(a: FloatArray) { a.fill(0f); a[1] = 1f; a[2] = 1f; a[14] = 1f }
    }
}

/** The Studio's video FX as one media3 GL effect; [params] is read every frame, so changes show at once. */
@UnstableApi
class FxEffect(private val params: FloatArray) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = FxShaderProgram(params, useHdr)
}

@UnstableApi
private class FxShaderProgram(private val params: FloatArray, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(VERT, FRAG).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }
    private var w = 1; private var h = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size { w = inputWidth; h = inputHeight; return Size(inputWidth, inputHeight) }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        program.use()
        program.setSamplerTexIdUniform("tex", inputTexId, 0)
        program.setFloatsUniform("res", floatArrayOf(w.toFloat(), h.toFloat()))
        program.setFloatUniform("time", (presentationTimeUs % 3_600_000_000L) / 1e6f)
        StudioVideo.FX_KEYS.forEachIndexed { i, k -> program.setFloatUniform(k, params[i]) }
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() { super.release(); program.delete() }

    companion object {
        const val VERT = "attribute vec4 aFramePosition; varying vec2 uv;\n" +
            "void main(){ gl_Position = aFramePosition; uv = vec2(aFramePosition.x * 0.5 + 0.5, 0.5 - aFramePosition.y * 0.5); }"
        // Port of FRAG in videofx.js; uv runs top-down like the page's, and texture reads flip it back to GL's.
        const val FRAG = """precision mediump float;
varying vec2 uv; uniform sampler2D tex; uniform vec2 res; uniform float time;
uniform float brightness, contrast, saturation, hue, rgbShift, pixelate, scanlines, noise, glitch, posterize, vignette, invert, mirror, thermal, zoom, strobe;
float rnd(vec2 c){ return fract(sin(dot(c, vec2(12.9898, 78.233))) * 43758.5453); }
vec4 at(vec2 t){ return texture2D(tex, vec2(t.x, 1.0 - t.y)); }
vec3 hueRot(vec3 c, float a){ float s = sin(a), k = cos(a); vec3 w = vec3(0.299, 0.587, 0.114);
  mat3 m = mat3(w.x+k*(1.-w.x)+s*(-w.x), w.x+k*(-w.x)+s*(0.143), w.x+k*(-w.x)+s*(-(1.-w.x)),
                w.y+k*(-w.y)+s*(-w.y), w.y+k*(1.-w.y)+s*(0.140), w.y+k*(-w.y)+s*(w.y),
                w.z+k*(-w.z)+s*(1.-w.z), w.z+k*(-w.z)+s*(-0.283), w.z+k*(1.-w.z)+s*(w.z)); return m * c; }
void main(){
  vec2 t = (uv - 0.5) / zoom + 0.5;
  if (mirror > 0.5) { t.x = t.x > 0.5 ? 1.0 - t.x : t.x; t.y = mirror > 0.75 && t.y > 0.5 ? 1.0 - t.y : t.y; }
  if (pixelate > 0.0) { float n = mix(res.x, 24.0, pixelate); vec2 b = vec2(n, n * res.y / res.x); t = (floor(t * b) + 0.5) / b; }
  if (glitch > 0.0) { float row = floor(t.y * 24.0); float g = step(1.0 - glitch * 0.35, rnd(vec2(row, floor(time * 12.0)))); t.x += g * (rnd(vec2(time, row)) - 0.5) * 0.25 * glitch; }
  float sh = rgbShift * 0.02 + glitch * 0.01 * sin(time * 40.0);
  vec3 c = vec3(at(t + vec2(sh, 0.0)).r, at(t).g, at(t - vec2(sh, 0.0)).b);
  c = hueRot(c, radians(hue));
  float l = dot(c, vec3(0.299, 0.587, 0.114));
  c = mix(vec3(l), c, saturation);
  c = (c - 0.5) * contrast + 0.5 + brightness;
  if (posterize > 0.0) { float lv = mix(32.0, 3.0, posterize); c = floor(c * lv) / lv; }
  if (thermal > 0.0) { float x = clamp(l, 0.0, 1.0); vec3 th = mix(vec3(0.0, 0.0, 0.5), vec3(0.0, 1.0, 1.0), smoothstep(0.0, 0.33, x));
    th = mix(th, vec3(1.0, 1.0, 0.0), smoothstep(0.33, 0.66, x)); th = mix(th, vec3(1.0, 0.0, 0.0), smoothstep(0.66, 1.0, x)); c = mix(c, th, thermal); }
  if (scanlines > 0.0) c *= 1.0 - scanlines * 0.45 * (0.5 + 0.5 * sin(uv.y * res.y * 3.14159));
  if (noise > 0.0) c += (rnd(uv * res + time) - 0.5) * noise * 0.35;
  if (vignette > 0.0) { vec2 d = uv - 0.5; c *= 1.0 - vignette * dot(d, d) * 2.2; }
  if (invert > 0.5) c = 1.0 - c;
  if (strobe > 0.0) c *= 1.0 - strobe * step(0.5, fract(time * 8.0));
  if (t.x < 0.0 || t.x > 1.0 || t.y < 0.0 || t.y > 1.0) c = vec3(0.0);
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}"""
    }
}
