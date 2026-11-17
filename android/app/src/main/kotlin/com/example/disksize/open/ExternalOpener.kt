package com.example.disksize.open

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.example.disksize.bridge.FlutterError
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object ExternalOpener {
    fun path(root: String, relativeHex: String): File {
        require(root.startsWith('/') && '\u0000' !in root && relativeHex.length % 2 == 0)
        if (relativeHex.length >= 8192) {
            throw FlutterError("path-too-long", "路径过长，Android 无法直接打开；可复制完整路径。", null)
        }
        val bytes = ByteArray(relativeHex.length / 2) { relativeHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val relative = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw FlutterError("invalid-path-encoding", "路径包含非 UTF-8 字节，Android 无法直接打开；可复制路径字节。", null)
        }
        if ('\u0000' in relative || (relative.isNotEmpty() && relative.split('/').any { it.isEmpty() || it == "." || it == ".." })) {
            throw FlutterError("invalid-path", "路径无法交给其他应用打开。", null)
        }
        val file = if (relative.isEmpty()) File(root) else File(root, relative)
        if (file.path.toByteArray(Charsets.UTF_8).size >= 4096) {
            throw FlutterError("path-too-long", "路径过长，Android 无法直接打开；可复制完整路径。", null)
        }
        return file
    }

    // The platform storage provider lets file managers navigate the real shared
    // storage location, including the root backend's /data/media alias.
    @SuppressLint("SdCardPath") // Recognize scanned aliases, rather than choosing an app storage directory.
    fun sharedDirectory(context: Context, file: File): Uri? {
        val user = Process.myUid() / 100000
        val aliases = mutableListOf("/storage/emulated/$user" to "primary", "/storage/self/primary" to "primary",
            "/sdcard" to "primary", "/data/media/$user" to "primary")
        for (volume in context.getSystemService(StorageManager::class.java).storageVolumes) {
            val directory = if (Build.VERSION.SDK_INT >= 30) volume.directory
                else if (volume.isPrimary) Environment.getExternalStorageDirectory() else volume.uuid?.let { File("/storage/$it") }
            val id = if (volume.isPrimary) "primary" else volume.uuid
            if (directory != null && id != null) aliases.add(directory.path to id)
        }
        val path = file.toPath().normalize().toString()
        for ((root, id) in aliases) {
            if (path == root || path.startsWith("$root/")) {
                val relative = path.removePrefix(root).removePrefix("/")
                return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "$id:$relative")
            }
        }
        return null
    }

    fun prepare(context: Context, selectedFile: File, kind: String): Intent {
        if (kind !in setOf("regular", "directory")) throw FlutterError("unsupported-entry", "符号链接和特殊文件暂不支持直接打开。", null)
        // Root scans see primary shared storage below /data/media, which an
        // ordinary UID must instead access through the current user's FUSE mount.
        val primaryAlias = "/data/media/${Process.myUid() / 100000}"
        val file = if (kind == "regular" && selectedFile.path.startsWith("$primaryAlias/"))
            File(Environment.getExternalStorageDirectory(), selectedFile.path.removePrefix("$primaryAlias/")) else selectedFile
        if (kind == "directory") {
            val shared = sharedDirectory(context, file)
            if (shared != null) return Intent(Intent.ACTION_VIEW).setDataAndType(shared, DocumentsContract.Document.MIME_TYPE_DIR)
        }
        try {
            val stat = Os.lstat(file.path)
            val expected = if (kind == "directory") OsConstants.S_ISDIR(stat.st_mode) else OsConstants.S_ISREG(stat.st_mode)
            if (!expected) throw FlutterError("entry-changed", "目标类型已变化或已成为链接，请重新扫描。", null)
            if (!file.canRead() || (kind == "directory" && !file.canExecute())) {
                throw FlutterError("entry-inaccessible", "当前应用无法读取此路径。Root 扫描权限不能传给其他应用；可复制路径到支持 Root 的文件管理器。", null)
            }
            val uri = SelectedDocumentsProvider.issue(context, file)
            return Intent(Intent.ACTION_VIEW).setDataAndType(uri, SelectedDocumentsProvider.mime(file)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (kind == "directory") addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                clipData = ClipData.newRawUri("DiskSize", if (kind == "directory")
                    DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getDocumentId(uri)) else uri)
            }
        } catch (error: ErrnoException) {
            throw FlutterError(if (error.errno == OsConstants.ENOENT) "entry-not-found" else "entry-inaccessible",
                if (error.errno == OsConstants.ENOENT) "文件或目录已不存在，快照仅保留扫描时的记录。"
                else "当前应用无法访问此路径。Root 扫描权限不能传给其他应用；可复制路径到支持 Root 的文件管理器。", null)
        } catch (_: IOException) {
            throw FlutterError("entry-inaccessible", "无法读取此位置或保存临时打开授权，请确认路径仍存在后重试。", null)
        }
    }

    fun launch(activity: Activity, intent: Intent) {
        if (activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
            throw FlutterError("no-handler", "没有可打开此项目的应用，请安装兼容的文件管理器或查看器。", null)
        }
        try { activity.startActivity(Intent.createChooser(intent, if (intent.type == DocumentsContract.Document.MIME_TYPE_DIR) "选择文件管理器" else "使用其他应用打开")) }
        catch (_: ActivityNotFoundException) { throw FlutterError("no-handler", "没有可打开此项目的应用。", null) }
        catch (_: SecurityException) { throw FlutterError("open-denied", "系统未允许打开此位置，可尝试复制路径到文件管理器。", null) }
    }
}
