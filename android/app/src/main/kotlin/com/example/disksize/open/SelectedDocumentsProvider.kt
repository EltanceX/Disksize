package com.example.disksize.open

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.webkit.MimeTypeMap
import com.example.disksize.R
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.Locale
import java.util.UUID

/** Temporary, read-only scopes issued only by an explicit open action. */
class SelectedDocumentsProvider : DocumentsProvider() {
    override fun onCreate() = true

    private fun stat(file: File) = try { Os.lstat(file.path) } catch (error: ErrnoException) {
        throw FileNotFoundException("目标不存在或无法读取").apply { initCause(error) }
    }

    private fun resolve(id: String): Pair<Scope, File> {
        val token = id.substringBefore(':')
        val scope = scopes(requireNotNull(context)).firstOrNull { it.token == token }
            ?: throw FileNotFoundException("打开授权已过期，请回到 DiskSize 重新打开")
        val root = File(scope.path)
        val rootStat = stat(root)
        if (rootStat.st_dev != scope.device || rootStat.st_ino != scope.inode || OsConstants.S_ISLNK(rootStat.st_mode)) {
            throw FileNotFoundException("目标已变化，请重新打开")
        }
        val relative = if (id == token) "" else id.substringAfter(':')
        if (relative.isNotEmpty() && relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw FileNotFoundException("无效的相对路径")
        }
        val file = if (relative.isEmpty()) root else File(root, relative)
        val actual = file.canonicalFile
        if (actual.path != root.path && !actual.path.startsWith("${root.path.trimEnd('/')}/")) throw FileNotFoundException("目标不在授权范围内")
        val stat = stat(file)
        if (!OsConstants.S_ISDIR(stat.st_mode) && !OsConstants.S_ISREG(stat.st_mode)) throw FileNotFoundException("不支持链接或特殊文件")
        return scope to actual
    }

    private fun row(cursor: MatrixCursor, id: String, file: File) {
        val values = mapOf<String, Any?>(
            Document.COLUMN_DOCUMENT_ID to id,
            Document.COLUMN_DISPLAY_NAME to file.name.ifEmpty { file.path },
            Document.COLUMN_MIME_TYPE to mime(file),
            Document.COLUMN_SIZE to if (file.isFile) file.length() else null,
            Document.COLUMN_LAST_MODIFIED to file.lastModified(),
            Document.COLUMN_FLAGS to 0,
        )
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_ICON))
        // A stable root lets DocumentsUI restore a path even when it cached
        // roots before the user issued this particular temporary scope.
        val values = mapOf<String, Any>(Root.COLUMN_ROOT_ID to "selected", Root.COLUMN_DOCUMENT_ID to "selection",
            Root.COLUMN_TITLE to "DiskSize · 已选位置", Root.COLUMN_FLAGS to (Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD),
            Root.COLUMN_MIME_TYPES to "*/*", Root.COLUMN_ICON to R.mipmap.ic_launcher)
        result.addRow(result.columnNames.map { values[it] }.toTypedArray())
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: columns).also {
            if (documentId == "selection") {
                val values = mapOf<String, Any>(Document.COLUMN_DOCUMENT_ID to "selection", Document.COLUMN_DISPLAY_NAME to "已选位置",
                    Document.COLUMN_MIME_TYPE to Document.MIME_TYPE_DIR, Document.COLUMN_FLAGS to 0)
                it.addRow(it.columnNames.map { name -> values[name] }.toTypedArray())
            } else row(it, documentId, resolve(documentId).second)
        }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (parentDocumentId == "selection") {
            return MatrixCursor(projection ?: columns).also { result ->
                for (scope in scopes(requireNotNull(context))) {
                    try { row(result, scope.token, resolve(scope.token).second) } catch (_: Exception) { /* Expired or changed scope. */ }
                }
            }
        }
        val file = resolve(parentDocumentId).second
        if (!file.isDirectory) throw FileNotFoundException("目标不是目录")
        val result = MatrixCursor(projection ?: columns)
        Files.newDirectoryStream(file.toPath()).use { stream ->
            for (child in stream) {
                if (result.count == 1000) {
                    result.extras = Bundle().apply { putString(DocumentsContract.EXTRA_INFO, "仅显示前 1000 项；可在 DiskSize 中选择更深的目录打开。") }
                    break
                }
                val id = if (parentDocumentId.contains(':')) "$parentDocumentId/${child.fileName}" else "$parentDocumentId:${child.fileName}"
                try { row(result, id, resolve(id).second) } catch (_: Exception) { /* Unreadable, replaced or escaping child. */ }
            }
        }
        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        if (parentDocumentId == "selection") { resolve(documentId); true } else {
        val (parentScope, parent) = resolve(parentDocumentId)
        val (scope, file) = resolve(documentId)
        scope.token == parentScope.token && parent.isDirectory && file.path != parent.path && file.path.startsWith("${parent.path.trimEnd('/')}/")
        }
    } catch (_: Exception) { false }

    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        if (childDocumentId == "selection") {
            if (parentDocumentId != null && parentDocumentId != "selection") throw FileNotFoundException("目标不在授权范围内")
            return DocumentsContract.Path(if (parentDocumentId == null) "selected" else null, listOf("selection"))
        }
        val (scope, _) = resolve(childDocumentId)
        if (parentDocumentId != null && parentDocumentId != childDocumentId && !isChildDocument(parentDocumentId, childDocumentId)) {
            throw FileNotFoundException("目标不在授权范围内")
        }
        val path = mutableListOf("selection", scope.token)
        val relative = childDocumentId.substringAfter(':', "")
        if (relative.isNotEmpty()) {
            var current = ""
            for (part in relative.split('/')) {
                current = if (current.isEmpty()) part else "$current/$part"
                path.add("${scope.token}:$current")
            }
        }
        return DocumentsContract.Path(if (parentDocumentId == null) "selected" else null,
            if (parentDocumentId == null) path else path.dropWhile { it != parentDocumentId })
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("仅允许只读打开")
        signal?.throwIfCanceled()
        val (_, file) = resolve(documentId)
        val before = stat(file)
        if (!OsConstants.S_ISREG(before.st_mode)) throw FileNotFoundException("目标不是普通文件")
        val fd = try { Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0) }
            catch (error: ErrnoException) { throw FileNotFoundException("无法只读打开目标").apply { initCause(error) } }
        try {
            val actual = Os.fstat(fd)
            val result = ParcelFileDescriptor.dup(fd)
            try {
                val openedPath = Os.readlink("/proc/self/fd/${result.fd}")
                if (!OsConstants.S_ISREG(actual.st_mode) || actual.st_dev != before.st_dev || actual.st_ino != before.st_ino ||
                    File(openedPath).canonicalPath != file.path) throw FileNotFoundException("目标已变化，请重新打开")
                return result
            } catch (error: Exception) { result.close(); throw error }
        } finally { Os.close(fd) }
    }

    companion object {
        private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
        private const val lifetime = 24 * 60 * 60 * 1000L
        private data class Scope(val token: String, val path: String, val device: Long, val inode: Long, val expires: Long)
        private fun preferences(context: Context) = context.getSharedPreferences("selected-documents", Context.MODE_PRIVATE)
        private fun scopes(context: Context): List<Scope> = preferences(context).all.mapNotNull { (token, raw) ->
            try {
                val value = JSONObject(raw as String)
                if (value.getLong("expires") <= System.currentTimeMillis()) null else Scope(token, value.getString("path"),
                    value.getLong("device"), value.getLong("inode"), value.getLong("expires"))
            } catch (_: Exception) { null }
        }

        @SuppressLint("UseKtx") // Check synchronous commit success before handing a URI to another process.
        @Synchronized internal fun issue(context: Context, file: File): Uri {
            val actual = file.canonicalFile
            val stat = Os.lstat(actual.path)
            require(OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISDIR(stat.st_mode))
            val token = UUID.randomUUID().toString()
            val kept = scopes(context).sortedByDescending { it.expires }.take(15)
            val editor = preferences(context).edit().clear()
            for (scope in kept) editor.putString(scope.token, JSONObject().put("path", scope.path).put("device", scope.device)
                .put("inode", scope.inode).put("expires", scope.expires).toString())
            editor.putString(token, JSONObject().put("path", actual.path).put("device", stat.st_dev).put("inode", stat.st_ino)
                .put("expires", System.currentTimeMillis() + lifetime).toString())
            check(editor.commit()) { "无法保存临时打开授权" }
            val authority = "${context.packageName}.selected-documents"
            context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority), null)
            return if (file.isDirectory) DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(authority, token), token)
                else DocumentsContract.buildDocumentUri(authority, token)
        }

        internal fun mime(file: File): String = if (file.isDirectory) Document.MIME_TYPE_DIR else
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "application/octet-stream"
    }
}
