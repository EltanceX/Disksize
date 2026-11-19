package com.example.disksize.index

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in only. Every measurement deletes from a new copy, never scan-index.db. */
@RunWith(AndroidJUnit4::class)
class SnapshotDeletePerformanceTest {
    @Test fun historicalCopyDeletion() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("snapshotDelete") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.noBackupFilesDir, "snapshot-delete-input-v1.db")
        check(source.isFile)
        val id = args.getString("snapshotDeleteId")!!
        check(id.matches(Regex("[0-9a-f-]{36}")))
        val report = JSONArray()
        val variants = args.getString("snapshotDeleteVariants", "legacy,production")!!.split(',')
        val repeats = args.getString("snapshotDeleteRepeats", "1")!!.toInt()
        check(repeats in 1..5)
        for (round in 1..repeats) for (variant in variants) {
            check(variant in setOf("legacy", "primary", "cache32-primary", "cache64-primary", "production", "memory", "spill", "spill16", "spill32", "spill64", "spill128"))
            val name = "snapshot-delete-copy-${UUID.randomUUID()}.db"
            val file = File(context.noBackupFilesDir, name)
            source.copyTo(file)
            try {
                IndexStore(context, name).use { store ->
                    val db = store.writableDatabase
                    fun scalar(sql: String) = db.rawQuery(sql, null).use { check(it.moveToFirst()); it.getString(0) }
                    val result = JSONObject().put("variant", variant).put("round", round)
                        .put("sqlite", scalar("SELECT sqlite_version()"))
                    for (key in listOf("secure_delete", "auto_vacuum", "journal_mode", "cache_size", "cache_spill", "soft_heap_limit", "synchronous", "wal_autocheckpoint"))
                        result.put(key, scalar("PRAGMA $key"))
                    val nodes = db.rawQuery("SELECT nodes FROM sessions WHERE id=?", arrayOf(id)).use { check(it.moveToFirst()); it.getLong(0) }
                    result.put("nodes", nodes)
                    val before = scalar("SELECT COUNT(*) FROM snapshots").toLong()
                    val phases = JSONObject()
                    val cpu = JSONObject()
                    val maxRss = java.util.concurrent.atomic.AtomicLong()
                    val maxWal = java.util.concurrent.atomic.AtomicLong()
                    val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
                    val sampler = Thread {
                        while (sampling.get()) {
                            val rss = File("/proc/self/status").useLines { lines -> lines.first { it.startsWith("VmRSS:") }.trim().split(Regex("\\s+"))[1].toLong() }
                            maxRss.updateAndGet { maxOf(it, rss) }
                            maxWal.updateAndGet { maxOf(it, File(file.path + "-wal").length()) }
                            SystemClock.sleep(20)
                        }
                    }
                    fun timed(key: String, block: () -> Unit) {
                        val start = SystemClock.elapsedRealtimeNanos(); val threadStart = android.os.Debug.threadCpuTimeNanos(); block()
                        phases.put(key, (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
                        cpu.put(key, (android.os.Debug.threadCpuTimeNanos() - threadStart) / 1_000_000.0)
                    }
                    if (variant == "memory") db.execSQL("PRAGMA temp_store=MEMORY")
                    if (variant == "spill") db.execSQL("PRAGMA cache_spill=OFF")
                    val plan = JSONArray()
                    db.rawQuery("EXPLAIN QUERY PLAN DELETE FROM aggregates WHERE sid=?", arrayOf(id)).use { while (it.moveToNext()) plan.put(it.getString(3)) }
                    result.put("plan", plan)
                    sampler.start()
                    try {
                        timed("total") {
                            if (variant == "production") {
                                assertEquals("ok", store.snapshots.execute(JSONObject().put("kind", "snapshot-delete").put("snapshotId", id)).getString("status"))
                            } else {
                                db.beginTransactionNonExclusive()
                                try {
                                    if (variant.startsWith("cache")) db.execSQL("PRAGMA cache_size=-${if (variant.startsWith("cache32")) 32768 else 65536}")
                                    if (variant.matches(Regex("spill[0-9]+"))) {
                                        val kib = variant.removePrefix("spill").toInt() * 1024
                                        val pages = kib * 1024 / scalar("PRAGMA page_size").toInt()
                                        db.execSQL("PRAGMA cache_spill=$pages")
                                        // SQLite 3.44 truncates numeric booleans to 8 bits: a multiple
                                        // of 256 sets the threshold but also switches spilling off.
                                        db.execSQL("PRAGMA cache_spill=ON")
                                        assertTrue(scalar("PRAGMA cache_spill").toInt() >= pages)
                                    }
                                    result.put("effectiveCache", scalar("PRAGMA cache_size")).put("effectiveSpill", scalar("PRAGMA cache_spill"))
                                    for ((table, key) in listOf("diff_rows" to "", "diff_cache" to "", "snapshots" to "id",
                                        "issues" to "sid", "hardlinks" to "sid", "aggregates" to "sid", "nodes" to "sid", "sessions" to "id")) {
                                        val hint = if (variant in setOf("primary", "cache32-primary", "cache64-primary") && table in setOf("aggregates", "nodes")) " INDEXED BY sqlite_autoindex_${table}_1" else ""
                                        timed(table) { db.execSQL("DELETE FROM $table$hint" + if (key.isEmpty()) "" else " WHERE $key=?", if (key.isEmpty()) emptyArray() else arrayOf(id)) }
                                    }
                                    db.setTransactionSuccessful()
                                } finally { timed("commit") { db.endTransaction() } }
                            }
                        }
                    } finally { sampling.set(false); sampler.join() }
                    timed("list") { assertEquals("ok", store.snapshots.execute(JSONObject().put("kind", "snapshot-list")).getString("status")) }
                    assertEquals(before - 1, scalar("SELECT COUNT(*) FROM snapshots").toLong())
                    assertEquals("ok", scalar("PRAGMA integrity_check"))
                    db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
                    for (table in listOf("nodes", "aggregates", "issues", "hardlinks")) {
                        db.rawQuery("SELECT COUNT(*) FROM $table WHERE sid=?", arrayOf(id)).use { check(it.moveToFirst()); assertEquals(0, it.getLong(0)) }
                    }
                    result.put("phasesMillis", phases)
                    result.put("cpuMillis", cpu)
                    result.put("peakRssKiB", maxRss.get()).put("peakWalBytes", maxWal.get())
                    db.beginTransactionNonExclusive()
                    try { result.put("writerSpillAfter", scalar("PRAGMA cache_spill")) } finally { db.endTransaction() }
                    report.put(result)
                    File(context.noBackupFilesDir, "snapshot-delete-performance.json").writeText(report.toString(2))
                    println("SNAPSHOT_DELETE $result")
                }
                if (variant == "production" && round == repeats) file.copyTo(File(context.noBackupFilesDir, "snapshot-delete-result-v1.db"), overwrite = true)
            } finally { SQLiteDatabase.deleteDatabase(file) }
        }
    }
}
