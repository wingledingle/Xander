package com.xander.files.data.storage

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class VolumeKind { INTERNAL, SD_CARD, USB }

data class VolumeInfo(
    val id: String,
    val label: String,
    val kind: VolumeKind,
    val root: FsPath,
    val directPath: String?,
    val uuid: String?,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
    val total: Long,
    val free: Long,
) {
    val used: Long get() = (total - free).coerceAtLeast(0)
    val fraction: Float get() = if (total > 0) used.toFloat() / total else 0f
}

@Singleton
class StorageVolumes @Inject constructor(@ApplicationContext private val context: Context) {

    private val sm = context.getSystemService(StorageManager::class.java)

    val primaryRoot: String get() = Environment.getExternalStorageDirectory().absolutePath

    /** Lists mounted volumes. Removable volumes whose path is unreadable will need a SAF grant. */
    @SuppressLint("DiscouragedPrivateApi")
    fun volumes(): List<VolumeInfo> {
        val out = ArrayList<VolumeInfo>()
        if (Build.VERSION.SDK_INT >= 24) {
            for (v in sm.storageVolumes) {
                if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val dir: File? = if (Build.VERSION.SDK_INT >= 30) v.directory else reflectPath(v)?.let(::File)
                val path = dir?.absolutePath
                val kind = when {
                    v.isPrimary && !v.isRemovable -> VolumeKind.INTERNAL
                    isUsb(v.getDescription(context), path) -> VolumeKind.USB
                    v.isRemovable -> VolumeKind.SD_CARD
                    else -> VolumeKind.INTERNAL
                }
                val (total, free) = space(path)
                out += VolumeInfo(
                    id = v.uuid ?: "primary",
                    label = v.getDescription(context),
                    kind = kind,
                    root = FsPath.local(path ?: "/storage/${v.uuid}"),
                    directPath = path,
                    uuid = v.uuid,
                    isPrimary = v.isPrimary,
                    isRemovable = v.isRemovable,
                    total = total, free = free,
                )
            }
        } else {
            val primary = Environment.getExternalStorageDirectory().absolutePath
            val (t, f) = space(primary)
            out += VolumeInfo("primary", "Internal storage", VolumeKind.INTERNAL, FsPath.local(primary), primary, null, true, false, t, f)
            context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { appDir ->
                val root = appDir.absolutePath.substringBefore("/Android/")
                val uuid = File(root).name
                val (tt, ff) = space(root)
                out += VolumeInfo(uuid, "SD card", VolumeKind.SD_CARD, FsPath.local(root), root, uuid, false, true, tt, ff)
            }
        }
        return out
    }

    fun volumeFor(path: String): VolumeInfo? =
        volumes().filter { it.directPath != null && (path == it.directPath || path.startsWith(it.directPath + "/")) }
            .maxByOrNull { it.directPath!!.length }

    fun space(path: String?): Pair<Long, Long> = try {
        if (path == null) 0L to 0L else StatFs(path).let { it.totalBytes to it.availableBytes }
    } catch (e: Exception) { 0L to 0L }

    private fun isUsb(desc: String, path: String?): Boolean =
        desc.contains("USB", true) || path?.contains("usb", true) == true || desc.contains("OTG", true)

    private fun reflectPath(v: android.os.storage.StorageVolume): String? = try {
        v.javaClass.getMethod("getPath").invoke(v) as String
    } catch (e: Exception) { null }

    /** Emits whenever a volume is mounted, removed or a USB device attaches/detaches. */
    fun changes(): Flow<Unit> = callbackFlow {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { trySend(Unit) }
        }
        val media = IntentFilter().apply {
            listOf(Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_REMOVED,
                Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_BAD_REMOVAL).forEach(::addAction)
            addDataScheme("file")
        }
        val usb = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, r, media, ContextCompat.RECEIVER_EXPORTED)
        val r2 = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { trySend(Unit) } }
        ContextCompat.registerReceiver(context, r2, usb, ContextCompat.RECEIVER_EXPORTED)
        trySend(Unit)
        awaitClose { context.unregisterReceiver(r); context.unregisterReceiver(r2) }
    }

    /** Is [path] on a removable volume that the app cannot write directly (pre-Android 11 SD cards). */
    fun needsSafForWrite(path: String): Boolean {
        val v = volumeFor(path) ?: return false
        if (!v.isRemovable) return false
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return false
        return true
    }
}
