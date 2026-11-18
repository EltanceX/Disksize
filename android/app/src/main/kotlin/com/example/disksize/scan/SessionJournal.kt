package com.example.disksize.scan

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Single writer in :scanner. The UI accesses snapshots only through Binder. */
internal class SessionJournal(context: Context, filename: String = "scan-session.json") {
    private val file = AtomicFile(File(context.noBackupFilesDir, filename))

    fun read(): JSONObject? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= MAX_BYTES) { "Oversized session journal" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return JSONObject(bytes.toString(Charsets.UTF_8)).also {
            check(it.getInt("schemaVersion") == 1) { "Unsupported session journal" }
        }
    }

    fun write(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES) { "Oversized session snapshot" }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }
    fun clear() { file.delete() }

    companion object { const val MAX_BYTES = 256 * 1024 }
}
