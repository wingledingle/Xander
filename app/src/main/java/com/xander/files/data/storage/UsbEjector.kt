package com.xander.files.data.storage

import android.content.Intent
import android.provider.Settings
import com.xander.files.data.fs.root.RootFileSystemProvider
import com.xander.files.data.fs.shizuku.ShizukuFileSystemProvider
import javax.inject.Inject
import javax.inject.Singleton

sealed interface EjectResult {
    data object Ejected : EjectResult
    /** Android does not let ordinary apps unmount drives; the system storage screen has the Eject button. */
    data class OpenSettings(val intent: Intent) : EjectResult
}

/** Safely unmounts USB drives / SD cards through root or Shizuku (`sm unmount`), else hands off to Settings. */
@Singleton
class UsbEjector @Inject constructor(
    private val root: RootFileSystemProvider,
    private val shizuku: ShizukuFileSystemProvider,
) {
    suspend fun eject(v: VolumeInfo): EjectResult {
        val uuid = v.uuid
        if (uuid != null) {
            val run: (suspend (String) -> String?)? = when {
                root.isAvailable() -> { c -> root.exec(c) }
                shizuku.isAvailable() -> { c -> runCatching { shizuku.exec(c) }.getOrNull() }
                else -> null
            }
            if (run != null) {
                val id = run("sm list-volumes public")?.let { volumeId(it, uuid) }
                if (id != null) {
                    run("sync; sm unmount $id")
                    val after = run("sm list-volumes public").orEmpty()
                    if (after.lines().none { it.startsWith("$id ") && it.contains(" mounted") }) return EjectResult.Ejected
                }
            }
        }
        return EjectResult.OpenSettings(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        /** Parses `sm list-volumes` lines such as "public:8,1 mounted 1A2B-3C4D". */
        fun volumeId(listing: String, uuid: String): String? =
            listing.lines().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size >= 3 && it[2].equals(uuid, true) }?.get(0)
    }
}
