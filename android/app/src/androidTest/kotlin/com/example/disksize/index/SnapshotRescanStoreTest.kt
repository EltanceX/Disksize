package com.example.disksize.index

import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.disksize.scan.SnapshotRescanContract as Contract
import com.example.disksize.scan.NativeBridge
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class SnapshotRescanStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun uuid() = UUID.randomUUID().toString()
    private fun identity(n: Long) = ByteBuffer.allocate(8).putLong(n).array()

    @Before fun requiresNewNormalMainApk() {
        val config = Class.forName("com.example.disksize.BuildConfig")
        assertFalse(config.getField("SCAN_BENCHMARK").getBoolean(null))
        // Do not allow an old installed main APK to pass tests through inlined schema constants.
        Class.forName("com.example.disksize.index.SnapshotRescanStore")
    }

    private fun withStore(block: (IndexStore) -> Unit) {
        val name = "rescan-store-${uuid()}.db"
        try { IndexStore(context, name).use(block) }
        finally { SQLiteDatabase.deleteDatabase(File(context.noBackupFilesDir, name)) }
    }

    private fun node(id: Long, parent: Long, name: ByteArray = byteArrayOf(), directory: Boolean = false, depth: Int = 1) =
        IndexNode(id, parent, name, byteArrayOf(), if (directory) 1 else 2, if (parent == 0L) 0 else depth,
            if (directory) 16832 else 33152, 10000, 10000, identity(1), identity(id), 1, 1, if (directory) 0 else 7,
            4096, 123, 456, true, true, 0)

    private fun fixture(store: IndexStore): String {
        val id = uuid()
        store.begin(id, "/rescan-persistence-fixture", "ordinary", Process.myUid(), Process.myPid()).use {
            it.node(node(1, 0, directory = true))
            it.node(node(2, 1, byteArrayOf(0xff.toByte(), 10), true))
            it.node(node(3, 2, "child".toByteArray(), depth = 2))
            it.node(node(4, 1, "outside".toByteArray()))
            it.directory(2, true, true, 0); it.directory(1, true, true, 0); it.flush()
        }
        store.finalize(id, StreamSummary("completed", 4, 0, 0, 2, 0, 1, 1)) { false }
        return id
    }

    private fun scalar(db: SQLiteDatabase, sql: String, vararg args: String): Long = db.rawQuery(sql, args).use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }
    private fun revision(store: IndexStore, id: String) = scalar(store.readableDatabase, "SELECT revision FROM snapshots WHERE id=?", id)
    private fun create(store: IndexStore, snapshot: String) = store.rescans.create(uuid(), Contract.Request(snapshot, 2, revision(store, snapshot)))
    private fun call(store: IndexStore, kind: String, id: String? = null): JSONObject {
        val query = JSONObject().put("kind", kind)
        if (id != null) query.put("snapshotId", id).put("retained", true)
        return JSONObject(IndexQueries(store).query(query.toString()))
    }
    private fun staging(store: IndexStore, job: SnapshotRescanStore.Job, issue: Boolean = false) {
        store.rescans.beginStaging(job.id).use {
            it.node(node(1, 0, directory = true)); it.node(node(2, 1, byteArrayOf(0xfe.toByte(), 10)))
            it.directory(1, true, !issue, 0)
            if (issue) it.issue(2, 1, 13, "open")
            it.volume(1000, 400, 300, 123)
            it.finish(StreamSummary(if (issue) "partial" else "completed", 2, if (issue) 1 else 0, 42, 2, 0, 1, 1))
        }
    }
    private inline fun <reified T : Throwable> rejects(block: () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") } catch (e: Throwable) { if (e !is T) throw e }
    }
    private fun rejected(reason: Contract.Error, block: () -> Unit) {
        try { block(); fail("Expected ${reason.wireName}") } catch (e: SnapshotRescanStore.Rejected) { assertEquals(reason, e.reason) }
    }
    private fun transaction(db: SQLiteDatabase, commit: Boolean = true, block: () -> Unit) {
        db.beginTransactionNonExclusive()
        try { block(); if (commit) db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun publishMarker(store: IndexStore, job: SnapshotRescanStore.Job, commit: Boolean = true) {
        val db = store.writableDatabase
        transaction(db, commit) {
            store.rescans.allocateNodeIds(db, job.sessionId, 3)
            db.execSQL("UPDATE sessions SET revision=? WHERE id=?", arrayOf<Any>(job.expectedRevision + 1, job.sessionId))
            db.execSQL("UPDATE snapshots SET revision=? WHERE id=?", arrayOf<Any>(job.expectedRevision + 1, job.snapshotId))
            assertTrue(store.rescans.recordCommit(db, job.id, job.expectedRevision + 1, JSONObject().put("fixture", true)))
        }
    }

    @Test fun stagingPersistsRawMetadataButIsInvisibleToPublicListsAndSnapshot() = withStore { store ->
        val id = fixture(store); val old = SnapshotBaseline.capture(store, id); val rev = revision(store, id)
        val job = create(store, id)
        assertArrayEquals(byteArrayOf(0xff.toByte(), 10), job.targetPath)
        assertEquals(id, job.sessionId); assertEquals(Contract.State.PREPARING, job.state)
        staging(store, job, issue = true)
        store.close()
        val saved = store.rescans.get(job.id)!!
        assertEquals(Contract.State.VALIDATING, saved.state); assertEquals(2L, saved.nodes); assertEquals(1L, saved.issues)
        assertTrue(saved.scanned >= saved.started && saved.started >= saved.created)
        assertEquals(123, JSONObject(saved.volume).getLong("capturedAtMillis"))
        assertEquals("partial", JSONObject(saved.summary).getString("state"))
        store.readableDatabase.rawQuery("SELECT path,device,inode,seconds,nanos FROM rescan_nodes WHERE task_id=? AND id=2", arrayOf(job.id)).use {
            assertTrue(it.moveToFirst()); assertArrayEquals(byteArrayOf(0xfe.toByte(), 10), it.getBlob(0))
            assertArrayEquals(identity(1), it.getBlob(1)); assertArrayEquals(identity(2), it.getBlob(2))
            assertEquals(123L, it.getLong(3)); assertEquals(456L, it.getLong(4))
        }
        assertEquals(1, call(store, "snapshot-list").getJSONArray("snapshots").length())
        assertEquals(1, call(store, "recent").getJSONArray("sessions").length())
        assertEquals(old, SnapshotBaseline.capture(store, id)); assertEquals(rev, revision(store, id))
        assertEquals(0L, scalar(store.readableDatabase, "SELECT local_updates FROM snapshots WHERE id=?", id))
        assertEquals("busy", call(store, "snapshot-delete", id).getString("status"))
        assertEquals("busy", call(store, "snapshot-retain", id).getString("status"))
    }

    @Test fun admissionSerializesWritersAndRejectsInvalidOrStaleTargets() = withStore { store ->
        val id = fixture(store); val rev = revision(store, id)
        rejected(Contract.Error.INVALID_REQUEST) { store.rescans.create(uuid(), Contract.Request(id, 0, rev)) }
        rejected(Contract.Error.STALE_REVISION) { store.rescans.create(uuid(), Contract.Request(id, 2, rev - 1)) }
        rejected(Contract.Error.NODE_NOT_FOUND) { store.rescans.create(uuid(), Contract.Request(id, 999, rev)) }
        rejected(Contract.Error.NOT_DIRECTORY) { store.rescans.create(uuid(), Contract.Request(id, 3, rev)) }
        val job = create(store, id)
        rejected(Contract.Error.BUSY) { create(store, id) }
        rejects<IllegalStateException> { store.begin(uuid(), "/unused", "ordinary", 10000, 10000) }
        store.rescans.transition(job.id, Contract.State.FAILED, Contract.Error.ROOT_UNAVAILABLE)
        call(store, "snapshot-retain", id)
        rejected(Contract.Error.RETAINED) { create(store, id) }
        store.writableDatabase.execSQL("UPDATE snapshots SET retained=0 WHERE id=?", arrayOf(id))
        store.begin(uuid(), "/unfinished", "ordinary", 10000, 10000).close()
        rejected(Contract.Error.BUSY) { create(store, id) }
    }

    @Test fun uniqueActiveSlotAlsoRejectsBypassingAdmission() = withStore { store ->
        val id = fixture(store); create(store, id)
        rejects<android.database.sqlite.SQLiteConstraintException> {
            store.writableDatabase.execSQL("""INSERT INTO rescan_jobs(id,snapshot_id,sid,target_node,target_path,expected_revision,state,created)
                VALUES(?,?,?,2,X'ff0a',?,'preparing',1)""", arrayOf<Any>(uuid(), id, id, revision(store, id)))
        }
    }

    @Test fun nativeProducerUsesTheExistingProtocolAndConsumerForIsolatedStaging() = withStore { store ->
        val id = fixture(store); val old = SnapshotBaseline.capture(store, id); val job = create(store, id)
        val root = File(context.filesDir, "rescan-store-native-${uuid()}")
        check(root.mkdir())
        try {
            File(root, "child").writeText("seven!!")
            val handle = NativeBridge.nativeCreateScan(root.path.toByteArray())
            try {
                store.rescans.beginStaging(job.id).use {
                    val summary = LocalScanPipe.run(handle, job.id, it) { _, _, _ -> }
                    assertEquals("completed", summary.state); assertEquals(2L, summary.nodes)
                    it.finish(summary)
                }
            } finally { NativeBridge.nativeReleaseScan(handle) }
            assertEquals(Contract.State.VALIDATING, store.rescans.get(job.id)!!.state)
            assertEquals(7L, scalar(store.readableDatabase, "SELECT size FROM rescan_nodes WHERE task_id=? AND id=2", job.id))
            assertEquals(1L, scalar(store.readableDatabase, "SELECT enumerated FROM rescan_nodes WHERE task_id=? AND id=1", job.id))
            assertEquals(old, SnapshotBaseline.capture(store, id))
        } finally { root.deleteRecursively() }
    }

    @Test fun stagingBatchesAndParentCacheEvictionPreserveCommittedRowsOnly() = withStore { store ->
        val id = fixture(store); val old = SnapshotBaseline.capture(store, id); val job = create(store, id)
        store.rescans.beginStaging(job.id).use { writer ->
            writer.node(node(1, 0, directory = true))
            // More than 32 directory paths forces the bounded cache's SQL fallback for root.
            for (n in 2L..42L) writer.node(node(n, n - 1, "d".toByteArray(), directory = true, depth = (n - 1).toInt()))
            for (n in 43L..4200L) writer.node(node(n, 1, "file-$n".toByteArray()))
            writer.flush(IndexStore.FlushReason.SUMMARY)
            assertEquals(4200L, store.rescans.get(job.id)!!.nodes)
            writer.node(node(4201, 1, "rollback".toByteArray()))
            rejects<IllegalStateException> { store.rescans.requestCancellation(job.id) }
        }
        assertEquals(4200L, scalar(store.readableDatabase, "SELECT COUNT(*) FROM rescan_nodes WHERE task_id=?", job.id))
        assertEquals(4200L, store.rescans.get(job.id)!!.nodes)
        store.rescans.recover()
        assertEquals(old, SnapshotBaseline.capture(store, id))
    }

    @Test fun terminalStatesCannotReopenAndCompletionRequiresDurableMarker() = withStore { store ->
        val id = fixture(store)
        for ((state, error) in listOf(Contract.State.FAILED to Contract.Error.STORAGE_ERROR,
            Contract.State.CANCELLED to Contract.Error.CANCELLED, Contract.State.STALE to Contract.Error.STALE_REVISION)) {
            val job = create(store, id)
            rejects<IllegalArgumentException> { store.rescans.transition(job.id, Contract.State.COMPLETED) }
            rejects<android.database.sqlite.SQLiteConstraintException> {
                store.writableDatabase.execSQL("UPDATE rescan_jobs SET state='completed' WHERE id=?", arrayOf(job.id))
            }
            store.rescans.transition(job.id, state, error)
            rejects<IllegalArgumentException> { store.rescans.transition(job.id, Contract.State.SCANNING) }
            store.rescans.recover()
            assertEquals(state, store.rescans.get(job.id)!!.state)
        }
    }

    @Test fun recoveryInterruptsEveryUncommittedPhaseWithoutTouchingOldSnapshot() = withStore { store ->
        val id = fixture(store); val old = SnapshotBaseline.capture(store, id); val rev = revision(store, id)
        for (phase in listOf(Contract.State.PREPARING, Contract.State.SCANNING, Contract.State.VALIDATING, Contract.State.APPLYING)) {
            val job = create(store, id)
            if (phase == Contract.State.SCANNING) store.rescans.beginStaging(job.id).use {
                it.node(node(1, 0, directory = true)); it.flush(IndexStore.FlushReason.SUMMARY)
            }
            if (phase in setOf(Contract.State.VALIDATING, Contract.State.APPLYING)) staging(store, job)
            if (phase == Contract.State.APPLYING) store.rescans.transition(job.id, phase)
            store.close(); store.recover(); store.recover()
            val recovered = store.rescans.get(job.id)!!
            assertEquals(Contract.State.INTERRUPTED, recovered.state); assertEquals("process-interrupted", recovered.error)
            assertTrue(recovered.ended > 0); assertTrue(recovered.stagingCleaned)
            assertEquals(0L, scalar(store.readableDatabase, "SELECT COUNT(*) FROM rescan_nodes WHERE task_id=?", job.id))
            assertEquals(old, SnapshotBaseline.capture(store, id)); assertEquals(rev, revision(store, id))
        }
    }

    @Test fun cancellationIsRequestedUntilWorkerClosesAndCleansStaging() = withStore { store ->
        val id = fixture(store); val old = SnapshotBaseline.capture(store, id); val job = create(store, id)
        store.rescans.beginStaging(job.id).use {
            it.node(node(1, 0, directory = true)); it.flush(IndexStore.FlushReason.SUMMARY)
            assertEquals(Contract.Cancellation.REQUESTED, store.rescans.requestCancellation(job.id))
            assertEquals(Contract.State.SCANNING, store.rescans.get(job.id)!!.state)
            rejects<CancellationException> { it.node(node(2, 1, "late".toByteArray())) }
        }
        store.rescans.transition(job.id, Contract.State.CANCELLED, Contract.Error.CANCELLED)
        assertEquals(Contract.Cancellation.ALREADY_TERMINAL, store.rescans.requestCancellation(job.id))
        store.rescans.recover()
        assertEquals(Contract.State.CANCELLED, store.rescans.get(job.id)!!.state)
        assertEquals(old, SnapshotBaseline.capture(store, id))
    }

    @Test fun failedBatchRollsBackAndCleanupIsOwnedByOneTask() = withStore { store ->
        val id = fixture(store); val job = create(store, id)
        store.rescans.beginStaging(job.id).use {
            it.node(node(1, 0, directory = true)); it.flush(IndexStore.FlushReason.SUMMARY)
            it.node(node(2, 1, "same".toByteArray()))
            rejects<android.database.sqlite.SQLiteConstraintException> { it.node(node(3, 1, "same".toByteArray())) }
        }
        assertEquals(1L, store.rescans.get(job.id)!!.nodes)
        assertEquals(1L, scalar(store.readableDatabase, "SELECT COUNT(*) FROM rescan_nodes WHERE task_id=?", job.id))
        store.rescans.transition(job.id, Contract.State.FAILED, Contract.Error.PROTOCOL_ERROR)
        val other = create(store, id); staging(store, other, issue = true)
        store.rescans.cleanStaging(job.id); store.rescans.cleanStaging(job.id)
        assertEquals(2L, scalar(store.readableDatabase, "SELECT COUNT(*) FROM rescan_nodes WHERE task_id=?", other.id))
        assertEquals(1L, scalar(store.readableDatabase, "SELECT COUNT(*) FROM rescan_issues WHERE task_id=?", other.id))
        rejects<IllegalStateException> { store.rescans.cleanStaging(other.id) }
        assertEquals("protocol-error", store.rescans.get(job.id)!!.error)
    }

    @Test fun watermarkReservationsAreMonotonicSparseOverflowCheckedAndTransactional() = withStore { store ->
        val id = fixture(store); val db = store.writableDatabase
        assertEquals(4L, scalar(db, "SELECT node_id_high_water FROM sessions WHERE id=?", id))
        rejects<IllegalArgumentException> { store.rescans.allocateNodeIds(db, id, 1) }
        transaction(db) { assertEquals(5L..7L, store.rescans.allocateNodeIds(db, id, 3)) }
        transaction(db, commit = false) { assertEquals(8L..9L, store.rescans.allocateNodeIds(db, id, 2)) }
        transaction(db) { assertEquals(8L..8L, store.rescans.allocateNodeIds(db, id, 1)) }
        db.execSQL("UPDATE sessions SET node_id_high_water=? WHERE id=?", arrayOf<Any>(Long.MAX_VALUE, id))
        transaction(db) {
            assertTrue(store.rescans.allocateNodeIds(db, id, 0).isEmpty())
            rejects<ArithmeticException> { store.rescans.allocateNodeIds(db, id, 1) }
        }
        assertEquals(Long.MAX_VALUE, scalar(db, "SELECT node_id_high_water FROM sessions WHERE id=?", id))
    }

    @Test fun commitMarkerMetadataAndWatermarkRollbackTogetherAndReplayIsIdempotent() = withStore { store ->
        val id = fixture(store); val other = fixture(store); val untouched = SnapshotBaseline.capture(store, other)
        val job = create(store, id); staging(store, job); store.rescans.transition(job.id, Contract.State.APPLYING)
        val db = store.writableDatabase
        val times = scalar(db, "SELECT created FROM snapshots WHERE id=?", id) to scalar(db, "SELECT ended FROM sessions WHERE id=?", id)
        rejects<IllegalArgumentException> { store.rescans.recordCommit(db, job.id, job.expectedRevision + 1) }
        transaction(db) { rejects<IllegalStateException> { store.rescans.recordCommit(db, job.id, job.expectedRevision + 1) } }
        publishMarker(store, job, commit = false)
        assertNull(store.rescans.get(job.id)!!.committedRevision)
        assertEquals(job.expectedRevision, revision(store, id))
        assertEquals(0L, scalar(db, "SELECT local_updates FROM snapshots WHERE id=?", id))
        assertEquals(4L, scalar(db, "SELECT node_id_high_water FROM sessions WHERE id=?", id))
        publishMarker(store, job)
        assertTrue(JSONObject(store.rescans.get(job.id)!!.metrics).has("importMicros"))
        assertTrue(JSONObject(store.rescans.get(job.id)!!.metrics).getBoolean("fixture"))
        transaction(db) { assertFalse(store.rescans.recordCommit(db, job.id, job.expectedRevision + 1)) }
        assertEquals(1L, scalar(db, "SELECT local_updates FROM snapshots WHERE id=?", id))
        assertTrue(scalar(db, "SELECT local_updated FROM snapshots WHERE id=?", id) > 0)
        assertEquals(times.first, scalar(db, "SELECT created FROM snapshots WHERE id=?", id))
        assertEquals(times.second, scalar(db, "SELECT ended FROM sessions WHERE id=?", id))
        assertEquals(Contract.Cancellation.ALREADY_COMPLETED, store.rescans.requestCancellation(job.id))
        // Simulate loss of a terminal publication after the durable merge marker.
        db.execSQL("UPDATE rescan_jobs SET state='applying' WHERE id=?", arrayOf(job.id))
        store.close(); store.recover(); store.recover()
        assertEquals(Contract.State.COMPLETED, store.rescans.get(job.id)!!.state)
        assertEquals(job.expectedRevision + 1, store.rescans.get(job.id)!!.committedRevision)
        assertTrue(store.rescans.get(job.id)!!.stagingCleaned)
        assertEquals(1L, scalar(store.readableDatabase, "SELECT local_updates FROM snapshots WHERE id=?", id))
        assertEquals(untouched, SnapshotBaseline.capture(store, other))
    }

    @Test fun completedTaskHistoryCascadesOnlyWhenItsSnapshotIsDeleted() = withStore { store ->
        val id = fixture(store); val other = fixture(store); val job = create(store, id)
        staging(store, job); store.rescans.transition(job.id, Contract.State.APPLYING); publishMarker(store, job)
        val second = create(store, other); staging(store, second)
        assertEquals("ok", call(store, "snapshot-delete", id).getString("status"))
        assertNull(store.rescans.get(job.id)); assertNotNull(store.rescans.get(second.id))
        assertEquals(2L, scalar(store.readableDatabase, "SELECT COUNT(*) FROM rescan_nodes WHERE task_id=?", second.id))
        assertEquals(0L, scalar(store.readableDatabase, "SELECT COUNT(*) FROM rescan_nodes WHERE task_id=?", job.id))
        assertEquals(0L, scalar(store.readableDatabase, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    @Test fun allLegacySchemasPreserveEveryExistingColumnAndHistoricalBrowsing() {
        for (version in 1..4) {
            val name = "rescan-migrate-$version-${uuid()}.db"; val file = File(context.noBackupFilesDir, name); val id = uuid()
            val columns = mutableMapOf<String, List<String>>()
            fun rows(db: SQLiteDatabase): Map<String, List<List<String?>>> = columns.mapValues { (table, names) ->
                db.rawQuery("SELECT ${names.joinToString(",")} FROM $table ORDER BY 1,2", null).use { c ->
                    buildList { while (c.moveToNext()) add((0 until c.columnCount).map { index ->
                        if (c.isNull(index)) null else if (c.getType(index) == android.database.Cursor.FIELD_TYPE_BLOB) c.getBlob(index).hex() else c.getString(index)
                    }) }
                }
            }
            try {
                val before = SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                    val sql = InstrumentationRegistry.getInstrumentation().context.assets.open("index-v2.sql").bufferedReader().use { it.readText() }
                    for (statement in sql.split(';')) if (statement.trim().isNotEmpty()) db.execSQL(statement)
                    if (version == 1) db.execSQL("DROP INDEX aggregate_incomplete")
                    if (version >= 3) {
                        db.execSQL("ALTER TABLE sessions ADD COLUMN volume TEXT NOT NULL DEFAULT '{}'")
                        db.execSQL("CREATE INDEX sessions_recent ON sessions(started DESC,id DESC)")
                    }
                    if (version == 4) {
                        db.execSQL("ALTER TABLE sessions ADD COLUMN identity TEXT NOT NULL DEFAULT '{}'")
                        db.execSQL("ALTER TABLE snapshots ADD COLUMN retained INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("CREATE TABLE diff_cache(id INTEGER PRIMARY KEY CHECK(id=1), signature TEXT NOT NULL, ready INTEGER NOT NULL)")
                        db.execSQL("""CREATE TABLE diff_rows(path BLOB PRIMARY KEY, old_kind INTEGER NOT NULL, new_kind INTEGER NOT NULL,
                            old_logical INTEGER,new_logical INTEGER,old_allocated INTEGER,new_allocated INTEGER,status TEXT NOT NULL,
                            logical_delta INTEGER,allocated_delta INTEGER,row_id INTEGER NOT NULL UNIQUE) WITHOUT ROWID""")
                        db.execSQL("CREATE INDEX diff_logical ON diff_rows(COALESCE(logical_delta,-9223372036854775808) DESC,path DESC)")
                        db.execSQL("CREATE INDEX diff_allocated ON diff_rows(COALESCE(allocated_delta,-9223372036854775808) DESC,path DESC)")
                        db.execSQL("CREATE INDEX diff_status ON diff_rows(status,path)")
                        db.execSQL("INSERT INTO diff_cache VALUES(1,'historical',1)")
                        db.execSQL("INSERT INTO diff_rows VALUES(X'ff0a',0,2,0,7,0,4096,'added',7,4096,1)")
                    }
                    db.execSQL("""INSERT INTO sessions(id,root,backend,state,phase,revision,provisional,started,ended,nodes,issues,scanner_uid,scanner_pid)
                        VALUES(?,'/old','ordinary','completed','final',9,0,100,200,2,1,10000,10000)""", arrayOf(id))
                    for ((node, parent) in listOf(1L to 0L, 37L to 1L)) {
                        val path = if (node == 1L) "" else "ff0a"
                        db.execSQL("""INSERT INTO nodes VALUES(?,?,$parent,${if (parent == 0L) 0 else 1},X'$path','$path',X'$path',X'',
                            ${if (node == 1L) 1 else 2},16832,10000,10000,X'0100000000000000',X'0200000000000000',1,1,7,4096,123,456,1,1,1,0)""", arrayOf<Any>(id, node))
                        db.execSQL("INSERT INTO aggregates VALUES(?,?,$parent,${if (parent == 0L) 0 else 1},${if (node == 1L) 1 else 2},'$path',?,7,4096,7,4096,1)", arrayOf<Any>(id, node, node))
                    }
                    db.execSQL("INSERT INTO issues(sid,node,code,errno,operation) VALUES(?,37,6,116,'historical')", arrayOf(id))
                    db.execSQL("INSERT INTO hardlinks VALUES(?,X'0100000000000000',X'0200000000000000',X'ff0a',0)", arrayOf(id))
                    db.execSQL("INSERT INTO snapshots(id,sid,revision,created,logical,allocated,complete) VALUES(?,?,9,200,7,4096,1)", arrayOf(id, id))
                    if (version >= 3) db.execSQL("UPDATE sessions SET volume='{\"totalBytes\":123}'")
                    if (version == 4) {
                        db.execSQL("UPDATE snapshots SET retained=1")
                        db.execSQL("UPDATE sessions SET identity='{\"rules\":\"historical\"}'")
                    }
                    db.version = version
                    for (table in listOf("sessions", "nodes", "aggregates", "hardlinks", "issues", "snapshots") + if (version == 4) listOf("diff_cache", "diff_rows") else emptyList()) {
                        columns[table] = db.rawQuery("PRAGMA table_info($table)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
                    }
                    rows(db)
                }
                IndexStore(context, name).use { store ->
                    val db = store.writableDatabase
                    assertEquals(5, db.version); assertEquals(before, rows(db))
                    assertEquals(37L, scalar(db, "SELECT node_id_high_water FROM sessions WHERE id=?", id))
                    assertEquals(0L, scalar(db, "SELECT local_updates+local_updated FROM snapshots WHERE id=?", id))
                    assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM rescan_jobs"))
                    val snapshot = call(store, "snapshot-list").getJSONArray("snapshots").getJSONObject(0)
                    assertEquals(id, snapshot.getString("id")); assertTrue(snapshot.getBoolean("available"))
                    assertEquals(version == 4, snapshot.getBoolean("retained"))
                    val children = JSONObject(IndexQueries(store).query(JSONObject().put("kind", "children").put("sessionId", id).put("parentId", 1).toString()))
                    assertEquals("ok", children.getString("status")); assertEquals(37L, children.getJSONArray("entries").getJSONObject(0).getLong("id"))
                    transaction(db) { assertEquals(38L..39L, store.rescans.allocateNodeIds(db, id, 2)) }
                    assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                }
                IndexStore(context, name).use { assertEquals(39L, scalar(it.readableDatabase, "SELECT node_id_high_water FROM sessions WHERE id=?", id)) }
            } finally { SQLiteDatabase.deleteDatabase(file) }
        }
    }
}
