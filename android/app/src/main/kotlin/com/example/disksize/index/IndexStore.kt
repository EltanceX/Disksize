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
internal class IndexStore(context: Context, filename: String = "scan-index.db", private val policy: ImportPolicy = ImportPolicy.DEFAULT,
    private val finalizePolicy: FinalizePolicy = FinalizePolicy.DEFAULT) : SQLiteOpenHelper(
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
    internal fun currentIdentity(root: String) = JSONObject(identity(root))
    val snapshots by lazy { SnapshotQueries(this) }
    val rescans by lazy { SnapshotRescanStore(this) }
    internal val snapshotMutationLock = java.util.concurrent.locks.ReentrantLock()
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
            volume TEXT NOT NULL DEFAULT '{}', identity TEXT NOT NULL DEFAULT '{}',
            node_id_high_water INTEGER NOT NULL DEFAULT 0 CHECK(node_id_high_water>=0))""")
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
            complete INTEGER NOT NULL, retained INTEGER NOT NULL DEFAULT 0,
            local_updated INTEGER NOT NULL DEFAULT 0, local_updates INTEGER NOT NULL DEFAULT 0)""")
        createDiffSchema(db)
        SnapshotRescanStore.createSchema(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1 until SCHEMA_VERSION && newVersion == SCHEMA_VERSION) {
            "Unsupported index migration $oldVersion -> $newVersion; existing data preserved"
        }
        if (oldVersion == 1) {
            db.execSQL("CREATE INDEX aggregate_incomplete ON aggregates(sid,parent,complete)")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN volume TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("CREATE INDEX sessions_recent ON sessions(started DESC,id DESC)")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN identity TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("ALTER TABLE snapshots ADD COLUMN retained INTEGER NOT NULL DEFAULT 0")
            createDiffSchema(db)
        }
        db.execSQL("ALTER TABLE sessions ADD COLUMN node_id_high_water INTEGER NOT NULL DEFAULT 0 CHECK(node_id_high_water>=0)")
        db.execSQL("UPDATE sessions SET node_id_high_water=COALESCE((SELECT MAX(id) FROM nodes WHERE sid=sessions.id),0)")
        db.execSQL("ALTER TABLE snapshots ADD COLUMN local_updated INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE snapshots ADD COLUMN local_updates INTEGER NOT NULL DEFAULT 0")
        SnapshotRescanStore.createSchema(db)
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
    companion object { const val SCHEMA_VERSION = 5 }
    fun recover() {
        writableDatabase.execSQL("""UPDATE sessions SET state='interrupted', phase='interrupted',
            reason='process-interrupted', revision=revision+1, ended=? WHERE state IN ('running','finalizing')""", arrayOf(now()))
        rescans.recover()
    }
    fun begin(id: String, root: String, backend: String, uid: Int, pid: Int): Import {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            check(!rescans.hasActive(db)) { "A snapshot rescan is active" }
            db.execSQL("INSERT INTO sessions(id,root,backend,state,phase,started,scanner_uid,scanner_pid,identity) VALUES(?,?,?,'running','scanning',?,?,?,?)",
                arrayOf<Any>(id, root, backend, now(), uid, pid, identity(root)))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
        private var maxNodeId = 0L
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
            maxNodeId = maxOf(maxNodeId, n.id)
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
                db.execSQL("UPDATE sessions SET revision=revision+1,nodes=?,issues=?,node_id_high_water=MAX(node_id_high_water,?) WHERE id=?",
                    arrayOf<Any>(discovered, issueCount, maxNodeId, id))
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
    private fun collectAllOwners(db: SQLiteDatabase, id: String, signal: CancellationSignal, check: () -> Unit,
        batch: ((SQLiteDatabase) -> Unit) -> Unit = { transaction(id, it) }) {
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
                batch {
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
    }

    private fun collectSharedOwners(db: SQLiteDatabase, id: String, signal: CancellationSignal, check: () -> Unit,
        batch: ((SQLiteDatabase) -> Unit) -> Unit = { transaction(id, it) }) {
        // Count all aliases, but elect and compare only eligible members.
        // A singleton would leave its preinitialized owner and totals intact.
        val eligible = "valid=1 AND skip=0 AND kind<>1"
        val path = "MIN(CASE WHEN $eligible THEN path END)"
        val conflict = listOf("size", "allocated", "seconds", "nanos").joinToString(" OR ") { column ->
            val value = "CASE WHEN $eligible THEN $column END"
            "MIN($value)<>MAX($value)"
        }
        val fields = "device,inode,$path,($conflict)"
        val source = "FROM nodes INDEXED BY node_identity WHERE sid=?"
        val grouping = " GROUP BY device,inode HAVING COUNT(*)>1 AND $path IS NOT NULL"
        val insert = "INSERT INTO hardlinks(sid,device,inode,path,changed)"
        val key = "(device,inode)"
        // Page by the 4096th index entry. The bulk statement stays below 4096
        // input rows. Read the boundary group separately with a cancellation
        // signal, since a single identity can have more than 4096 aliases.
        db.compileStatement("$insert VALUES(?,?,?,?,?)").use { boundary ->
            var lower = ""
            while (true) {
                check()
                val upper = db.rawQuery("SELECT device,inode $source$lower ORDER BY device,inode LIMIT 1 OFFSET 4095",
                    arrayOf(id), signal).use {
                    if (it.moveToFirst()) "(X'${it.getBlob(0).hex()}',X'${it.getBlob(1).hex()}')" else null
                }
                val ceiling = if (upper == null) "" else " AND $key<$upper"
                batch {
                    it.execSQL("$insert SELECT ?,$fields $source$lower$ceiling$grouping", arrayOf(id, id))
                    if (upper != null) {
                        it.rawQuery("SELECT $fields $source AND $key=$upper$grouping", arrayOf(id), signal).use { group ->
                            if (group.moveToFirst()) {
                                check()
                                boundary.bindString(1, id)
                                boundary.bindBlob(2, group.getBlob(0))
                                boundary.bindBlob(3, group.getBlob(1))
                                boundary.bindBlob(4, group.getBlob(2))
                                boundary.bindLong(5, group.getLong(3))
                                boundary.executeInsert()
                            }
                        }
                    }
                }
                if (upper == null) break
                lower = " AND $key>$upper"
            }
        }
    }

    private fun nodePageEnd(db: SQLiteDatabase, id: String, after: Long, signal: CancellationSignal): Long =
        db.rawQuery("SELECT MAX(id) FROM (SELECT id FROM nodes WHERE sid=? AND id>? ORDER BY id LIMIT 4096)",
            arrayOf(id, after.toString()), signal).use { it.moveToFirst(); if (it.isNull(0)) after else it.getLong(0) }

    private fun applyOwners(db: SQLiteDatabase, id: String, signal: CancellationSignal, check: () -> Unit,
        batch: ((SQLiteDatabase) -> Unit) -> Unit = { transaction(id, it) }) {
        val sparse = finalizePolicy.sparseOwners
        if (sparse && db.rawQuery("SELECT 1 FROM hardlinks WHERE sid=? LIMIT 1", arrayOf(id), signal).use { !it.moveToFirst() }) return
        var after = 0L
        while (true) {
            check(); val last = nodePageEnd(db, id, after, signal)
            if (last == after) break
            batch {
                val range = arrayOf<Any>(id, after, last)
                if (sparse) {
                    // Imported rows already carry the correct default contribution.
                    // Update only displaced owners and members of changed identities.
                    it.execSQL("""INSERT OR REPLACE INTO aggregates
                        (sid,id,parent,depth,kind,name_key,owner,own_logical,own_allocated,logical,allocated,complete)
                        SELECT a.sid,a.id,a.parent,a.depth,a.kind,a.name_key,o.id,
                            CASE WHEN a.kind<>1 AND o.id<>a.id THEN 0 ELSE a.own_logical END,
                            CASE WHEN a.kind<>1 AND o.id<>a.id THEN 0 ELSE a.own_allocated END,
                            CASE WHEN a.kind<>1 AND o.id<>a.id THEN 0 ELSE a.logical END,
                            CASE WHEN a.kind<>1 AND o.id<>a.id THEN 0 ELSE a.allocated END,
                            a.complete AND h.changed=0
                        FROM nodes n CROSS JOIN hardlinks h ON h.sid=n.sid AND h.device=n.device AND h.inode=n.inode
                        CROSS JOIN nodes o ON o.sid=h.sid AND o.path=h.path
                        CROSS JOIN aggregates a ON a.sid=n.sid AND a.id=n.id
                        WHERE n.sid=? AND n.id>? AND n.id<=? AND (o.id<>a.id OR (h.changed=1 AND a.complete<>0))""", range)
                } else {
                    it.execSQL("""UPDATE aggregates SET
                    owner=COALESCE((SELECT o.id FROM nodes n JOIN hardlinks h ON h.sid=n.sid AND h.device=n.device AND h.inode=n.inode
                        JOIN nodes o ON o.sid=h.sid AND o.path=h.path WHERE n.sid=aggregates.sid AND n.id=aggregates.id),id),
                    complete=complete AND NOT EXISTS(SELECT 1 FROM nodes n JOIN hardlinks h ON h.sid=n.sid AND h.device=n.device AND h.inode=n.inode
                        WHERE n.sid=aggregates.sid AND n.id=aggregates.id AND h.changed=1)
                    WHERE sid=? AND id>? AND id<=?""", range)
                    it.execSQL("UPDATE aggregates SET own_logical=0,own_allocated=0,logical=0,allocated=0 WHERE sid=? AND id>? AND id<=? AND kind<>1 AND owner<>id",
                        range)
                }
                it.execSQL("""INSERT INTO issues(sid,node,code,errno,operation) SELECT n.sid,n.id,6,116,'hardlink-metadata-changed'
                    FROM nodes n JOIN hardlinks h ON h.sid=n.sid AND h.device=n.device AND h.inode=n.inode
                    WHERE n.sid=? AND n.id>? AND n.id<=? AND n.kind<>1 AND h.changed=1""", range)
            }
            after = last
        }
    }

    private fun aggregate(db: SQLiteDatabase, id: String, signal: CancellationSignal, check: () -> Unit,
        cancellableReads: Boolean = false, batch: ((SQLiteDatabase) -> Unit) -> Unit = { transaction(id, it) }) {
        // Import requires directory parents, so every non-directory is already
        // final after applyOwners().
        val directories = finalizePolicy.directoriesOnly
        val depthSql = if (directories) "SELECT depth FROM aggregates WHERE sid=? ORDER BY depth DESC LIMIT 1"
            else "SELECT COALESCE(MAX(depth),0) FROM nodes WHERE sid=?"
        val depth = db.rawQuery(depthSql, arrayOf(id), signal).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val kind = if (directories) " AND kind=1" else ""
        for (level in depth downTo 0) {
            var after = 0L
            while (true) {
                check()
                val page = "SELECT id FROM aggregates WHERE sid=? AND depth=? AND id>?$kind ORDER BY id LIMIT 1024"
                val last = db.rawQuery(if (directories) "SELECT MAX(id) FROM ($page)" else page,
                    arrayOf(id, level.toString(), after.toString()), signal).use { cursor ->
                    var last = after
                    while (cursor.moveToNext()) if (!cursor.isNull(0)) last = cursor.getLong(0)
                    last
                }
                if (last == after) break
                batch {
                    val range = arrayOf<Any>(id, level, after, last)
                    val values = if (directories) """(logical,allocated,complete)=(
                        SELECT aggregates.own_logical+COALESCE(SUM(c.logical),0),
                            aggregates.own_allocated+COALESCE(SUM(c.allocated),0),
                            aggregates.complete AND COALESCE(MIN(c.complete),1)
                        FROM aggregates c INDEXED BY aggregate_parent WHERE c.sid=aggregates.sid AND c.parent=aggregates.id)"""
                    else """logical=own_logical+COALESCE((SELECT SUM(c.logical) FROM aggregates c WHERE c.sid=aggregates.sid AND c.parent=aggregates.id),0),
                        allocated=own_allocated+COALESCE((SELECT SUM(c.allocated) FROM aggregates c WHERE c.sid=aggregates.sid AND c.parent=aggregates.id),0),
                        complete=complete AND NOT EXISTS(SELECT 1 FROM aggregates c WHERE c.sid=aggregates.sid AND c.parent=aggregates.id AND c.complete=0)"""
                    if (!cancellableReads) it.execSQL("UPDATE aggregates SET $values WHERE sid=? AND depth=? AND id>? AND id<=?$kind", range)
                    else {
                        // Wide child sums run through a cancellable SELECT; each following write updates one row.
                        it.compileStatement("UPDATE aggregates SET logical=?,allocated=?,complete=? WHERE sid=? AND id=?").use { update ->
                            it.rawQuery("""SELECT a.id,a.own_logical+COALESCE(SUM(c.logical),0),
                                a.own_allocated+COALESCE(SUM(c.allocated),0),a.complete AND COALESCE(MIN(c.complete),1)
                                FROM aggregates a LEFT JOIN aggregates c INDEXED BY aggregate_parent ON c.sid=a.sid AND c.parent=a.id
                                WHERE a.sid=? AND a.depth=? AND a.id>? AND a.id<=?${if (directories) " AND a.kind=1" else ""}
                                GROUP BY a.id ORDER BY a.id""", arrayOf(id, level.toString(), after.toString(), last.toString()), signal).use { rows ->
                                while (rows.moveToNext()) {
                                    check()
                                    kotlin.check(rows.getType(1) == android.database.Cursor.FIELD_TYPE_INTEGER &&
                                        rows.getType(2) == android.database.Cursor.FIELD_TYPE_INTEGER) { "Aggregate counter overflow" }
                                    update.bindLong(1, rows.getLong(1)); update.bindLong(2, rows.getLong(2)); update.bindLong(3, rows.getLong(3))
                                    update.bindString(4, id); update.bindLong(5, rows.getLong(0)); update.executeUpdateDelete()
                                }
                            }
                        }
                    }
                    it.rawQuery("""SELECT 1 FROM aggregates WHERE sid=? AND depth=? AND id>? AND id<=?$kind
                        AND (typeof(logical)<>'integer' OR typeof(allocated)<>'integer') LIMIT 1""",
                        arrayOf(id, level.toString(), after.toString(), last.toString()), signal).use { overflow ->
                        kotlin.check(!overflow.moveToFirst()) { "Aggregate counter overflow" }
                    }
                }
                after = last
            }
        }
    }

    internal enum class RebuildPoint { INITIALIZED, OWNERS_COLLECTED, OWNERS_APPLIED, AGGREGATED }

    /** Raw metadata is authoritative. No commits, revision increments, or snapshot publication here. */
    internal fun rebuildSnapshot(id: String, signal: CancellationSignal, check: () -> Unit, point: (RebuildPoint) -> Unit) {
        val db = writableDatabase
        require(db.inTransaction())
        fun deletePages(sql: String) {
            db.compileStatement(sql).use { statement ->
                statement.bindString(1, id)
                do { check(); val count = statement.executeUpdateDelete() } while (count == 4096)
            }
        }
        // Remove obsolete IDs, but retain rows already equal to their authoritative raw baseline.
        // Replacing every unchanged singleton also rewrites all secondary indexes and creates a
        // snapshot-sized WAL even for a tiny refresh. All nodes are still checked and owners and
        // directories are still recomputed globally inside the same transaction.
        var aggregateAfter = 0L
        while (true) {
            check()
            val last = db.rawQuery("SELECT MAX(id) FROM (SELECT id FROM aggregates WHERE sid=? AND id>? ORDER BY id LIMIT 4096)",
                arrayOf(id, aggregateAfter.toString()), signal).use { it.moveToFirst(); if (it.isNull(0)) aggregateAfter else it.getLong(0) }
            if (last == aggregateAfter) break
            db.execSQL("""DELETE FROM aggregates WHERE sid=? AND id>? AND id<=? AND NOT EXISTS
                (SELECT 1 FROM nodes n WHERE n.sid=aggregates.sid AND n.id=aggregates.id)""", arrayOf<Any>(id, aggregateAfter, last))
            aggregateAfter = last
        }
        deletePages("DELETE FROM hardlinks WHERE (sid,device,inode) IN (SELECT sid,device,inode FROM hardlinks WHERE sid=? LIMIT 4096)")
        var issueAfter = 0L
        while (true) {
            check()
            val last = db.rawQuery("SELECT MAX(id) FROM (SELECT id FROM issues WHERE sid=? AND id>? ORDER BY id LIMIT 4096)",
                arrayOf(id, issueAfter.toString()), signal).use { it.moveToFirst(); if (it.isNull(0)) issueAfter else it.getLong(0) }
            if (last == issueAfter) break
            db.execSQL("DELETE FROM issues WHERE sid=? AND id>? AND id<=? AND code=6 AND errno=116 AND operation='hardlink-metadata-changed'",
                arrayOf<Any>(id, issueAfter, last))
            issueAfter = last
        }
        var after = 0L
        while (true) {
            check(); val last = nodePageEnd(db, id, after, signal)
            if (last == after) break
            db.execSQL("""INSERT OR REPLACE INTO aggregates
                SELECT n.sid,n.id,n.parent,n.depth,n.kind,n.name_key,n.id,
                    CASE WHEN n.valid=1 AND n.skip=0 AND n.kind=2 THEN n.size ELSE 0 END,
                    CASE WHEN n.valid=1 AND n.skip=0 THEN n.allocated ELSE 0 END,
                    CASE WHEN n.valid=1 AND n.skip=0 AND n.kind=2 THEN n.size ELSE 0 END,
                    CASE WHEN n.valid=1 AND n.skip=0 THEN n.allocated ELSE 0 END,n.complete
                FROM nodes n LEFT JOIN aggregates a ON a.sid=n.sid AND a.id=n.id
                WHERE n.sid=? AND n.id>? AND n.id<=? AND (a.id IS NULL OR
                    (a.parent,a.depth,a.kind,a.name_key,a.owner,a.own_logical,a.own_allocated,a.logical,a.allocated,a.complete)<>
                    (n.parent,n.depth,n.kind,n.name_key,n.id,
                    CASE WHEN n.valid=1 AND n.skip=0 AND n.kind=2 THEN n.size ELSE 0 END,
                    CASE WHEN n.valid=1 AND n.skip=0 THEN n.allocated ELSE 0 END,
                    CASE WHEN n.valid=1 AND n.skip=0 AND n.kind=2 THEN n.size ELSE 0 END,
                    CASE WHEN n.valid=1 AND n.skip=0 THEN n.allocated ELSE 0 END,n.complete))""", arrayOf<Any>(id, after, last))
            after = last
        }
        point(RebuildPoint.INITIALIZED); check()
        val batch: ((SQLiteDatabase) -> Unit) -> Unit = { operation -> check(); operation(db); check() }
        if (finalizePolicy.sparseOwners) collectSharedOwners(db, id, signal, check, batch) else collectAllOwners(db, id, signal, check, batch)
        point(RebuildPoint.OWNERS_COLLECTED); check()
        applyOwners(db, id, signal, check, batch)
        point(RebuildPoint.OWNERS_APPLIED); check()
        aggregate(db, id, signal, check, cancellableReads = true, batch = batch)
        point(RebuildPoint.AGGREGATED); check()
    }

    fun finalize(id: String, summary: StreamSummary, signal: CancellationSignal = CancellationSignal(), cancelled: () -> Boolean): JSONObject {
        val start = android.os.SystemClock.elapsedRealtime()
        val startNanos = System.nanoTime()
        val db = writableDatabase
        db.rawQuery("SELECT 1 FROM snapshots WHERE sid=?", arrayOf(id)).use { check(!it.moveToFirst()) { "Snapshot is immutable" } }
        fun check() {
            if (cancelled()) throw CancellationException("Index finalization cancelled")
            signal.throwIfCanceled()
        }
        check()
        transaction(id) { it.execSQL("UPDATE sessions SET state='finalizing',phase='deduplicating' WHERE id=?", arrayOf(id)) }
        if (finalizePolicy.sparseOwners) collectSharedOwners(db, id, signal, ::check) else collectAllOwners(db, id, signal, ::check)
        transaction(id) { it.execSQL("UPDATE sessions SET phase='aggregating' WHERE id=?", arrayOf(id)) }
        val dedupMillis = android.os.SystemClock.elapsedRealtime() - start
        val initializeStart = System.nanoTime()
        applyOwners(db, id, signal, ::check)
        val initializeMillis = android.os.SystemClock.elapsedRealtime() - start - dedupMillis
        val aggregateStart = System.nanoTime()
        aggregate(db, id, signal, ::check)
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
                .put("variant", finalizePolicy.wireName)
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
