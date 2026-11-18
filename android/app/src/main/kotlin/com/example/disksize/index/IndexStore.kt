package com.example.disksize.index

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.os.CancellationSignal
import com.example.disksize.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException

/** Owned by the ordinary scanner process. Import/finalize are confined to its single worker. */
internal class IndexStore(context: Context, filename: String = "scan-index.db", private val policy: ImportPolicy = ImportPolicy.DEFAULT) : SQLiteOpenHelper(
    context, File(context.noBackupFilesDir, filename).absolutePath, null, SCHEMA_VERSION) {
    private val scanIdentity = JSONObject().put("rules", "native-v3:path-min-hardlink:blocks512:no-follow:one-mount:2000000:512")
        .put("user", android.os.Process.myUid() / 100000)
        .put("boot", try { File("/proc/sys/kernel/random/boot_id").readText().trim() } catch (_: Exception) {
            android.provider.Settings.Global.getString(context.contentResolver, "boot_count") ?: ""
        }).toString()
    private val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
    private fun identity(root: String) = JSONObject(scanIdentity).put("volumeUuid", try {
        storage.getUuidForPath(File(root)).toString()
    } catch (_: Exception) { "" }).toString()
    val snapshots by lazy { SnapshotQueries(this) }
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA temp_store=FILE")
        db.execSQL("PRAGMA cache_size=-8192")
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sessions(id TEXT PRIMARY KEY, root TEXT NOT NULL, backend TEXT NOT NULL,
            state TEXT NOT NULL, phase TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0,
            provisional INTEGER NOT NULL DEFAULT 1, started INTEGER NOT NULL, ended INTEGER NOT NULL DEFAULT 0,
            nodes INTEGER NOT NULL DEFAULT 0, issues INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '',
            scanner_uid INTEGER NOT NULL, scanner_pid INTEGER NOT NULL, metrics TEXT NOT NULL DEFAULT '{}',
            volume TEXT NOT NULL DEFAULT '{}', identity TEXT NOT NULL DEFAULT '{}')""")
        db.execSQL("CREATE INDEX sessions_recent ON sessions(started DESC,id DESC)")
        db.execSQL("""CREATE TABLE nodes(sid TEXT NOT NULL REFERENCES sessions(id), id INTEGER NOT NULL,
            parent INTEGER NOT NULL, depth INTEGER NOT NULL, name BLOB NOT NULL, name_key TEXT NOT NULL,
            path BLOB NOT NULL, link BLOB NOT NULL, kind INTEGER NOT NULL, mode INTEGER NOT NULL,
            uid INTEGER NOT NULL, gid INTEGER NOT NULL, device BLOB NOT NULL, inode BLOB NOT NULL,
            mount INTEGER NOT NULL, nlink INTEGER NOT NULL, size INTEGER NOT NULL, allocated INTEGER NOT NULL,
            seconds INTEGER NOT NULL, nanos INTEGER NOT NULL, valid INTEGER NOT NULL, complete INTEGER NOT NULL,
            enumerated INTEGER NOT NULL DEFAULT 0, skip INTEGER NOT NULL, PRIMARY KEY(sid,id)) WITHOUT ROWID""")
        db.execSQL("CREATE UNIQUE INDEX node_path ON nodes(sid,path)")
        if (!BuildConfig.SCAN_BENCHMARK || policy.keepNodeParentIndex) db.execSQL("CREATE INDEX node_parent ON nodes(sid,parent,id)")
        db.execSQL("CREATE INDEX node_identity ON nodes(sid,device,inode,path)")
        db.execSQL("""CREATE TABLE aggregates(sid TEXT NOT NULL REFERENCES sessions(id), id INTEGER NOT NULL,
            parent INTEGER NOT NULL, depth INTEGER NOT NULL, kind INTEGER NOT NULL, name_key TEXT NOT NULL,
            owner INTEGER NOT NULL, own_logical INTEGER NOT NULL, own_allocated INTEGER NOT NULL,
            logical INTEGER NOT NULL, allocated INTEGER NOT NULL, complete INTEGER NOT NULL,
            PRIMARY KEY(sid,id)) WITHOUT ROWID""")
        db.execSQL("CREATE INDEX aggregate_parent ON aggregates(sid,parent,id)")
        db.execSQL("CREATE INDEX aggregate_incomplete ON aggregates(sid,parent,complete)")
        db.execSQL("CREATE INDEX aggregate_depth ON aggregates(sid,depth,id)")
        db.execSQL("CREATE INDEX children_logical ON aggregates(sid,parent,logical DESC,id DESC)")
        db.execSQL("CREATE INDEX children_allocated ON aggregates(sid,parent,allocated DESC,id DESC)")
        db.execSQL("CREATE INDEX children_name ON aggregates(sid,parent,name_key,id)")
        db.execSQL("CREATE INDEX large_logical ON aggregates(sid,kind,logical DESC,id DESC)")
        db.execSQL("CREATE INDEX large_allocated ON aggregates(sid,kind,allocated DESC,id DESC)")
        db.execSQL("""CREATE TABLE hardlinks(sid TEXT NOT NULL, device BLOB NOT NULL, inode BLOB NOT NULL,
            path BLOB NOT NULL, changed INTEGER NOT NULL, PRIMARY KEY(sid,device,inode)) WITHOUT ROWID""")
        db.execSQL("""CREATE TABLE issues(id INTEGER PRIMARY KEY AUTOINCREMENT, sid TEXT NOT NULL REFERENCES sessions(id),
            node INTEGER NOT NULL, code INTEGER NOT NULL, errno INTEGER NOT NULL, operation TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX issue_session ON issues(sid,id)")
        db.execSQL("CREATE INDEX issue_node ON issues(sid,node,id)")
        db.execSQL("""CREATE TABLE snapshots(id TEXT PRIMARY KEY, sid TEXT NOT NULL UNIQUE REFERENCES sessions(id),
            revision INTEGER NOT NULL, created INTEGER NOT NULL, logical INTEGER NOT NULL, allocated INTEGER NOT NULL,
            complete INTEGER NOT NULL, retained INTEGER NOT NULL DEFAULT 0)""")
        createDiffSchema(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..3 && newVersion == SCHEMA_VERSION) {
            "Unsupported index migration $oldVersion -> $newVersion; existing data preserved"
        }
        if (oldVersion == 1) {
            db.execSQL("CREATE INDEX aggregate_incomplete ON aggregates(sid,parent,complete)")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN volume TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("CREATE INDEX sessions_recent ON sessions(started DESC,id DESC)")
        }
        db.execSQL("ALTER TABLE sessions ADD COLUMN identity TEXT NOT NULL DEFAULT '{}'")
        db.execSQL("ALTER TABLE snapshots ADD COLUMN retained INTEGER NOT NULL DEFAULT 0")
        createDiffSchema(db)
    }
    private fun createDiffSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE diff_cache(id INTEGER PRIMARY KEY CHECK(id=1), signature TEXT NOT NULL, ready INTEGER NOT NULL)")
        db.execSQL("""CREATE TABLE diff_rows(path BLOB PRIMARY KEY, old_kind INTEGER NOT NULL, new_kind INTEGER NOT NULL,
            old_logical INTEGER, new_logical INTEGER, old_allocated INTEGER, new_allocated INTEGER,
            status TEXT NOT NULL, logical_delta INTEGER, allocated_delta INTEGER, row_id INTEGER NOT NULL UNIQUE) WITHOUT ROWID""")
        db.execSQL("CREATE INDEX diff_logical ON diff_rows(COALESCE(logical_delta,-9223372036854775808) DESC,path DESC)")
        db.execSQL("CREATE INDEX diff_allocated ON diff_rows(COALESCE(allocated_delta,-9223372036854775808) DESC,path DESC)")
        db.execSQL("CREATE INDEX diff_status ON diff_rows(status,path)")
    }
    companion object { const val SCHEMA_VERSION = 4 }
    fun recover() {
        writableDatabase.execSQL("""UPDATE sessions SET state='interrupted', phase='interrupted',
            reason='process-interrupted', revision=revision+1, ended=? WHERE state IN ('running','finalizing')""", arrayOf(now()))
    }
    fun begin(id: String, root: String, backend: String, uid: Int, pid: Int): Import {
        writableDatabase.execSQL("INSERT INTO sessions(id,root,backend,state,phase,started,scanner_uid,scanner_pid,identity) VALUES(?,?,?,'running','scanning',?,?,?,?)",
            arrayOf<Any>(id, root, backend, now(), uid, pid, identity(root)))
        return Import(id)
    }
    enum class FlushReason { LIMIT, PROGRESS, SUMMARY, INTERVAL }

    inner class Import internal constructor(val id: String) : AutoCloseable, IndexConsumer {
        private val db = writableDatabase
        private val insert = db.compileStatement("""INSERT INTO nodes(sid,id,parent,depth,name,name_key,path,link,kind,mode,uid,gid,
            device,inode,mount,nlink,size,allocated,seconds,nanos,valid,complete,skip) VALUES(${List(23) { "?" }.joinToString(",")})""")
        private val insertAggregate = db.compileStatement("INSERT INTO aggregates VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
        private var actions = 0; private var transaction = false
        private var openedAt = 0L
        private var maxActiveNanos = 0L
        private var deferredProgress = 0L
        private var discovered = 0L; private var issueCount = 0L
        override var writeNanos = 0L
            private set
        private var volumeNanos = 0L
        private var commitNanos = 0L
        private var committedActions = 0L
        private var maxActions = 0
        private val commits = LongArray(FlushReason.entries.size)
        private var parentCacheHits = 0L
        private var parentCacheMisses = 0L
        override val measuredImportNanos get() = writeNanos + volumeNanos
        // At most 32 recent parent paths, with a 1 MiB byte cap (deep trees can have long paths).
        private val paths = LinkedHashMap<Long, Pair<ByteArray, Int>>(32, 0.75f, true)
        private var pathBytes = 0
        private fun cached(id: Long, path: ByteArray, depth: Int) {
            paths.put(id, path to depth)?.let { pathBytes -= it.first.size }; pathBytes += path.size
            while (paths.size > 32 || pathBytes > 1_048_576) {
                val first = paths.entries.iterator(); pathBytes -= first.next().value.first.size; first.remove()
            }
        }
        private fun action(before: Long) {
            if (!transaction) { db.beginTransactionNonExclusive(); transaction = true; openedAt = before }
            actions++
        }
        private fun finishAction(before: Long, volume: Boolean = false) {
            val after = System.nanoTime()
            if (volume) volumeNanos += after - before else writeNanos += after - before
            if (actions >= policy.actionLimit) flush(FlushReason.LIMIT)
            else if (policy.maxActiveNanos > 0L && after - openedAt >= policy.maxActiveNanos) flush(FlushReason.INTERVAL)
        }
        override fun node(n: IndexNode) {
            val before = System.nanoTime()
            action(before)
            val ancestor = if (n.parent == 0L) byteArrayOf() to -1 else {
                val parent = paths[n.parent]
                if (parent != null) {
                    parentCacheHits++
                    parent
                } else {
                    parentCacheMisses++
                    db.rawQuery("SELECT path,depth,kind FROM nodes WHERE sid=? AND id=?", arrayOf(id, n.parent.toString())).use {
                        require(it.moveToFirst() && it.getInt(2) == 1) { "Missing/non-directory parent" }
                        it.getBlob(0) to it.getInt(1)
                    }
                }
            }
            require(n.depth == ancestor.second + 1)
            val prefix = ancestor.first
            val typed = policy.typedBindings
            val path = when {
                prefix.isEmpty() -> n.name
                !typed -> prefix + byteArrayOf(47) + n.name
                else -> ByteArray(prefix.size + 1 + n.name.size).also {
                    prefix.copyInto(it)
                    it[prefix.size] = 47
                    n.name.copyInto(it, prefix.size + 1)
                }
            }
            require(path.size <= 512 * 256)
            val logical = if (n.valid && n.skip == 0 && n.kind == 2) n.size else 0L
            val allocated = if (n.valid && n.skip == 0) n.allocated else 0L
            val nameKey = n.name.hex()
            if (typed) {
                val complete = if (n.complete) 1L else 0L
                insert.apply {
                    bindString(1, id); bindLong(2, n.id); bindLong(3, n.parent); bindLong(4, n.depth.toLong())
                    bindBlob(5, n.name); bindString(6, nameKey); bindBlob(7, path); bindBlob(8, n.link)
                    bindLong(9, n.kind.toLong()); bindLong(10, n.mode); bindLong(11, n.uid); bindLong(12, n.gid)
                    bindBlob(13, n.device); bindBlob(14, n.inode); bindLong(15, n.mount); bindLong(16, n.links)
                    bindLong(17, n.size); bindLong(18, n.allocated); bindLong(19, n.seconds); bindLong(20, n.nanos)
                    bindLong(21, if (n.valid) 1L else 0L); bindLong(22, complete); bindLong(23, n.skip.toLong())
                }
                insert.executeInsert()
                insertAggregate.apply {
                    bindString(1, id); bindLong(2, n.id); bindLong(3, n.parent); bindLong(4, n.depth.toLong())
                    bindLong(5, n.kind.toLong()); bindString(6, nameKey); bindLong(7, n.id)
                    bindLong(8, logical); bindLong(9, allocated); bindLong(10, logical); bindLong(11, allocated)
                    bindLong(12, complete)
                }
                insertAggregate.executeInsert()
            } else {
                insert.bindAll(listOf(id, n.id, n.parent, n.depth, n.name, nameKey, path, n.link, n.kind, n.mode, n.uid, n.gid,
                    n.device, n.inode, n.mount, n.links, n.size, n.allocated, n.seconds, n.nanos, n.valid, n.complete, n.skip))
                insert.executeInsert()
                insertAggregate.bindAll(listOf(id, n.id, n.parent, n.depth, n.kind, n.name.hex(), n.id, logical, allocated,
                    logical, allocated, n.complete))
                insertAggregate.executeInsert()
            }
            if (n.kind == 1) cached(n.id, path, n.depth)
            discovered++
            finishAction(before)
        }
        override fun directory(node: Long, enumerated: Boolean, complete: Boolean, skip: Int) {
            val before = System.nanoTime()
            action(before)
            db.execSQL("UPDATE nodes SET enumerated=?,complete=?,skip=? WHERE sid=? AND id=? AND kind=1",
                arrayOf<Any>(if (enumerated) 1 else 0, if (complete) 1 else 0, skip, id, node))
            db.execSQL("UPDATE aggregates SET complete=? WHERE sid=? AND id=?", arrayOf<Any>(if (complete) 1 else 0, id, node))
            if (skip != 0) db.execSQL("UPDATE aggregates SET own_logical=0,own_allocated=0,logical=0,allocated=0 WHERE sid=? AND id=?",
                arrayOf<Any>(id, node))
            finishAction(before)
        }
        override fun issue(node: Long, code: Int, errno: Int, op: String) {
            val before = System.nanoTime()
            action(before); issueCount++
            db.execSQL("INSERT INTO issues(sid,node,code,errno,operation) VALUES(?,?,?,?,?)", arrayOf<Any>(id, node, code, errno, op))
            db.execSQL("UPDATE nodes SET complete=0 WHERE sid=? AND id=?", arrayOf<Any>(id, node))
            db.execSQL("UPDATE aggregates SET complete=0 WHERE sid=? AND id=?", arrayOf<Any>(id, node))
            finishAction(before)
        }
        override fun volume(total: Long, free: Long, available: Long, captured: Long) {
            val before = System.nanoTime()
            action(before)
            val value = JSONObject().put("totalBytes", total).put("freeBytes", free)
                .put("availableBytes", available).put("capturedAtMillis", captured)
            db.execSQL("UPDATE sessions SET volume=? WHERE id=?", arrayOf(value.toString(), id))
            finishAction(before, volume = true)
        }
        fun flush() = flush(FlushReason.SUMMARY)

        override fun flush(reason: FlushReason) {
            if (!transaction) return
            val before = System.nanoTime()
            val activeNanos = before - openedAt
            if (reason == FlushReason.PROGRESS && policy.maxActiveNanos > 0L && activeNanos < policy.maxActiveNanos) {
                deferredProgress++
                return
            }
            val batch = actions
            try {
                db.execSQL("UPDATE sessions SET revision=revision+1,nodes=?,issues=? WHERE id=?", arrayOf<Any>(discovered, issueCount, id))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction(); transaction = false; actions = 0
                val elapsed = System.nanoTime() - before
                writeNanos += elapsed
                commitNanos += elapsed
            }
            commits[reason.ordinal]++
            committedActions += batch
            maxActions = maxOf(maxActions, batch)
            maxActiveNanos = maxOf(maxActiveNanos, activeNanos)
        }
        override fun measurements() = JSONObject().put("importMicros", measuredImportNanos / 1000)
            .put("importCommitMicros", commitNanos / 1000)
            .put("transactions", commits.sum()).put("committedActions", committedActions).put("maxActionsPerTransaction", maxActions)
            .put("limitCommits", commits[FlushReason.LIMIT.ordinal]).put("progressCommits", commits[FlushReason.PROGRESS.ordinal])
            .put("summaryCommits", commits[FlushReason.SUMMARY.ordinal])
            .put("intervalCommits", commits[FlushReason.INTERVAL.ordinal]).put("deferredProgressFlushes", deferredProgress)
            .put("maxActiveTransactionMicros", maxActiveNanos / 1000).put("importVariant", policy.name)
            .put("parentCacheHits", parentCacheHits).put("parentCacheMisses", parentCacheMisses)
        override fun close() {
            if (transaction) { db.endTransaction(); transaction = false }
            insert.close(); insertAggregate.close(); paths.clear()
        }
    }
    private fun transaction(id: String, operation: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase; db.beginTransactionNonExclusive()
        try { operation(db); db.execSQL("UPDATE sessions SET revision=revision+1 WHERE id=?", arrayOf(id)); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }
    fun interrupt(id: String, state: String, reason: String) {
        transaction(id) { db ->
            db.execSQL("DELETE FROM snapshots WHERE sid=?", arrayOf(id))
            db.execSQL("UPDATE sessions SET state=?,phase=?,reason=?,ended=?,provisional=1 WHERE id=?",
                arrayOf<Any>(state, state, reason, now(), id))
        }
    }
    fun finalize(id: String, summary: StreamSummary, signal: CancellationSignal = CancellationSignal(), cancelled: () -> Boolean): JSONObject {
        val start = android.os.SystemClock.elapsedRealtime()
        val startNanos = System.nanoTime()
        val db = writableDatabase
        db.rawQuery("SELECT 1 FROM snapshots WHERE sid=?", arrayOf(id)).use { check(!it.moveToFirst()) { "Snapshot is immutable" } }
        fun check() { if (cancelled()) throw CancellationException("Index finalization cancelled") }
        check()
        transaction(id) { it.execSQL("UPDATE sessions SET state='finalizing',phase='deduplicating' WHERE id=?", arrayOf(id)) }
        // Page groups too: Android CursorWindow refills can otherwise re-run an
        // unbounded GROUP BY from its start for each window, causing quadratic work.
        var lastDevice = ""; var lastInode = ""
        db.compileStatement("INSERT INTO hardlinks VALUES(?,?,?,?,?)").use { statement ->
            while (true) {
                check()
                // These are hex encodings of two validated 8-byte DB identities.
                // rawQuery only accepts string bindings, so use typed BLOB literals.
                val after = if (lastDevice.isEmpty()) "" else " AND (device,inode)>(X'$lastDevice',X'$lastInode')"
                var rows = 0
                transaction(id) {
                    db.rawQuery("""SELECT device,inode,MIN(path),
                        (MIN(size)<>MAX(size) OR MIN(allocated)<>MAX(allocated) OR MIN(seconds)<>MAX(seconds) OR MIN(nanos)<>MAX(nanos))
                        FROM nodes WHERE sid=? AND valid=1 AND skip=0 AND kind<>1$after
                        GROUP BY device,inode ORDER BY device,inode LIMIT 1024""", arrayOf(id), signal).use { groups ->
                        while (groups.moveToNext()) {
                            check()
                            val device = groups.getBlob(0); val inode = groups.getBlob(1)
                            statement.bindAll(listOf(id, device, inode, groups.getBlob(2), groups.getInt(3)))
                            statement.executeInsert(); rows++; lastDevice = device.hex(); lastInode = inode.hex()
                        }
                    }
                }
                if (rows < 1024) break
            }
        }
        transaction(id) { it.execSQL("UPDATE sessions SET phase='aggregating' WHERE id=?", arrayOf(id)) }
        val dedupMillis = android.os.SystemClock.elapsedRealtime() - start
        val initializeStart = System.nanoTime()
        var from = 1L
        while (from <= summary.nodes) {
            check(); val to = from + 4095
            transaction(id) {
                it.execSQL("""UPDATE aggregates SET
                    owner=COALESCE((SELECT o.id FROM nodes n JOIN hardlinks h ON h.sid=n.sid AND h.device=n.device AND h.inode=n.inode
                        JOIN nodes o ON o.sid=h.sid AND o.path=h.path WHERE n.sid=aggregates.sid AND n.id=aggregates.id),id),
                    complete=complete AND NOT EXISTS(SELECT 1 FROM nodes n JOIN hardlinks h ON h.sid=n.sid AND h.device=n.device AND h.inode=n.inode
                        WHERE n.sid=aggregates.sid AND n.id=aggregates.id AND h.changed=1)
                    WHERE sid=? AND id BETWEEN ? AND ?""", arrayOf<Any>(id, from, to))
                it.execSQL("UPDATE aggregates SET own_logical=0,own_allocated=0,logical=0,allocated=0 WHERE sid=? AND id BETWEEN ? AND ? AND kind<>1 AND owner<>id",
                    arrayOf<Any>(id, from, to))
                it.execSQL("""INSERT INTO issues(sid,node,code,errno,operation) SELECT n.sid,n.id,6,116,'hardlink-metadata-changed'
                    FROM nodes n JOIN hardlinks h ON h.sid=n.sid AND h.device=n.device AND h.inode=n.inode
                    WHERE n.sid=? AND n.id BETWEEN ? AND ? AND n.kind<>1 AND h.changed=1""", arrayOf<Any>(id, from, to))
            }
            from = to + 1
        }
        val initializeMillis = android.os.SystemClock.elapsedRealtime() - start - dedupMillis
        val aggregateStart = System.nanoTime()
        val depth = db.rawQuery("SELECT COALESCE(MAX(depth),0) FROM nodes WHERE sid=?", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) }
        for (level in depth downTo 0) {
            var after = 0L
            while (true) {
                check()
                val ids = db.rawQuery("SELECT id FROM aggregates WHERE sid=? AND depth=? AND id>? ORDER BY id LIMIT 1024",
                    arrayOf(id, level.toString(), after.toString())).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
                if (ids.isEmpty()) break
                val last = ids.last()
                transaction(id) {
                    it.execSQL("""UPDATE aggregates SET
                        logical=own_logical+COALESCE((SELECT SUM(c.logical) FROM aggregates c WHERE c.sid=aggregates.sid AND c.parent=aggregates.id),0),
                        allocated=own_allocated+COALESCE((SELECT SUM(c.allocated) FROM aggregates c WHERE c.sid=aggregates.sid AND c.parent=aggregates.id),0),
                        complete=complete AND NOT EXISTS(SELECT 1 FROM aggregates c WHERE c.sid=aggregates.sid AND c.parent=aggregates.id AND c.complete=0)
                        WHERE sid=? AND depth=? AND id>? AND id<=?""", arrayOf<Any>(id, level, after, last))
                it.rawQuery("SELECT 1 FROM aggregates WHERE sid=? AND depth=? AND id>? AND id<=? AND (typeof(logical)<>'integer' OR typeof(allocated)<>'integer') LIMIT 1",
                    arrayOf(id, level.toString(), after.toString(), last.toString())).use { overflow -> kotlin.check(!overflow.moveToFirst()) { "Aggregate counter overflow" } }
                }
                after = last
            }
        }
        check()
        val complete = db.rawQuery("SELECT complete FROM aggregates WHERE sid=? AND id=1", arrayOf(id)).use { it.moveToFirst() && it.getInt(0) == 1 }
        val state = if (summary.state == "completed" && !complete) "partial" else summary.state
        val aggregateEnd = System.nanoTime()
        val metrics = JSONObject().put("scanMicros", summary.elapsed).put("finalizeMillis", android.os.SystemClock.elapsedRealtime() - start)
            .put("importDbMicros", summary.importWriteMicros).put("dedupMillis", dedupMillis).put("initializeMillis", initializeMillis)
            .put("peakOpenFds", summary.peakFds).put("openFdsAtFinish", summary.finishFds)
            .put("rootMountId", summary.mount).put("rootFilesystemType", summary.filesystem)
        val finalize = summary.performance?.let {
            JSONObject().put("dedupMicros", (initializeStart - startNanos) / 1000)
                .put("initializeMicros", (aggregateStart - initializeStart) / 1000)
                .put("aggregateMicros", (aggregateEnd - aggregateStart) / 1000)
                .also { timings -> metrics.put("performance", it.put("finalize", timings)) }
        }
        val commitStart = System.nanoTime()
        transaction(id) {
            it.execSQL("""UPDATE sessions SET state=?,phase='final',provisional=0,ended=?,metrics=?,
                issues=(SELECT COUNT(*) FROM issues WHERE sid=?) WHERE id=?""", arrayOf<Any>(state, now(), metrics.toString(), id, id))
            if (state in setOf("completed", "partial")) it.execSQL("""INSERT INTO snapshots(id,sid,revision,created,logical,allocated,complete)
                SELECT id,id,revision+1,?,COALESCE((SELECT logical FROM aggregates WHERE sid=? AND id=1),0),
                COALESCE((SELECT allocated FROM aggregates WHERE sid=? AND id=1),0),? FROM sessions WHERE id=?""",
                arrayOf<Any>(now(), id, id, if (complete) 1 else 0, id))
        }
        val committed = System.nanoTime()
        val result = preview(id)
        // Only the terminal journal can carry timings measured after this transaction.
        if (finalize != null) {
            result.getJSONObject("performance").getJSONObject("finalize")
                .put("commitMicros", (committed - commitStart) / 1000)
                .put("previewMicros", (System.nanoTime() - committed) / 1000)
        }
        return result
    }
    fun preview(id: String): JSONObject {
        val db = readableDatabase
        val value = db.rawQuery("SELECT state,nodes,issues,provisional,metrics FROM sessions WHERE id=?", arrayOf(id)).use {
            require(it.moveToFirst()); JSONObject(it.getString(4)).put("state", it.getString(0)).put("nodeCount", it.getLong(1))
                .put("issueCount", it.getLong(2)).put("provisional", it.getInt(3) == 1)
        }
        db.rawQuery("SELECT logical,allocated,complete FROM aggregates WHERE sid=? AND id=1", arrayOf(id)).use {
            val exists = it.moveToFirst()
            value.put("logicalBytes", if (exists) it.getLong(0) else 0).put("allocatedBytes", if (exists) it.getLong(1) else 0)
                .put("complete", exists && it.getInt(2) == 1 && value.getString("state") == "completed")
        }
        val entries = JSONArray(); var budget = 0
        db.rawQuery("SELECT n.path,n.kind,a.logical,a.allocated,a.complete,n.skip FROM nodes n JOIN aggregates a ON a.sid=n.sid AND a.id=n.id WHERE n.sid=? ORDER BY n.id LIMIT 64", arrayOf(id)).use {
            while (it.moveToNext()) {
                val path = it.getBlob(0); if (path.size > 4096 || budget + path.size * 2 > 65536) continue
                budget += path.size * 2
                entries.put(JSONObject().put("pathHex", path.hex()).put("kind", kinds[it.getInt(1)])
                    .put("logicalBytes", it.getLong(2)).put("allocatedBytes", it.getLong(3)).put("complete", it.getInt(4) == 1)
                    .put("skipped", skips[it.getInt(5)]))
            }
        }
        val issues = JSONArray(); budget = 0
        db.rawQuery("SELECT n.path,i.code,i.errno,i.operation FROM issues i LEFT JOIN nodes n ON n.sid=i.sid AND n.id=i.node WHERE i.sid=? ORDER BY i.id LIMIT 64", arrayOf(id)).use {
            while (it.moveToNext()) {
                val path = if (it.isNull(0)) byteArrayOf() else it.getBlob(0)
                if (path.size > 4096 || budget + path.size * 2 > 65536) continue
                budget += path.size * 2
                issues.put(JSONObject().put("pathHex", path.hex()).put("code", errors[it.getInt(1)])
                    .put("systemError", it.getInt(2)).put("operation", it.getString(3)))
            }
        }
        return value.put("entries", entries).put("issues", issues)
            .put("previewTruncated", value.getLong("nodeCount") > entries.length() || value.getLong("issueCount") > issues.length())
    }
    private fun now() = System.currentTimeMillis()
}

internal fun SQLiteStatement.bindAll(values: List<Any>) {
    clearBindings()
    values.forEachIndexed { index, value -> when (value) {
        is ByteArray -> bindBlob(index + 1, value)
        is String -> bindString(index + 1, value)
        is Boolean -> bindLong(index + 1, if (value) 1 else 0)
        is Number -> bindLong(index + 1, value.toLong())
        else -> error("Unsupported binding")
    } }
}
