package com.example.disksize.index

import android.database.sqlite.SQLiteDatabase
import com.example.disksize.scan.SnapshotRescanContract as Contract
import org.json.JSONObject
import java.util.concurrent.CancellationException

/** Ordinary-UID persistence only. The coordinator must confine writers to its index worker. */
internal class SnapshotRescanStore(private val store: IndexStore) {
    data class Job(
        val id: String, val snapshotId: String, val sessionId: String, val targetNode: Long,
        val targetPath: ByteArray, val expectedRevision: Long, val state: Contract.State,
        val created: Long, val started: Long, val scanned: Long, val ended: Long, val error: String,
        val committedRevision: Long?, val cancellationRequested: Boolean,
        val nodes: Long, val issues: Long, val stagingCleaned: Boolean,
        val metrics: String, val summary: String, val volume: String,
    )

    class Rejected(val reason: Contract.Error) : IllegalStateException(reason.wireName)

    companion object {
        private val active = Contract.State.entries.filterNot { it.terminal }.joinToString(",") { "'${it.wireName}'" }
        private val states = Contract.State.entries.joinToString(",") { "'${it.wireName}'" }
        private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE rescan_jobs(id TEXT PRIMARY KEY,
                snapshot_id TEXT NOT NULL REFERENCES snapshots(id) ON DELETE CASCADE,
                sid TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                target_node INTEGER NOT NULL CHECK(target_node>0), target_path BLOB NOT NULL,
                expected_revision INTEGER NOT NULL CHECK(expected_revision>=0 AND expected_revision<9223372036854775807),
                state TEXT NOT NULL CHECK(state IN ($states)), created INTEGER NOT NULL,
                started INTEGER NOT NULL DEFAULT 0, scanned INTEGER NOT NULL DEFAULT 0, ended INTEGER NOT NULL DEFAULT 0,
                error TEXT NOT NULL DEFAULT '', committed_revision INTEGER,
                cancel_requested INTEGER NOT NULL DEFAULT 0 CHECK(cancel_requested IN (0,1)),
                nodes INTEGER NOT NULL DEFAULT 0, issues INTEGER NOT NULL DEFAULT 0,
                staging_cleaned INTEGER NOT NULL DEFAULT 0 CHECK(staging_cleaned IN (0,1)),
                metrics TEXT NOT NULL DEFAULT '{}', summary TEXT NOT NULL DEFAULT '{}', volume TEXT NOT NULL DEFAULT '{}',
                CHECK(committed_revision IS NULL OR committed_revision=expected_revision+1),
                CHECK(state<>'completed' OR committed_revision IS NOT NULL))""")
            db.execSQL("CREATE UNIQUE INDEX rescan_active ON rescan_jobs((1)) WHERE state IN ($active)")
            db.execSQL("CREATE INDEX rescan_history ON rescan_jobs(snapshot_id,created DESC,id DESC)")
            db.execSQL("""CREATE TABLE rescan_nodes(task_id TEXT NOT NULL REFERENCES rescan_jobs(id) ON DELETE CASCADE,
                id INTEGER NOT NULL, parent INTEGER NOT NULL, depth INTEGER NOT NULL,
                name BLOB NOT NULL, name_key TEXT NOT NULL, path BLOB NOT NULL, link BLOB NOT NULL,
                kind INTEGER NOT NULL, mode INTEGER NOT NULL, uid INTEGER NOT NULL, gid INTEGER NOT NULL,
                device BLOB NOT NULL, inode BLOB NOT NULL, mount INTEGER NOT NULL, nlink INTEGER NOT NULL,
                size INTEGER NOT NULL, allocated INTEGER NOT NULL, seconds INTEGER NOT NULL, nanos INTEGER NOT NULL,
                valid INTEGER NOT NULL, complete INTEGER NOT NULL, enumerated INTEGER NOT NULL DEFAULT 0,
                skip INTEGER NOT NULL, PRIMARY KEY(task_id,id)) WITHOUT ROWID""")
            db.execSQL("CREATE UNIQUE INDEX rescan_path ON rescan_nodes(task_id,path)")
            db.execSQL("CREATE INDEX rescan_parent ON rescan_nodes(task_id,parent,id)")
            db.execSQL("""CREATE TABLE rescan_issues(id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id TEXT NOT NULL REFERENCES rescan_jobs(id) ON DELETE CASCADE,
                node INTEGER NOT NULL, code INTEGER NOT NULL, errno INTEGER NOT NULL, operation TEXT NOT NULL)""")
            db.execSQL("CREATE INDEX rescan_issue_node ON rescan_issues(task_id,node,id)")
        }
    }

    private fun <T> write(block: (SQLiteDatabase) -> T): T {
        val db = store.writableDatabase
        check(!db.inTransaction()) { "Finish or close the staging batch before changing task state" }
        db.beginTransactionNonExclusive()
        return try { block(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    fun hasActive(db: SQLiteDatabase = store.readableDatabase, snapshotId: String? = null): Boolean {
        val scope = if (snapshotId == null) "" else " AND snapshot_id=?"
        return db.rawQuery("SELECT 1 FROM rescan_jobs WHERE state IN ($active)$scope LIMIT 1",
            snapshotId?.let { arrayOf(it) }).use { it.moveToFirst() }
    }

    /** Derive scope from trusted raw index bytes, never a caller-supplied path. Live identity checks come later. */
    fun create(id: String, request: Contract.Request): Job = write { db ->
        if (!uuid.matches(id)) throw Rejected(Contract.Error.INVALID_REQUEST)
        var sid = ""
        var path = byteArrayOf()
        val target = db.rawQuery("""SELECT p.sid,p.revision,s.revision,s.provisional,s.state,p.retained,n.kind,n.path
            FROM snapshots p JOIN sessions s ON s.id=p.sid
            LEFT JOIN nodes n ON n.sid=p.sid AND n.id=? WHERE p.id=?""",
            arrayOf(request.nodeId.toString(), request.snapshotId)).use { c ->
            if (!c.moveToFirst()) null else {
                sid = c.getString(0)
                if (!c.isNull(7)) path = c.getBlob(7)
                Contract.Target(c.getLong(1),
                    available = c.getLong(1) == c.getLong(2) && c.getInt(3) == 0 && c.getString(4) in setOf("completed", "partial"),
                    retained = c.getInt(5) == 1, directory = !c.isNull(6) && c.getInt(6) == 1, nodeExists = !c.isNull(6))
            }
        }
        val ordinaryActive = db.rawQuery("SELECT 1 FROM sessions WHERE state IN ('running','finalizing') LIMIT 1", null).use { it.moveToFirst() }
        Contract.admissionError(request, target, hasActive(db) || ordinaryActive)?.let { throw Rejected(it) }
        db.compileStatement("""INSERT INTO rescan_jobs(id,snapshot_id,sid,target_node,target_path,expected_revision,state,created)
            VALUES(?,?,?,?,?,?,'preparing',?)""").use {
            it.bindAll(listOf(id, request.snapshotId, sid, request.nodeId, path, request.expectedRevision, System.currentTimeMillis()))
            it.executeInsert()
        }
        get(db, id)!!
    }

    fun get(id: String): Job? = get(store.readableDatabase, id)

    /** Bounded UI metadata; neither task discovery nor history opens staging or mutates the snapshot. */
    fun decorate(db: SQLiteDatabase, value: JSONObject, snapshotId: String): JSONObject {
        db.rawQuery("SELECT id FROM rescan_jobs WHERE snapshot_id=? ORDER BY created DESC,rowid DESC LIMIT 1", arrayOf(snapshotId)).use {
            if (it.moveToFirst()) value.put("latestRescanTaskId", it.getString(0))
        }
        db.rawQuery("""SELECT hex(substr(target_path,1,4096)),length(target_path),started,scanned,ended,committed_revision
            FROM rescan_jobs WHERE snapshot_id=? AND committed_revision IS NOT NULL
            ORDER BY committed_revision DESC LIMIT 1""", arrayOf(snapshotId)).use {
            if (it.moveToFirst()) value.put("lastLocalUpdate", JSONObject()
                .put("targetPathHex", it.getString(0).lowercase()).put("pathTruncated", it.getLong(1) > 4096)
                .put("startedAtMillis", it.getLong(2)).put("scannedAtMillis", it.getLong(3))
                .put("endedAtMillis", it.getLong(4)).put("revision", it.getLong(5)))
        }
        return value
    }

    fun apply(id: String, signal: android.os.CancellationSignal = android.os.CancellationSignal(),
        cancelled: () -> Boolean = { false }, checkpoint: (SnapshotRescanMerger.Point) -> Unit = {},
        cancellationError: () -> Contract.Error = { Contract.Error.CANCELLED }): Job =
        SnapshotRescanMerger(store).apply(id, signal, cancelled, checkpoint, cancellationError)

    fun source(id: String): SnapshotRescanSource = SnapshotRescanSource.read(store, requireNotNull(get(id)))

    fun scopeError(id: String): Contract.Error? = store.readableDatabase.rawQuery(
        "SELECT operation FROM rescan_issues WHERE task_id=? AND operation LIKE 'scope-%' ORDER BY id LIMIT 1", arrayOf(id)).use {
        if (!it.moveToFirst()) null else when (it.getString(0)) {
            "scope-missing" -> Contract.Error.TARGET_MISSING
            "scope-type-changed" -> Contract.Error.TARGET_TYPE_CHANGED
            "scope-identity", "scope-path-changed" -> Contract.Error.INCOMPATIBLE_IDENTITY
            "scope-invalid" -> Contract.Error.PROTOCOL_ERROR
            "scope-cancelled" -> Contract.Error.CANCELLED
            else -> Contract.Error.SCAN_FAILED
        }
    }

    private fun get(db: SQLiteDatabase, id: String): Job? = db.rawQuery("SELECT * FROM rescan_jobs WHERE id=?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) return null
        fun s(key: String) = c.getString(c.getColumnIndexOrThrow(key))
        fun n(key: String) = c.getLong(c.getColumnIndexOrThrow(key))
        val marker = c.getColumnIndexOrThrow("committed_revision")
        Job(s("id"), s("snapshot_id"), s("sid"), n("target_node"), c.getBlob(c.getColumnIndexOrThrow("target_path")),
            n("expected_revision"), Contract.State.entries.first { it.wireName == s("state") }, n("created"), n("started"), n("scanned"), n("ended"),
            s("error"), if (c.isNull(marker)) null else c.getLong(marker), n("cancel_requested") == 1L,
            n("nodes"), n("issues"), n("staging_cleaned") == 1L, s("metrics"), s("summary"), s("volume"))
    }

    fun transition(id: String, state: Contract.State, error: Contract.Error? = null): Job = write { db ->
        val old = requireNotNull(get(db, id))
        require(state != Contract.State.COMPLETED) { "Completion requires the merge transaction's durable marker" }
        require(old.committedRevision == null && Contract.canTransition(old.state, state))
        require(if (state.terminal) error != null else error == null)
        when (state) {
            Contract.State.CANCELLED -> require(error == Contract.Error.CANCELLED)
            Contract.State.INTERRUPTED -> require(error == Contract.Error.PROCESS_INTERRUPTED)
            Contract.State.STALE -> require(error == Contract.Error.STALE_REVISION)
            else -> Unit
        }
        if (!state.terminal && old.cancellationRequested) throw CancellationException("Cancellation pending")
        val now = System.currentTimeMillis()
        db.execSQL("UPDATE rescan_jobs SET state=?,error=?,started=?,ended=? WHERE id=?",
            arrayOf<Any>(state.wireName, error?.wireName ?: "", if (state == Contract.State.SCANNING) now else old.started,
                if (state.terminal) now else 0L, id))
        if (state.terminal) clean(db, id)
        get(db, id)!!
    }

    fun requestCancellation(id: String): Contract.Cancellation = write { db ->
        val job = requireNotNull(get(db, id))
        Contract.cancellation(job.state, job.committedRevision).also {
            if (it == Contract.Cancellation.REQUESTED) db.execSQL("UPDATE rescan_jobs SET cancel_requested=1 WHERE id=?", arrayOf(id))
        }
    }

    fun cleanStaging(id: String) = write { db ->
        val job = requireNotNull(get(db, id))
        check(job.state.terminal || job.committedRevision != null) { "Cannot clean an active task" }
        clean(db, id)
    }

    private fun clean(db: SQLiteDatabase, id: String) {
        db.execSQL("DELETE FROM rescan_issues WHERE task_id=?", arrayOf(id))
        db.execSQL("DELETE FROM rescan_nodes WHERE task_id=?", arrayOf(id))
        db.execSQL("UPDATE rescan_jobs SET staging_cleaned=1 WHERE id=?", arrayOf(id))
    }

    /** Run at process startup, before a writer exists. Never interrupt the target session. */
    fun recover() = write { db ->
        val now = System.currentTimeMillis()
        db.execSQL("""UPDATE rescan_jobs SET state='completed',error='',ended=CASE WHEN ended=0 THEN ? ELSE ended END
            WHERE committed_revision IS NOT NULL""", arrayOf(now))
        db.execSQL("""UPDATE rescan_jobs SET state='interrupted',error='process-interrupted',ended=?
            WHERE committed_revision IS NULL AND state IN ($active)""", arrayOf(now))
        for (table in listOf("rescan_issues", "rescan_nodes")) db.execSQL("""DELETE FROM $table WHERE task_id IN
            (SELECT id FROM rescan_jobs WHERE state NOT IN ($active))""")
        db.execSQL("UPDATE rescan_jobs SET staging_cleaned=1 WHERE state NOT IN ($active)")
    }

    /** Reservations are part of the caller's merge transaction; committed IDs are never reused. */
    fun allocateNodeIds(db: SQLiteDatabase, sessionId: String, count: Long): LongRange {
        require(db === store.writableDatabase && db.inTransaction() && count >= 0)
        val high = db.rawQuery("SELECT node_id_high_water FROM sessions WHERE id=?", arrayOf(sessionId)).use {
            require(it.moveToFirst()); it.getLong(0)
        }
        if (count == 0L) return LongRange.EMPTY
        val last = Math.addExact(high, count)
        db.execSQL("UPDATE sessions SET node_id_high_water=? WHERE id=?", arrayOf<Any>(last, sessionId))
        return (high + 1)..last
    }

    /** Call only after publishing matching session/snapshot revisions in the same outer transaction. */
    fun recordCommit(db: SQLiteDatabase, id: String, revision: Long, metrics: JSONObject = JSONObject()): Boolean {
        require(db === store.writableDatabase && db.inTransaction())
        val job = requireNotNull(get(db, id))
        job.committedRevision?.let { require(it == revision); return false }
        require(job.state == Contract.State.APPLYING && !job.cancellationRequested && revision == job.expectedRevision + 1)
        db.rawQuery("""SELECT 1 FROM snapshots p JOIN sessions s ON s.id=p.sid WHERE p.id=? AND p.sid=?
            AND p.revision=? AND s.revision=p.revision AND p.retained=0 AND s.provisional=0
            AND s.state IN ('completed','partial')""", arrayOf(job.snapshotId, job.sessionId, revision.toString())).use { check(it.moveToFirst()) }
        val now = System.currentTimeMillis()
        val timings = JSONObject(job.metrics)
        metrics.keys().forEach { key -> timings.put(key, metrics.get(key)) }
        db.execSQL("UPDATE snapshots SET local_updated=?,local_updates=local_updates+1 WHERE id=?", arrayOf<Any>(now, job.snapshotId))
        db.execSQL("UPDATE rescan_jobs SET committed_revision=?,state='completed',error='',ended=?,metrics=? WHERE id=?",
            arrayOf<Any>(revision, now, timings.toString(), id))
        return true
    }

    fun beginStaging(id: String): Staging {
        transition(id, Contract.State.SCANNING)
        return Staging(id)
    }

    inner class Staging internal constructor(private val id: String) : IndexConsumer, AutoCloseable {
        private val db = store.writableDatabase
        private val insert = db.compileStatement("""INSERT INTO rescan_nodes(task_id,id,parent,depth,name,name_key,path,link,
            kind,mode,uid,gid,device,inode,mount,nlink,size,allocated,seconds,nanos,valid,complete,skip)
            VALUES(${List(23) { "?" }.joinToString(",")})""")
        private val paths = LinkedHashMap<Long, Pair<ByteArray, Int>>(32, 0.75f, true)
        private var pathBytes = 0
        private var nodes = 0L
        private var issues = 0L
        private var actions = 0
        private var transaction = false
        private var openedAt = 0L
        private var closed = false
        override var writeNanos = 0L
            private set
        override val measuredImportNanos get() = writeNanos

        private fun checkWritable() {
            val job = requireNotNull(get(db, id))
            check(job.state == Contract.State.SCANNING && !job.stagingCleaned && job.committedRevision == null)
            if (job.cancellationRequested) throw CancellationException("Snapshot rescan cancelled")
        }

        private inline fun action(block: () -> Unit) {
            check(!closed)
            val before = System.nanoTime()
            if (!transaction) {
                db.beginTransactionNonExclusive(); transaction = true; openedAt = before
                try { checkWritable() } catch (e: Exception) { db.endTransaction(); transaction = false; throw e }
            }
            try { block(); actions++ } catch (e: Exception) { close(); throw e }
            writeNanos += System.nanoTime() - before
            if (actions >= 4096 || System.nanoTime() - openedAt >= 250_000_000L) flush(IndexStore.FlushReason.LIMIT)
        }

        override fun node(n: IndexNode) = action {
            require(n.id == nodes + 1 && n.id <= 2_000_000 && n.parent in 0 until n.id)
            require(if (n.id == 1L) n.parent == 0L && n.name.isEmpty() else n.parent > 0 && n.name.isNotEmpty())
            require(n.name.size <= 255 && n.name.none { it == 0.toByte() || it == 47.toByte() })
            require(!n.name.contentEquals(byteArrayOf(46)) && !n.name.contentEquals(byteArrayOf(46, 46)))
            val parent = if (n.parent == 0L) byteArrayOf() to -1 else paths[n.parent] ?: db.rawQuery(
                "SELECT path,depth FROM rescan_nodes WHERE task_id=? AND id=? AND kind=1", arrayOf(id, n.parent.toString())).use {
                require(it.moveToFirst()) { "Missing/non-directory staging parent" }; it.getBlob(0) to it.getInt(1)
            }
            require(n.depth == parent.second + 1 && n.depth <= 512)
            val path = if (parent.first.isEmpty()) n.name else ByteArray(parent.first.size + 1 + n.name.size).also {
                parent.first.copyInto(it); it[parent.first.size] = 47; n.name.copyInto(it, parent.first.size + 1)
            }
            require(path.size <= 512 * 256)
            insert.apply {
                bindString(1, id); bindLong(2, n.id); bindLong(3, n.parent); bindLong(4, n.depth.toLong())
                bindBlob(5, n.name); bindString(6, n.name.hex()); bindBlob(7, path); bindBlob(8, n.link)
                bindLong(9, n.kind.toLong()); bindLong(10, n.mode); bindLong(11, n.uid); bindLong(12, n.gid)
                bindBlob(13, n.device); bindBlob(14, n.inode); bindLong(15, n.mount); bindLong(16, n.links)
                bindLong(17, n.size); bindLong(18, n.allocated); bindLong(19, n.seconds); bindLong(20, n.nanos)
                bindLong(21, if (n.valid) 1 else 0); bindLong(22, if (n.complete) 1 else 0); bindLong(23, n.skip.toLong())
            }
            insert.executeInsert(); nodes++
            if (n.kind == 1) {
                paths.put(n.id, path to n.depth)?.let { pathBytes -= it.first.size }; pathBytes += path.size
                while (paths.size > 32 || pathBytes > 1_048_576) {
                    val first = paths.entries.iterator(); pathBytes -= first.next().value.first.size; first.remove()
                }
            }
        }

        override fun directory(node: Long, enumerated: Boolean, complete: Boolean, skip: Int) = action {
            db.compileStatement("UPDATE rescan_nodes SET enumerated=?,complete=?,skip=? WHERE task_id=? AND id=? AND kind=1").use {
                it.bindAll(listOf(enumerated, complete, skip, id, node)); require(it.executeUpdateDelete() == 1)
            }
        }

        override fun issue(node: Long, code: Int, errno: Int, op: String) = action {
            require(node in 0..nodes && code in errors.indices && op.length <= 4096)
            db.execSQL("INSERT INTO rescan_issues(task_id,node,code,errno,operation) VALUES(?,?,?,?,?)", arrayOf<Any>(id, node, code, errno, op))
            db.execSQL("UPDATE rescan_nodes SET complete=0 WHERE task_id=? AND id=?", arrayOf<Any>(id, node)); issues++
        }

        override fun volume(total: Long, free: Long, available: Long, captured: Long) = action {
            val volume = JSONObject().put("totalBytes", total).put("freeBytes", free).put("availableBytes", available).put("capturedAtMillis", captured)
            db.execSQL("UPDATE rescan_jobs SET volume=? WHERE id=?", arrayOf(volume.toString(), id))
        }

        override fun flush(reason: IndexStore.FlushReason) {
            check(!closed)
            if (!transaction || (reason == IndexStore.FlushReason.PROGRESS && System.nanoTime() - openedAt < 250_000_000L)) return
            val before = System.nanoTime()
            try {
                checkWritable()
                db.execSQL("UPDATE rescan_jobs SET nodes=?,issues=? WHERE id=?", arrayOf<Any>(nodes, issues, id))
                db.setTransactionSuccessful()
            } catch (e: Exception) { closed = true; insert.close(); paths.clear(); throw e }
            finally { db.endTransaction(); transaction = false; actions = 0; writeNanos += System.nanoTime() - before }
        }

        fun finish(summary: StreamSummary) {
            require(summary.nodes == nodes && summary.issues == issues)
            flush(IndexStore.FlushReason.SUMMARY)
            write { target ->
                checkWritable()
                target.execSQL("UPDATE rescan_jobs SET state='validating',scanned=?,summary=?,metrics=? WHERE id=?", arrayOf<Any>(
                    System.currentTimeMillis(),
                    JSONObject().put("state", summary.state).put("nodes", summary.nodes).put("issues", summary.issues)
                        .put("scanMicros", summary.elapsed).put("peakOpenFds", summary.peakFds).put("openFdsAtFinish", summary.finishFds)
                        .put("rootMountId", summary.mount).put("rootFilesystemType", summary.filesystem).toString(),
                    JSONObject().put("importMicros", measuredImportNanos / 1000).put("performance", summary.performance).toString(), id))
            }
            close()
        }

        override fun close() {
            if (transaction) { db.endTransaction(); transaction = false }
            insert.close(); paths.clear(); closed = true
        }
    }
}
