package com.example.disksize.index

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Deterministic structural and hardlink fixtures in the replay database only. */
internal class FinalizeReplay(val name: String, private val directories: Int, private val filesPerDir: Int) {
    private val files = directories * filesPerDir
    private val pairStep = when (name) {
        "empty", "deep-wide" -> 0
        "sparse-links" -> 128
        "dense-links" -> 1
        else -> error("Unknown replay fixture")
    }
    private val deep = if (name == "deep-wide") minOf(directories, 480) else 0
    private val pairs = if (pairStep > 0) (files / 2 + pairStep - 1) / pairStep else 0
    private val changes = if (pairStep > 0) (files / 2 + pairStep * 17 - 1) / (pairStep * 17) else 0
    private val fileBytes = if (name == "empty") 0L else 13L
    val issues = changes * 2L
    val logical = (files - pairs) * fileBytes + changes * 4L
    val allocated = (directories + 1) * 4096L + if (fileBytes == 0L) 0L else (files - pairs) * 512L

    fun manifest(): JSONObject {
        val recipe = "finalize-$name-v1:dirs=$directories:filesPerDir=$filesPerDir:chain=$deep:pairStep=$pairStep:changed=17"
        return JSONObject().put("kind", "finalize-$name-v1")
            .put("recipeSha256", MessageDigest.getInstance("SHA-256").digest(recipe.toByteArray()).hex())
            .put("nodes", 1 + directories + files).put("directories", 1 + directories).put("regularFiles", files)
            .put("depth", if (deep > 0) deep + 1 else 2).put("logicalBytes", logical).put("allocatedBytes", allocated)
            .put("sharedIdentities", pairs).put("changedIdentities", changes)
    }

    fun write(writer: IndexStore.Import) {
        val device = ByteBuffer.allocate(8).putLong(1L).array()
        val empty = byteArrayOf()
        var next = 0L
        fun writeNode(parent: Long, name: String, directory: Boolean, depth: Int, ordinal: Int = -1): Long {
            val id = ++next
            val pair = if (pairStep > 0 && !directory) (ordinal % (files / 2)).takeIf { it % pairStep == 0 } else null
            val inode = if (directory || name == "empty") id else directories + files + 2L + (pair ?: ordinal)
            val changed = pair != null && pair % (pairStep * 17) == 0 && ordinal >= files / 2
            writer.node(IndexNode(id, parent, name.toByteArray(), empty, if (directory) 1 else 2, depth,
                if (directory) 0x41ed else 0x81a4, 10000, 10000, device,
                ByteBuffer.allocate(8).putLong(inode).array(), 1, 1,
                if (directory) 0 else if (changed) fileBytes + 4 else fileBytes,
                if (directory) 4096L else if (fileBytes == 0L) 0L else 512L, 1, 0, true, true, 0))
            return id
        }
        val root = writeNode(0, "", true, 0)
        if (deep > 0) {
            var branch = root
            repeat(deep) { d -> branch = writeNode(branch, "d", true, d + 1) }
            // Many direct siblings plus a deep chain exercise both extremes.
            repeat(files) { f -> writeNode(branch, "f$f", false, deep + 1, f) }
            for (node in branch downTo root + 1) writer.directory(node, true, true, 0)
            repeat(directories - deep) { d -> writer.directory(writeNode(root, "e$d", true, 1), true, true, 0) }
        } else {
            repeat(directories) { d ->
                // All later members have a lower raw path than the earlier members.
                val name = if (pairStep == 0) "d$d" else if (d < directories / 2) "z$d" else "a$d"
                val parent = writeNode(root, name, true, 1)
                repeat(filesPerDir) { f -> writeNode(parent, "f$f", false, 2, d * filesPerDir + f) }
                writer.directory(parent, true, true, 0)
            }
        }
        writer.directory(root, true, true, 0)
        writer.flush()
        check(next == files + directories + 1L)
    }

    /** Row-for-row comparison after timing. Keep SQLite cursor windows bounded. */
    fun resultDigest(db: SQLiteDatabase, sid: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(8)
        fun append(value: Long) { digest.update(bytes.putLong(0, value).array()) }
        fun scan(sql: String, columns: Int) {
            var after = 0L
            while (true) {
                val first = after
                db.rawQuery(sql, arrayOf(sid, after.toString())).use {
                    while (it.moveToNext()) {
                        after = it.getLong(0)
                        for (col in 0 until columns) append(it.getLong(col))
                    }
                }
                if (after == first) break
            }
        }
        scan("""SELECT id,owner,own_logical,own_allocated,logical,allocated,complete FROM aggregates
            WHERE sid=? AND id>? ORDER BY id LIMIT 1024""", 7)
        scan("""SELECT id,node,code,errno FROM issues WHERE sid=? AND id>? ORDER BY id LIMIT 1024""", 4)
        return digest.digest().hex()
    }
}
