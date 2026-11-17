package com.xander.files.modules

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import java.io.InputStream

/**
 * What the module host needs from the app's storage. Paths are the app's own strings (Xander's file-manager
 * paths, or plain file:// and content:// URIs in the standalone apps); the page only ever sees them as opaque.
 */
interface HostStorage {
    data class Info(val name: String, val mime: String?, val size: Long)
    data class Media(val path: String, val name: String, val size: Long, val modified: Long)

    suspend fun stat(path: String): Info
    /** Opens [path] at [offset]. Blocking; callers are on WebView or IO threads. */
    fun open(path: String, offset: Long): InputStream
    /** A direct file path when the file is plainly readable on this device (faster for decoders), else null. */
    fun localFile(path: String): String?
    /** URI for the platform player. With [dataSources] it may use a custom scheme. */
    fun mediaUri(path: String): Uri
    fun dataSources(context: Context): DataSource.Factory
    /** Videos ("video") or audio ("audio") on the device, newest first. */
    fun mediaIndex(kind: String): List<Media>
}
