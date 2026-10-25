package com.xander.files.data.storage

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Storage permission model across API levels. */
object StorageAccess {
    /** Test hook: forces the permission state (null = ask the platform). */
    @androidx.annotation.VisibleForTesting
    var overrideForTests: Boolean? = null

    fun hasFullAccess(context: Context): Boolean = overrideForTests ?:
        if (Build.VERSION.SDK_INT >= 30) runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    val legacyPermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    /** Intent for the "All files access" settings screen on Android 11+. */
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun allFilesAccessFallbackIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** On Android 11 and 12 the system picker can still grant Android/data and Android/obb trees. */
    val canGrantAndroidDataViaSaf: Boolean get() = Build.VERSION.SDK_INT in 30..32

    fun shouldShowRationale(activity: Activity) =
        legacyPermissions.any { activity.shouldShowRequestPermissionRationale(it) }
}
