package com.example.disksize.scan

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.example.disksize.bridge.ScanAccessStatus

internal object ScanAccess {
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
