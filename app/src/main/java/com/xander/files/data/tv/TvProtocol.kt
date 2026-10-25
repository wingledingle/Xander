package com.xander.files.data.tv

import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath
import org.json.JSONArray
import org.json.JSONObject

/** Wire format shared by the TV companion server and the phone-side client. */
object TvProtocol {
    const val SERVICE_TYPE = "_xandertv._tcp."
    const val DEFAULT_PORT = 8765
    const val TOKEN_HEADER = "X-Xander-Token"

    fun entryJson(e: FileEntry) = JSONObject().put("n", e.name).put("d", e.isDirectory).put("s", e.size).put("m", e.lastModified).put("h", e.isHidden)

    fun entryFrom(dir: FsPath, o: JSONObject) = FileEntry(dir.child(o.getString("n")), o.getBoolean("d"), size = o.optLong("s"), lastModified = o.optLong("m"))

    fun appJson(a: TvApp) = JSONObject().put("pkg", a.packageName).put("label", a.label).put("ver", a.versionName).put("code", a.versionCode)
        .put("sys", a.system).put("size", a.sizeBytes).put("launch", a.launchable)

    fun appFrom(o: JSONObject) = TvApp(o.getString("pkg"), o.optString("label"), o.optString("ver"), o.optLong("code"), o.optBoolean("sys"), o.optLong("size"), o.optBoolean("launch"))

    fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }
}

data class TvApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val system: Boolean,
    val sizeBytes: Long,
    val launchable: Boolean,
)

data class TvInfo(val name: String, val model: String, val android: String, val storageRoot: String, val total: Long, val free: Long, val remote: Boolean)

/** Remote-control keys understood by the TV. */
enum class TvKey { UP, DOWN, LEFT, RIGHT, CENTER, BACK, HOME, RECENTS, PLAY_PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN, MUTE, NOTIFICATIONS, POWER_DIALOG }
