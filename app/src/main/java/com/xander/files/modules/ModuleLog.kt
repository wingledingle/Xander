package com.xander.files.modules

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small log per module in Android/data/<app>/files/logs/<id>.log (reachable from Xander's own browser), so a
 * crash on a phone can be diagnosed afterwards: page errors, WebView renderer crashes, app crashes, and the
 * device / WebView versions they happened on. Kept under [MAX_BYTES].
 */
object ModuleLog {
    private const val MAX_BYTES = 256 * 1024
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var installed = false

    fun file(context: Context, id: String): File? =
        context.getExternalFilesDir(null)?.let { File(it, "logs/$id.log") }

    @Synchronized
    fun write(context: Context, id: String, line: String) {
        Log.i("ModuleLog", "$id: $line")
        val f = file(context, id) ?: return
        runCatching {
            f.parentFile?.mkdirs()
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES / 2))
            f.appendText("${time.format(Date())} $line\n")
        }
    }

    fun started(context: Context, id: String) {
        val webView = runCatching { if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } else null }.getOrNull()
        write(context, id, "--- start: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), WebView ${webView ?: "?"}")
        installCrashHook(context.applicationContext, id)
    }

    // Records an app crash in the module's log, then lets Android handle it as usual.
    private fun installCrashHook(context: Context, id: String) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(context, id, "APP CRASH on ${t.name}: ${Log.getStackTraceString(e).take(4000)}") }
            previous?.uncaughtException(t, e)
        }
    }
}
