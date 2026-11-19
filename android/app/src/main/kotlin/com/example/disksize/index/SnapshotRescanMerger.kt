package com.example.disksize.index

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.example.disksize.scan.SnapshotRescanContract as Contract
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.CancellationException
import kotlin.concurrent.withLock

/** One ordinary-UID worker, one atomic replacement. Temporary tables live on its pinned write connection. */
internal class SnapshotRescanMerger(private val store: IndexStore) {
    enum class Point {
        VALIDATED, MEMBERS_COLLECTED, IDS_RESERVED, OLD_REMOVED, NODES_INSERTED,
        INITIALIZED, OWNERS_COLLECTED, OWNERS_APPLIED, AGGREGATED, PUBLISHED, BEFORE_COMMIT, AFTER_COMMIT,
    }

    private data class Target(val parent: Long, val depth: Int, val name: ByteArray, val nameKey: String,
        val path: ByteArray, val device: ByteArray, val mount: Long, val filesystem: Long, val nodes: Long)

    private fun reject(error: Contract.Error): Nothing = throw SnapshotRescanStore.Rejected(error)

    fun apply(id: String, signal: CancellationSignal, cancelled: () -> Boolean, checkpoint: (Point) -> Unit,
        cancellationError: () -> Contract.Error): SnapshotRescanStore.Job =
        store.snapshotMutationLock.withLock {
            val jobs = store.rescans
            val initial = requireNotNull(jobs.get(id))
            // Check the marker before reserving IDs or touching any source data, including after later updates.
            if (initial.committedRevision != null || initial.state.terminal) return@withLock initial
            require(initial.state == Contract.State.VALIDATING) { "Only a closed, validated stream may be applied" }
            val db = store.writableDatabase
            check(!db.inTransaction())
            val start = System.nanoTime()
            fun check() {
                signal.throwIfCanceled()
                if (cancelled() || initial.cancellationRequested) throw CancellationException("Snapshot rescan cancelled")
            }
            fun point(value: Point) { checkpoint(value); check() }
            try {
                check()
                jobs.transition(id, Contract.State.APPLYING)
                db.beginTransactionNonExclusive()
                var bodyFailure: Throwable? = null
                try {
                    val job = requireNotNull(jobs.get(id))
                    if (job.cancellationRequested) throw CancellationException("Snapshot rescan cancelled")
                    val target = target(db, job, signal)
                    validateStage(db, job, target, signal, ::check)
                    point(Point.VALIDATED)
                    val validated = System.nanoTime()
                    createTemporaryTables(db)
                    val oldCount = collectMembers(db, job, signal, ::check)
                    if (target.nodes - oldCount + job.nodes !in 1L..2_000_000L) reject(Contract.Error.INCOMPLETE_SCAN)
                    point(Point.MEMBERS_COLLECTED)
                    val descendants = jobs.allocateNodeIds(db, job.sessionId, job.nodes - 1)
                    createMapping(db, job, descendants.first, signal, ::check)
                    point(Point.IDS_RESERVED)
                    replaceNodes(db, job, target, signal, ::check) { point(it) }
                    val replaced = System.nanoTime()
                    val metrics = JSONObject().put("validationMicros", (validated - start) / 1000)
                        .put("replacementMicros", (replaced - validated) / 1000).put("oldSubtreeNodes", oldCount)
                        .put("newSubtreeNodes", job.nodes)
                    var phaseStart = replaced
                    store.rebuildSnapshot(job.sessionId, signal, ::check) { value ->
                        val time = System.nanoTime()
                        metrics.put("${value.name.lowercase()}Micros", (time - phaseStart) / 1000)
                        phaseStart = time
                        point(Point.valueOf(value.name))
                    }
                    publish(db, job, signal, metrics, ::check)
                    point(Point.PUBLISHED)
                    db.execSQL("DROP TABLE temp.rescan_members"); db.execSQL("DROP TABLE temp.rescan_mapping")
                    point(Point.BEFORE_COMMIT)
                    db.setTransactionSuccessful()
                } catch (error: Throwable) {
                    bodyFailure = error
                    throw error
                } finally {
                    try { db.endTransaction() } catch (rollback: Exception) {
                        // SQLITE_FULL can roll back SQLite's transaction automatically. Android's
                        // subsequent ROLLBACK then fails; preserve the original storage failure.
                        val original = bodyFailure
                        if (original != null) original.addSuppressed(rollback) else throw rollback
                    }
                }
                // Publication/cleanup errors after a commit cannot turn it into a failed or cancelled update.
                checkpoint(Point.AFTER_COMMIT)
                jobs.cleanStaging(id)
                requireNotNull(jobs.get(id))
            } catch (error: Exception) {
                val durable = jobs.get(id)
                if (durable?.committedRevision != null) return@withLock durable
                val reason = when (error) {
                    is SnapshotRescanStore.Rejected -> error.reason
                    is OperationCanceledException, is CancellationException -> cancellationError().also {
                        require(it in setOf(Contract.Error.CANCELLED, Contract.Error.PROCESS_INTERRUPTED))
                    }
                    is SQLiteFullException, is SQLiteDiskIOException -> Contract.Error.STORAGE_ERROR
                    is JSONException -> Contract.Error.PROTOCOL_ERROR
                    else -> Contract.Error.APPLY_FAILED
                }
                if (durable != null && !durable.state.terminal) {
                    val state = when (reason) {
                        Contract.Error.STALE_REVISION -> Contract.State.STALE
                        Contract.Error.CANCELLED -> Contract.State.CANCELLED
                        Contract.Error.PROCESS_INTERRUPTED -> Contract.State.INTERRUPTED
                        else -> Contract.State.FAILED
                    }
                    try { jobs.transition(id, state, reason) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                }
                throw SnapshotRescanStore.Rejected(reason).also { it.initCause(error) }
            }
        }

    private fun scalar(db: SQLiteDatabase, sql: String, args: Array<String>, signal: CancellationSignal): Long =
        db.rawQuery(sql, args, signal).use { check(it.moveToFirst()); it.getLong(0) }

    private fun target(db: SQLiteDatabase, job: SnapshotRescanStore.Job, signal: CancellationSignal): Target {
        var value: Target? = null
        val current = db.rawQuery("""SELECT p.sid,p.revision,s.revision,s.provisional,s.state,p.retained,s.nodes,s.metrics,
            n.parent,n.depth,n.name,n.name_key,n.path,n.device,n.mount,n.kind
            FROM snapshots p JOIN sessions s ON s.id=p.sid LEFT JOIN nodes n ON n.sid=p.sid AND n.id=? WHERE p.id=?""",
            arrayOf(job.targetNode.toString(), job.snapshotId), signal).use { c ->
            if (!c.moveToFirst()) null else {
                if (c.getString(0) != job.sessionId) reject(Contract.Error.SNAPSHOT_UNAVAILABLE)
                if (!c.isNull(15)) value = Target(c.getLong(8), c.getInt(9), c.getBlob(10), c.getString(11), c.getBlob(12),
                    c.getBlob(13), c.getLong(14), JSONObject(c.getString(7)).optLong("rootFilesystemType"), c.getLong(6))
                Contract.Target(c.getLong(1), c.getLong(1) == c.getLong(2) && c.getInt(3) == 0 && c.getString(4) in setOf("completed", "partial"),
                    c.getInt(5) == 1, !c.isNull(15) && c.getInt(15) == 1, nodeExists = !c.isNull(15))
            }
        }
        val ordinary = scalar(db, "SELECT COUNT(*) FROM sessions WHERE state IN ('running','finalizing')", emptyArray(), signal) != 0L
        Contract.admissionError(Contract.Request(job.snapshotId, job.targetNode, job.expectedRevision), current, ordinary)?.let { reject(it) }
        val result = requireNotNull(value)
        if (!result.path.contentEquals(job.targetPath)) reject(Contract.Error.STALE_REVISION)
        if (result.device.size != 8 || result.mount <= 0 || result.filesystem == 0L) reject(Contract.Error.INCOMPATIBLE_IDENTITY)
        return result
    }

    private fun validateStage(db: SQLiteDatabase, job: SnapshotRescanStore.Job, target: Target, signal: CancellationSignal, check: () -> Unit) {
        if (job.stagingCleaned) reject(Contract.Error.PROTOCOL_ERROR)
        val summary = JSONObject(job.summary)
        val counts = db.rawQuery("SELECT COUNT(*),COALESCE(MIN(id),0),COALESCE(MAX(id),0),COALESCE(MAX(depth),0) FROM rescan_nodes WHERE task_id=?",
            arrayOf(job.id), signal).use { it.moveToFirst(); LongArray(4) { column -> it.getLong(column) } }
        val issues = scalar(db, "SELECT COUNT(*) FROM rescan_issues WHERE task_id=?", arrayOf(job.id), signal)
        if (counts[0] != job.nodes || summary.getLong("nodes") != job.nodes || issues != job.issues || summary.getLong("issues") != issues ||
            job.nodes !in 0L..2_000_000L || (job.nodes > 0 && (counts[1] != 1L || counts[2] != job.nodes))) reject(Contract.Error.PROTOCOL_ERROR)
        val live = db.rawQuery("SELECT kind,valid FROM rescan_nodes WHERE task_id=? AND id=1", arrayOf(job.id), signal).use {
            when { !it.moveToFirst() || it.getInt(1) != 1 -> Contract.LiveTarget.MISSING
                it.getInt(0) != 1 -> Contract.LiveTarget.OTHER
                else -> Contract.LiveTarget.DIRECTORY }
        }
        val incomplete = scalar(db, """SELECT EXISTS(SELECT 1 FROM rescan_nodes WHERE task_id=?
            AND (valid<>1 OR complete<>1 OR skip<>0 OR (kind=1 AND enumerated<>1)))""", arrayOf(job.id), signal) != 0L
        Contract.resultError(live, summary.getString("state"), !incomplete && job.nodes > 0, issues)?.let { reject(it) }
        if (counts[3] + target.depth > 512) reject(Contract.Error.INCOMPLETE_SCAN)
        if (summary.getLong("rootMountId") != target.mount || summary.getLong("rootFilesystemType") != target.filesystem) reject(Contract.Error.INCOMPATIBLE_IDENTITY)
        val oldCount = scalar(db, "SELECT COUNT(*) FROM nodes WHERE sid=?", arrayOf(job.sessionId), signal)
        val maxId = scalar(db, "SELECT COALESCE(MAX(id),0) FROM nodes WHERE sid=?", arrayOf(job.sessionId), signal)
        val high = scalar(db, "SELECT node_id_high_water FROM sessions WHERE id=?", arrayOf(job.sessionId), signal)
        if (oldCount != target.nodes || maxId > high) reject(Contract.Error.PROTOCOL_ERROR)
        var after = 0L
        val nonInteger = listOf("parent", "depth", "kind", "mode", "uid", "gid", "mount", "nlink", "size", "allocated",
            "seconds", "nanos", "valid", "complete", "enumerated", "skip").joinToString(" OR ") { "typeof(n.$it)<>'integer'" }
        while (after < job.nodes) {
            check()
            val last = minOf(after + 4096, job.nodes)
            val bad = db.rawQuery("""SELECT n.id FROM rescan_nodes n LEFT JOIN rescan_nodes p ON p.task_id=n.task_id AND p.id=n.parent
                WHERE n.task_id=? AND n.id>? AND n.id<=? AND (
                    $nonInteger OR typeof(n.name)<>'blob' OR typeof(n.path)<>'blob' OR typeof(n.link)<>'blob' OR
                    typeof(n.device)<>'blob' OR length(n.device)<>8 OR typeof(n.inode)<>'blob' OR length(n.inode)<>8 OR
                    n.device<>X'${target.device.hex()}' OR n.mount<>? OR n.kind NOT BETWEEN 1 AND 4 OR
                    n.depth NOT BETWEEN 0 AND 512 OR n.size<0 OR n.allocated<0 OR n.nlink<0 OR n.nanos NOT BETWEEN 0 AND 999999999 OR
                    length(n.name)>255 OR length(n.link)>4096 OR length(n.path)>131072 OR n.name_key<>lower(hex(n.name)) OR
                    instr(n.name,X'00')>0 OR instr(n.name,X'2f')>0 OR n.name IN (X'2e',X'2e2e') OR
                    (n.id=1 AND (n.parent<>0 OR n.depth<>0 OR length(n.name)<>0 OR length(n.path)<>0)) OR
                    (n.id<>1 AND (p.id IS NULL OR p.kind<>1 OR n.parent>=n.id OR length(n.name)=0 OR n.depth<>p.depth+1 OR
                        n.path<>CASE WHEN length(p.path)=0 THEN n.name ELSE CAST(p.path||X'2f'||n.name AS BLOB) END))) LIMIT 1""",
                arrayOf(job.id, after.toString(), last.toString(), target.mount.toString()), signal).use { it.moveToFirst() }
            if (bad) reject(Contract.Error.PROTOCOL_ERROR)
            after = last
        }
    }

    private fun createTemporaryTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TEMP TABLE rescan_members(seq INTEGER PRIMARY KEY,id INTEGER NOT NULL UNIQUE,kind INTEGER NOT NULL)")
        db.execSQL("CREATE TEMP TABLE rescan_mapping(staged_id INTEGER PRIMARY KEY,new_id INTEGER NOT NULL UNIQUE)")
    }

    private fun collectMembers(db: SQLiteDatabase, job: SnapshotRescanStore.Job, signal: CancellationSignal, check: () -> Unit): Long {
        db.execSQL("INSERT INTO temp.rescan_members(id,kind) VALUES(?,1)", arrayOf(job.targetNode))
        var after = 0L
        db.compileStatement("INSERT INTO temp.rescan_members(id,kind) VALUES(?,?)").use { insert ->
            while (true) {
                check()
                // Only directories can extend the frontier. Empty file-only pages otherwise rerun a
                // parent lookup against the historical index for every 256 leaf nodes.
                val last = scalar(db, "SELECT COALESCE(MAX(seq),?) FROM (SELECT seq FROM temp.rescan_members WHERE kind=1 AND seq>? ORDER BY seq LIMIT 256)",
                    arrayOf(after.toString(), after.toString()), signal)
                if (last == after) break
                var parent = 0L; var child = 0L
                while (true) {
                    check(); var rows = 0
                    db.rawQuery("""SELECT n.parent,n.id,n.kind FROM nodes n INDEXED BY node_parent WHERE n.sid=?
                        AND n.parent IN (SELECT id FROM temp.rescan_members WHERE seq>? AND seq<=? AND kind=1)
                        AND (n.parent,n.id)>(CAST(? AS INTEGER),CAST(? AS INTEGER)) ORDER BY n.parent,n.id LIMIT 4096""",
                        arrayOf(job.sessionId, after.toString(), last.toString(), parent.toString(), child.toString()), signal).use { c ->
                        while (c.moveToNext()) {
                            check(); parent = c.getLong(0); child = c.getLong(1)
                            insert.bindLong(1, child); insert.bindLong(2, c.getLong(2)); insert.executeInsert(); rows++
                        }
                    }
                    if (rows < 4096) break
                }
                after = last
            }
        }
        return scalar(db, "SELECT COUNT(*) FROM temp.rescan_members", emptyArray(), signal)
    }

    private fun createMapping(db: SQLiteDatabase, job: SnapshotRescanStore.Job, first: Long, signal: CancellationSignal, check: () -> Unit) {
        db.execSQL("INSERT INTO temp.rescan_mapping VALUES(1,?)", arrayOf(job.targetNode))
        var after = 1L
        while (after < job.nodes) {
            check(); signal.throwIfCanceled(); val last = minOf(after + 4096, job.nodes)
            db.execSQL("INSERT INTO temp.rescan_mapping SELECT id,?+(id-2) FROM rescan_nodes WHERE task_id=? AND id>? AND id<=?",
                arrayOf<Any>(first, job.id, after, last))
            after = last
        }
    }

    private fun replaceNodes(db: SQLiteDatabase, job: SnapshotRescanStore.Job, target: Target, signal: CancellationSignal,
        check: () -> Unit, point: (Point) -> Unit) {
        var after = 0L
        while (true) {
            check()
            val last = scalar(db, "SELECT COALESCE(MAX(id),?) FROM (SELECT id FROM temp.rescan_members WHERE id>? ORDER BY id LIMIT 4096)",
                arrayOf(after.toString(), after.toString()), signal)
            if (last == after) break
            db.execSQL("DELETE FROM issues WHERE sid=? AND node IN (SELECT id FROM temp.rescan_members WHERE id>? AND id<=?)",
                arrayOf<Any>(job.sessionId, after, last))
            db.execSQL("DELETE FROM nodes WHERE sid=? AND id IN (SELECT id FROM temp.rescan_members WHERE id>? AND id<=?)",
                arrayOf<Any>(job.sessionId, after, last))
            after = last
        }
        if (job.targetNode == 1L) db.execSQL("DELETE FROM issues WHERE sid=? AND node=0", arrayOf(job.sessionId))
        point(Point.OLD_REMOVED)
        db.compileStatement("""INSERT INTO nodes
            SELECT ?,m.new_id,CASE WHEN n.id=1 THEN ? ELSE p.new_id END,?+n.depth,
                CASE WHEN n.id=1 THEN ? ELSE n.name END,CASE WHEN n.id=1 THEN ? ELSE n.name_key END,
                CASE WHEN n.id=1 THEN ? WHEN length(?)=0 THEN n.path ELSE CAST(?||X'2f'||n.path AS BLOB) END,
                n.link,n.kind,n.mode,n.uid,n.gid,n.device,n.inode,n.mount,n.nlink,n.size,n.allocated,n.seconds,n.nanos,
                n.valid,n.complete,n.enumerated,n.skip FROM rescan_nodes n JOIN temp.rescan_mapping m ON m.staged_id=n.id
                LEFT JOIN temp.rescan_mapping p ON p.staged_id=n.parent WHERE n.task_id=? AND n.id>? AND n.id<=?""").use { insert ->
            after = 0L
            while (after < job.nodes) {
                check(); val last = minOf(after + 4096, job.nodes)
                insert.bindAll(listOf(job.sessionId, target.parent, target.depth, target.name, target.nameKey,
                    target.path, target.path, target.path, job.id, after, last)); insert.executeInsert()
                after = last
            }
        }
        point(Point.NODES_INSERTED)
    }

    private fun publish(db: SQLiteDatabase, job: SnapshotRescanStore.Job, signal: CancellationSignal, metrics: JSONObject, check: () -> Unit) {
        val count = scalar(db, "SELECT COUNT(*) FROM nodes WHERE sid=?", arrayOf(job.sessionId), signal)
        val issues = scalar(db, "SELECT COUNT(*) FROM issues WHERE sid=?", arrayOf(job.sessionId), signal)
        val root = db.rawQuery("SELECT logical,allocated,complete FROM aggregates WHERE sid=? AND id=1", arrayOf(job.sessionId), signal).use {
            check(it.moveToFirst()); longArrayOf(it.getLong(0), it.getLong(1), it.getLong(2))
        }
        val revision = job.expectedRevision + 1
        db.execSQL("UPDATE sessions SET revision=?,nodes=?,issues=?,state=?,phase='final',reason='',provisional=0 WHERE id=?",
            arrayOf<Any>(revision, count, issues, if (root[2] == 1L && issues == 0L) "completed" else "partial", job.sessionId))
        db.execSQL("UPDATE snapshots SET revision=?,logical=?,allocated=?,complete=? WHERE id=?",
            arrayOf<Any>(revision, root[0], root[1], root[2], job.snapshotId))
        store.snapshots.invalidateForRescan(db, check)
        store.rescans.recordCommit(db, job.id, revision, metrics)
    }
}
