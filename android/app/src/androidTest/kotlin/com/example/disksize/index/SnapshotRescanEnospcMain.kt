package com.example.disksize.index

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.system.Os
import com.example.disksize.BuildConfig
import com.example.disksize.scan.SnapshotRescanContract as Contract
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

/** Standalone app_process in a private mount namespace with a 12 MiB tmpfs. No normal app storage. */
object SnapshotRescanEnospcMain {
    @JvmStatic fun main(args: Array<String>) {
        try { run(args) } catch (error: Throwable) { error.printStackTrace(System.out); kotlin.system.exitProcess(1) }
        kotlin.system.exitProcess(0)
    }

    @Suppress("DEPRECATION")
    private fun run(args: Array<String>) {
        check(BuildConfig.RESCAN_TEST_HOST && !BuildConfig.SCAN_BENCHMARK)
        val directory = File(args[0]).canonicalFile
        check(directory.path.matches(Regex("/data/local/tmp/rescan-enospc-[0-9a-f]{32}/fs")))
        check(directory.usableSpace <= 12 * 1024 * 1024) { "Refuse to fill an unbounded filesystem" }
        android.os.Looper.prepareMainLooper()
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("systemMain").invoke(null)
        val system = threadClass.getMethod("getSystemContext").invoke(thread) as Context
        val app = system.createPackageContext("com.example.disksize", 0)
        val isolated = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
        val uid = args[1].toInt(); check(uid >= 10000)
        val helper = IndexStore(isolated, "enospc.db")
        Os.setgid(uid); Os.setuid(uid); check(Process.myUid() == uid)
        val reserve = File(directory, "reserve")
        helper.use { store ->
            val snapshot = UUID.randomUUID().toString()
            fun bytes(n: Long) = ByteBuffer.allocate(8).putLong(n).array()
            fun node(writer: IndexConsumer, id: Long, parent: Long, name: String, kind: Int) {
                writer.node(IndexNode(id, parent, name.toByteArray(), byteArrayOf(), kind, if (id == 1L) 0 else if (parent == 1L) 1 else 2,
                    if (kind == 1) 16832 else 33152, uid.toLong(), uid.toLong(), bytes(1), bytes(id), 1, 1, 0, 4096, 1, 0, true, true, 0))
                if (kind == 1) writer.directory(id, true, true, 0)
            }
            store.begin(snapshot, directory.path, "ordinary", uid, Process.myPid()).use { w ->
                node(w, 1, 0, "", 1); node(w, 2, 1, "scope", 1); node(w, 3, 2, "old", 2)
                w.flush(IndexStore.FlushReason.SUMMARY)
            }
            store.finalize(snapshot, StreamSummary("completed", 3, 0, 0, 1, 0, 1, 1)) { false }
            fun signature() = store.readableDatabase.rawQuery("SELECT revision,nodes,node_id_high_water FROM sessions WHERE id=?", arrayOf(snapshot)).use {
                check(it.moveToFirst()); "${it.getLong(0)}:${it.getLong(1)}:${it.getLong(2)}"
            }
            val before = signature()
            val revision = before.substringBefore(':').toLong()
            val job = UUID.randomUUID().toString()
            store.rescans.create(job, Contract.Request(snapshot, 2, revision))
            store.rescans.beginStaging(job).use { w ->
                node(w, 1, 0, "", 1)
                for (id in 2L..5001L) node(w, id, 1, "new-$id-${"x".repeat(120)}", 2)
                w.finish(StreamSummary("completed", 5001, 0, 0, 1, 0, 1, 1))
            }
            store.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).close()
            var full = false
            try {
                FileOutputStream(reserve).use { output ->
                    val chunk = ByteArray(4096)
                    while (true) output.write(chunk)
                }
            } catch (error: IOException) {
                check(error.message.orEmpty().contains("ENOSPC") || error.message.orEmpty().contains("No space left")) { error.toString() }
                full = true
            }
            check(full)
            java.io.RandomAccessFile(reserve, "rw").use { it.setLength(maxOf(0L, it.length() - 65536)) }
            var rejected = false
            try { store.rescans.apply(job) } catch (error: SnapshotRescanStore.Rejected) {
                if (error.reason != Contract.Error.STORAGE_ERROR) error.printStackTrace(System.out)
                check(error.reason == Contract.Error.STORAGE_ERROR) { error.toString() }; rejected = true
            }
            check(rejected) { "Replacement unexpectedly fit in the bounded filesystem" }
            check(reserve.delete())
            check(before == signature())
            store.close(); store.recover()
            check(before == signature())
            val state = store.rescans.get(job)!!
            check(state.committedRevision == null && state.stagingCleaned)
            check(store.readableDatabase.rawQuery("SELECT hex(name) FROM nodes WHERE sid=? AND id=3", arrayOf(snapshot)).use {
                it.moveToFirst() && it.getString(0) == "6F6C64"
            })
            check(store.readableDatabase.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" })
            println("RESCAN_ENOSPC " + JSONObject().put("passed", true).put("filesystemLimitBytes", 12 * 1024 * 1024)
                .put("uid", Process.myUid()).put("before", before).put("after", signature()).put("state", state.state.wireName))
        }
    }
}
