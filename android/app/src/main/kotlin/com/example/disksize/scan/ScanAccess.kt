package com.example.disksize.scan

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.StatFs
import com.example.disksize.bridge.ScanAccessStatus
import org.json.JSONArray
import org.json.JSONObject

internal object ScanAccess {
    /** Ordinary app mount view; these are entry points, not additive volumes. */
    fun volumes(context: Context): String {
        val values = JSONArray()
        val manager = context.getSystemService(StorageManager::class.java)
        for (volume in manager.storageVolumes) {
            val directory = if (Build.VERSION.SDK_INT >= 30) volume.directory
                else if (volume.isPrimary) Environment.getExternalStorageDirectory() else null
            val item = JSONObject().put("label", volume.getDescription(context))
                .put("path", directory?.absolutePath ?: "").put("state", volume.state)
                .put("primary", volume.isPrimary).put("removable", volume.isRemovable)
            if (directory != null && volume.state in setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)) {
                try {
                    val stat = StatFs(directory.path)
                    item.put("totalBytes", stat.totalBytes).put("freeBytes", stat.freeBytes)
                        .put("availableBytes", stat.availableBytes).put("capturedAtMillis", System.currentTimeMillis())
                } catch (_: RuntimeException) { /* An unavailable mount still remains visible. */ }
            }
            values.put(item)
        }
        return JSONObject().put("status", "ok").put("volumes", values).toString()
    }

    fun read(context: Context): ScanAccessStatus {
        val legacy = Build.VERSION.SDK_INT == 29 && Environment.isExternalStorageLegacy()
        val shared = if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            legacy && context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        return ScanAccessStatus(
            apiLevel = Build.VERSION.SDK_INT.toLong(),
            ownPath = context.filesDir.absolutePath,
            externalOwnPath = context.getExternalFilesDir(null)?.absolutePath,
            sharedPath = Environment.getExternalStorageDirectory().absolutePath,
            sharedAccessGranted = shared,
            legacyStorage = legacy,
            notificationGranted = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
        )
    }

    fun requiresSharedAccess(context: Context, path: String): Boolean {
        val access = read(context)
        fun within(root: String) = path == root || path.startsWith("$root/")
        return within(access.sharedPath) && access.externalOwnPath?.let { !within(it) } != false
    }
}
