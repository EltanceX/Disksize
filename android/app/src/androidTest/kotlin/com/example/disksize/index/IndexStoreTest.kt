package com.example.disksize.index

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.disksize.BuildConfig
import com.example.disksize.scan.NativeBridge
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

@RunWith(AndroidJUnit4::class)
class IndexStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun withStore(policy: ImportPolicy = ImportPolicy.DEFAULT, finalizer: FinalizePolicy = FinalizePolicy.DEFAULT,
        block: (IndexStore) -> Unit) {
        val name = "phase5-test-${UUID.randomUUID()}.db"
        val store = IndexStore(context, name, policy, finalizer)
        try { block(store) } finally {
            store.close(); SQLiteDatabase.deleteDatabase(File(context.noBackupFilesDir, name))
        }
    }
    @Test fun importKeepsPendingRowsPrivateAndAdvancesCursorOnlyOnCommit() = withStore { store ->
        val older = UUID.randomUUID().toString()
        val current = UUID.randomUUID().toString()
        val uid = Process.myUid()
        val pid = Process.myPid()
        val limit = ImportPolicy.DEFAULT.actionLimit.toLong()
        store.begin(older, "/fixture", "ordinary", uid, pid).use {
            it.node(node(1, 0, "", 0, 1))
            it.node(node(2, 1, "old", 8, 2))
            it.directory(1, true, true, 0)
            it.flush()
        }
        store.finalize(older, StreamSummary("completed", 2, 0, 0, 2, 0, 1, 1)) { false }
        val queries = IndexQueries(store)
        val reader = Executors.newSingleThreadExecutor()
        fun query(kind: String, id: String, cursor: String = ""): JSONObject {
            val request = JSONObject().put("kind", kind).put("sessionId", id)
                .put("parentId", 1).put("sort", "name").put("limit", 2).put("cursor", cursor)
            return reader.submit<JSONObject> { JSONObject(queries.query(request.toString())) }.get(5, TimeUnit.SECONDS)
        }
        try {
            store.begin(current, "/fixture", "ordinary", uid, pid).use { importer ->
                importer.node(node(1, 0, "", 0, 1))
                for (i in 2L..limit) importer.node(node(i, 1, "new-$i", 8, i))
                val page = query("children", current)
                assertEquals("ok", page.getString("status"))
                val token = page.getString("nextCursor")
                assertEquals(limit, page.getLong("nodeCount"))
                assertTrue(page.getBoolean("provisional"))
                importer.node(node(limit + 1, 1, "pending", 8, limit + 1))
                assertEquals("ok", query("children", current, token).getString("status"))
                assertEquals(limit, query("session", current).getLong("nodeCount"))
                assertEquals("6f6c64", query("children", older).getJSONArray("entries").getJSONObject(0).getString("nameHex"))
                importer.flush(IndexStore.FlushReason.PROGRESS)
                assertEquals("stale-cursor", query("children", current, token).getString("status"))
                assertEquals(limit + 1, query("session", current).getLong("nodeCount"))
                importer.node(node(limit + 2, 1, "rollback", 8, limit + 2))
            }
            store.recover()
            val recovered = query("session", current)
            assertEquals("interrupted", recovered.getString("state"))
            assertEquals(limit + 1, recovered.getLong("nodeCount"))
            val snapshots = query("snapshot-list", older).getJSONArray("snapshots")
            assertEquals(1, snapshots.length())
            assertEquals(older, snapshots.getJSONObject(0).getString("id"))
        } finally { reader.shutdownNow() }
    }

    @Test fun reportImportQueryPlans() {
        val test = InstrumentationRegistry.getInstrumentation()
        assumeTrue(BuildConfig.SCAN_BENCHMARK && InstrumentationRegistry.getArguments().getString("scanImportExplain") == "true")
        val plans = JSONObject()
        for (variant in listOf("1024-eager-typed", "1024-eager-typed-lean")) {
            withStore(ImportPolicy.benchmark(variant)) { store ->
                val id = UUID.randomUUID().toString()
                store.begin(id, "/fixture", "ordinary", Process.myUid(), Process.myPid()).use { writer ->
                    writer.node(node(1, 0, "", 0, 1))
                    repeat(32) { i -> writer.node(node(i + 2L, 1, "f$i", 8, i + 2L)) }
                    writer.flush()
                }
                val db = store.readableDatabase
                val record = JSONObject()
                for (table in listOf("nodes", "aggregates")) {
                    db.rawQuery("PRAGMA index_list($table)", null).use { c ->
                        val indexes = org.json.JSONArray()
                        while (c.moveToNext()) indexes.put(c.getString(1))
                        record.put(table, indexes)
                    }
                }
                val queries = linkedMapOf(
                    "snapshotPaths" to "SELECT n.id,a.logical FROM nodes n JOIN aggregates a ON a.sid=n.sid AND a.id=n.id WHERE n.sid=? ORDER BY n.path LIMIT 16",
                    "dedup" to "SELECT device,inode,MIN(path) FROM nodes WHERE sid=? AND valid=1 AND skip=0 AND kind<>1 GROUP BY device,inode ORDER BY device,inode LIMIT 1024",
                    "parentSum" to "SELECT SUM(logical),SUM(allocated) FROM aggregates WHERE sid=? AND parent=1",
                    "parentIncomplete" to "SELECT 1 FROM aggregates WHERE sid=? AND parent=1 AND complete=0",
                    "depth" to "SELECT id FROM aggregates WHERE sid=? AND depth=1 AND id>0 ORDER BY id LIMIT 1024",
                    "nodeDetail" to "SELECT * FROM nodes WHERE sid=? AND id=1",
                )
                for (sort in listOf("logical", "allocated", "name_key")) {
                    val order = if (sort == "name_key") "ASC" else "DESC"
                    queries["children-$sort"] = "SELECT a.id,n.name FROM aggregates a JOIN nodes n ON n.sid=a.sid AND n.id=a.id " +
                        "WHERE a.sid=? AND a.parent=1 ORDER BY a.$sort $order,a.id $order LIMIT 101"
                    if (sort != "name_key") queries["large-$sort"] = "SELECT a.id,n.name FROM aggregates a JOIN nodes n ON n.sid=a.sid AND n.id=a.id " +
                        "WHERE a.sid=? AND a.kind=2 ORDER BY a.$sort DESC,a.id DESC LIMIT 101"
                }
                for ((key, sql) in queries) {
                    db.rawQuery("EXPLAIN QUERY PLAN $sql", arrayOf(id)).use { c ->
                        val entries = org.json.JSONArray()
                        while (c.moveToNext()) entries.put(c.getString(3))
                        record.put(key, entries)
                    }
                }
                plans.put(variant, record)
            }
        }
        test.sendStatus(2, Bundle().apply { putString("importPlans", plans.toString()) })
    }
    @Test fun nativeImportMatchesIndependentStatAndHardlinkOwner() = withStore { store ->
        val root = File(context.filesDir, "phase5-test-${UUID.randomUUID()}")
        check(root.mkdir())
        try {
            val nested = File(root, "dir"); check(nested.mkdir())
            File(root, "z").writeText("abc")
            File(root, "small").writeText("12345")
            File(nested, "child").writeText("1234567")
            RandomAccessFile(File(root, "sparse"), "rw").use { it.setLength(1L shl 30) }
            Os.symlink("dir", File(root, "link").path)
            var allocated = 0L; val identities = mutableSetOf<Pair<Long, Long>>()
            root.walkTopDown().forEach { f ->
                val stat = Os.lstat(f.path)
                if (android.system.OsConstants.S_ISDIR(stat.st_mode) || identities.add(stat.st_dev to stat.st_ino)) allocated += stat.st_blocks * 512
            }
            val id = UUID.randomUUID().toString()
            val handle = NativeBridge.nativeCreateScan(root.path.toByteArray())
            val stream = try { store.begin(id, root.path, "ordinary", Process.myUid(), Process.myPid()).use {
                LocalScanPipe.run(handle, id, it) { _, _, _ -> }
            } } finally { NativeBridge.nativeReleaseScan(handle) }
            val result = store.finalize(id, stream) { false }
            assertEquals("completed", result.getString("state")); assertEquals(7, result.getLong("nodeCount"))
            assertEquals((1L shl 30) + 15, result.getLong("logicalBytes")); assertEquals(allocated, result.getLong("allocatedBytes"))
            store.readableDatabase.rawQuery("SELECT n.path,o.path,a.own_logical FROM nodes n JOIN aggregates a ON a.sid=n.sid AND a.id=n.id JOIN nodes o ON o.sid=a.sid AND o.id=a.owner WHERE n.sid=? AND n.name_key='7a'", arrayOf(id)).use {
                assertTrue(it.moveToFirst()); assertEquals("z", it.getBlob(1).toString(Charsets.UTF_8)); assertEquals(3, it.getLong(2))
            }
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM snapshots WHERE sid=?", arrayOf(id)).use { it.moveToFirst(); assertEquals(1, it.getLong(0)) }
        } finally { root.deleteRecursively() }
    }
    private fun node(id: Long, parent: Long, name: String, bytes: Long, inode: Long) = IndexNode(
        id, parent, name.toByteArray(), byteArrayOf(), if (id == 1L) 1 else 2, if (id == 1L) 0 else 1,
        if (id == 1L) 16832 else 33152, 10000, 10000, ByteBuffer.allocate(8).putLong(1).array(),
        ByteBuffer.allocate(8).putLong(inode).array(), 1, 2, bytes, 4096, 1, 0, true, id != 1L, 0)

    @Test fun sharedOwnerKeepsIneligibleAliasesAndDoesNotSplitIdentityPages() = withStore(finalizer = FinalizePolicy.OPTIMIZED) { store ->
        val id = UUID.randomUUID().toString()
        var next = 0L
        fun entry(parent: Long, name: String, inode: Long, logical: Long = 0) =
            node(++next, parent, name, logical, inode).copy(links = 1)
        val uid = Process.myUid()
        val pid = Process.myPid()
        val winner: Long
        val valid: Long
        val invalid: Long
        val skipped: Long
        val noOwner: Long
        store.begin(id, "/fixture", "ordinary", uid, pid).use { writer ->
            writer.node(entry(0, "", 99999))
            writer.node(entry(1, "z", 99998).copy(kind = 1))
            writer.node(entry(1, "a", 42).copy(kind = 1))
            repeat(4096) { i -> writer.node(entry(2, "f$i", 42, 3).copy(depth = 2)) }
            writer.node(entry(3, "later-byte", 42, 5).copy(name = byteArrayOf(0xff.toByte()), depth = 2))
            val selected = entry(3, "earlier-byte", 42, 7).copy(name = byteArrayOf(0x80.toByte()), depth = 2)
            writer.node(selected)
            winner = selected.id
            val hidden = entry(3, "ineligible", 43, 55).copy(depth = 2, valid = false, complete = false)
            writer.node(hidden); invalid = hidden.id
            val excluded = entry(3, "skipped", 43, 66).copy(depth = 2, skip = 1, complete = false)
            writer.node(excluded); skipped = excluded.id
            val eligible = entry(2, "eligible", 43, 11).copy(depth = 2)
            writer.node(eligible); valid = eligible.id
            val allInvalid = entry(2, "no-owner", 44, 77).copy(depth = 2, valid = false, complete = false)
            writer.node(allInvalid); noOwner = allInvalid.id
            writer.node(entry(3, "non-contributing", 44, 88).copy(depth = 2, skip = 1, complete = false))
            for (dir in listOf(3L, 2L, 1L)) writer.directory(dir, true, true, 0)
            writer.flush()
        }
        val result = store.finalize(id, StreamSummary("completed", next, 0, 0, 0, 0, 1, 0)) { false }
        assertEquals("partial", result.getString("state"))
        assertEquals(18L, result.getLong("logicalBytes"))
        assertEquals(5L * 4096L, result.getLong("allocatedBytes"))
        assertEquals(4098L, result.getLong("issueCount")) // All non-directories of inode 42.
        val db = store.readableDatabase
        fun row(node: Long): List<Long> = db.rawQuery(
            "SELECT owner,own_logical,own_allocated,logical,complete FROM aggregates WHERE sid=? AND id=?",
            arrayOf(id, node.toString()),
        ).use { assertTrue(it.moveToFirst()); (0..4).map(it::getLong) }
        assertEquals(listOf(winner, 7L, 4096L, 7L, 0L), row(winner))
        assertEquals(listOf(winner, 0L, 0L, 0L, 0L), row(4L))
        assertEquals(listOf(winner, 0L, 4096L, 7L, 0L), row(3L))
        assertEquals(listOf(valid, 0L, 0L, 0L, 0L), row(invalid))
        assertEquals(listOf(valid, 0L, 0L, 0L, 0L), row(skipped))
        assertEquals(listOf(noOwner, 0L, 0L, 0L, 0L), row(noOwner))
        db.rawQuery("SELECT COUNT(*) FROM hardlinks WHERE sid=?", arrayOf(id)).use {
            assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
        }
    }

    @Test fun directoryAggregationRejectsSumAndAdditionOverflowBeforePublishing() = withStore(finalizer = FinalizePolicy.OPTIMIZED) { store ->
        for (sumOverflow in listOf(true, false)) {
            val id = UUID.randomUUID().toString()
            store.begin(id, "/fixture", "ordinary", Process.myUid(), Process.myPid()).use { writer ->
                writer.node(node(1, 0, "", 0, 1).copy(allocated = if (sumOverflow) 0 else Long.MAX_VALUE))
                writer.node(node(2, 1, "first", if (sumOverflow) Long.MAX_VALUE else 1, 2).copy(allocated = 1))
                writer.node(node(3, 1, "second", 1, 3).copy(allocated = 0))
                writer.directory(1, true, true, 0)
                writer.flush()
            }
            val failure = runCatching { store.finalize(id, StreamSummary("completed", 3, 0, 0, 0, 0, 1, 0)) { false } }.exceptionOrNull()
            if (sumOverflow) assertTrue("$failure", failure is android.database.sqlite.SQLiteException)
            else assertEquals("Aggregate counter overflow", failure?.message)
            assertTrue(store.preview(id).getBoolean("provisional"))
            assertNoSnapshot(store, id)
        }
    }

    @Test fun cancellationDuringAggregationDoesNotExposeASnapshot() = withStore(finalizer = FinalizePolicy.OPTIMIZED) { store ->
        val id = UUID.randomUUID().toString()
        store.begin(id, "/fixture", "ordinary", Process.myUid(), Process.myPid()).use {
            it.node(node(1, 0, "", 0, 1)); it.node(node(2, 1, "leaf", 9, 2))
            it.directory(1, true, true, 0); it.flush()
        }
        val signal = CancellationSignal()
        val failure = runCatching {
            store.finalize(id, StreamSummary("completed", 2, 0, 0, 0, 0, 1, 0), signal) {
                store.readableDatabase.rawQuery("SELECT phase FROM sessions WHERE id=?", arrayOf(id)).use {
                    if (it.moveToFirst() && it.getString(0) == "aggregating") signal.cancel()
                }
                false
            }
        }.exceptionOrNull()
        assertTrue("$failure", failure is OperationCanceledException)
        store.recover()
        val recovered = store.preview(id)
        assertEquals("interrupted", recovered.getString("state"))
        assertTrue(recovered.getBoolean("provisional"))
        assertNoSnapshot(store, id)
    }

    private fun assertNoSnapshot(store: IndexStore, id: String) {
        store.readableDatabase.rawQuery("SELECT 1 FROM snapshots WHERE sid=?", arrayOf(id)).use { assertFalse(it.moveToFirst()) }
    }

    @Test fun changedHardlinkIsPartialAndRecoveryPreservesRows() = withStore { store ->
        val id = UUID.randomUUID().toString()
        store.begin(id, "/fixture", "ordinary", Process.myUid(), Process.myPid()).use {
            it.node(node(1, 0, "", 4096, 5)); it.node(node(2, 1, "z", 3, 8)); it.node(node(3, 1, "a", 4, 8))
            it.directory(1, true, true, 0); it.flush()
        }
        val result = store.finalize(id, StreamSummary("completed", 3, 0, 1, 2, 0, 1, 1)) { false }
        assertEquals("partial", result.getString("state")); assertEquals(4, result.getLong("logicalBytes"))
        assertEquals(2, result.getLong("issueCount")); assertFalse(result.getBoolean("complete"))
        val other = UUID.randomUUID().toString()
        store.begin(other, "/fixture", "ordinary", Process.myUid(), Process.myPid()).use { it.node(node(1, 0, "", 0, 5)); it.flush() }
        store.recover()
        store.readableDatabase.rawQuery("SELECT state,nodes,provisional FROM sessions WHERE id=?", arrayOf(other)).use {
            assertTrue(it.moveToFirst()); assertEquals("interrupted", it.getString(0)); assertEquals(1, it.getLong(1)); assertEquals(1, it.getInt(2))
        }
        assertEquals("partial", store.preview(id).getString("state"))
        store.readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT 1 FROM aggregates c WHERE c.sid=? AND c.parent=? AND c.complete=0", arrayOf(id, "1")).use {
            val plan = buildString { while (it.moveToNext()) append(it.getString(3)) }
            assertTrue(plan, plan.contains("aggregate_incomplete") && plan.contains("parent=?") && plan.contains("complete=?"))
        }
    }
    @Test fun keysetPagesPreserveTiesAndRejectChangedRevisionAndQuery() = withStore { store ->
        val id = UUID.randomUUID().toString()
        store.begin(id, "/fixture", "ordinary", Process.myUid(), Process.myPid()).use {
            it.node(node(1, 0, "", 4096, 1))
            for (i in 2L..1001L) it.node(node(i, 1, "file-$i", 8, i))
            it.directory(1, true, true, 0); it.flush()
        }
        store.finalize(id, StreamSummary("completed", 1001, 0, 1, 2, 0, 1, 1)) { false }
        val queries = IndexQueries(store)
        fun request(kind: String, sort: String, cursor: String = "") = JSONObject().put("sessionId", id)
            .put("kind", kind).put("parentId", 1).put("sort", sort).put("limit", 37).put("cursor", cursor)
        for (kind in listOf("children", "large-files")) for (sort in listOf("logical", "allocated")) {
            val seen = mutableSetOf<Long>(); var token = ""; var pages = 0
            do {
                val page = JSONObject(queries.query(request(kind, sort, token).toString()))
                assertEquals("ok", page.getString("status")); val rows = page.getJSONArray("entries")
                for (i in 0 until rows.length()) assertTrue("Duplicate node", seen.add(rows.getJSONObject(i).getLong("id")))
                token = page.optString("nextCursor"); pages++
            } while (token.isNotEmpty())
            assertEquals(1000, seen.size); assertEquals(28, pages)
        }
        val first = JSONObject(queries.query(request("children", "name").toString()))
        val token = first.getString("nextCursor")
        for (sort in listOf("logical", "allocated", "name")) {
            val start = JSONObject(queries.query(request("children", sort).toString()))
            val second = JSONObject(queries.query(request("children", sort, start.getString("nextCursor")).toString()))
            val previous = JSONObject(queries.query(request("children", sort, second.getString("previousCursor")).toString()))
            assertEquals(start.getJSONArray("entries").toString(), previous.getJSONArray("entries").toString())
            assertFalse(previous.has("previousCursor"))
            assertEquals(second.getJSONArray("entries").toString(), JSONObject(queries.query(request("children", sort, previous.getString("nextCursor")).toString())).getJSONArray("entries").toString())
        }
        assertEquals("invalid-query", JSONObject(queries.query(request("children", "allocated", token).toString())).getString("status"))
        store.writableDatabase.execSQL("UPDATE sessions SET revision=revision+1 WHERE id=?", arrayOf(id))
        assertEquals("stale-cursor", JSONObject(queries.query(request("children", "name", token).toString())).getString("status"))
        val detail = JSONObject(queries.query(JSONObject().put("sessionId", id).put("kind", "detail").put("nodeId", 2).toString())).getJSONObject("detail")
        assertEquals("66696c652d32", detail.getString("pathHex")); assertEquals(8, detail.getLong("rawSize"))
    }
    @Test fun v4RejectsMalformedAndInterruptedStreams() = withStore { store ->
        val id = UUID.randomUUID().toString()
        fun payload(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use(block)
        }.toByteArray()
        fun frame(type: Int, sequence: Long, body: ByteArray) = payload {
            it.writeInt(0x44535a34); it.writeShort(IndexProtocol.VERSION); it.writeShort(type); it.writeInt(body.size)
            it.write(uuidBytes(id)); it.writeLong(sequence); it.write(body)
        }
        val identity = frame(1, 0, payload { it.writeInt(Process.myUid()); it.writeInt(Process.myUid()); it.writeInt(Process.myPid()) })
        val summary = frame(5, 1, payload { out -> out.writeInt(7); repeat(7) { out.writeLong(0) } })
        fun measurement(write: Long = 3, poll: Long = 2, frames: Long = 2) = frame(9, 2, payload { out ->
            for (value in listOf(5L, write, poll, frames, (identity.size + summary.size).toLong())) out.writeLong(value)
        })
        val end = frame(6, 3, byteArrayOf())
        store.begin(id, "/fixture", "ordinary", Process.myUid(), Process.myPid()).use { writer ->
            val good = identity + summary + measurement() + end
            val variants = listOf(
                good.copyOf(good.size - 1), good + byteArrayOf(1),
                good.copyOf().also { it[5] = 3 }, // obsolete protocol
                good.copyOf().also { it[8] = 127 }, // oversized payload
                good.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() },
                good.copyOf().also { it[35] = 1 }, // out-of-order sequence
                good.copyOf().also { it[39] = (it[39].toInt() xor 1).toByte() }, // wrong scanner UID
                identity + frame(6, 1, byteArrayOf()), // end before summary
                identity + summary + frame(6, 2, byteArrayOf()), // missing measurements
                identity + summary + measurement(frames = 1) + end,
                identity + summary + measurement(poll = 4) + end,
                identity + summary + measurement() + frame(9, 3, byteArrayOf()) + end,
                identity + summary + frame(9, 2, ByteArray(39)) + end,
                identity + frame(5, 1, payload { it.writeInt(7); repeat(6) { _ -> it.writeLong(0) } }),
                identity + frame(5, 1, payload { it.writeInt(7); repeat(7) { _ -> it.writeLong(0) }; it.writeByte(0) }),
            )
            for (reuse in listOf(false, true)) {
                val fragmented = object : ByteArrayInputStream(good) {
                    override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 7))
                }
                val result = IndexProtocol.read(fragmented, id, Process.myUid(), Process.myPid(), writer, reuse) { _, _, _ -> }
                assertEquals("failed", result.state)
                val producer = checkNotNull(result.performance).getJSONObject("producer")
                assertEquals(2, producer.getLong("frames"))
                assertEquals((identity.size + summary.size).toLong(), producer.getLong("wireBytes"))
                assertEquals(3, producer.getLong("pipeWriteMicros"))
                assertEquals(2, producer.getLong("pipePollMicros"))
                for (bytes in variants) {
                    var rejected = false
                    try { IndexProtocol.read(ByteArrayInputStream(bytes), id, Process.myUid(), Process.myPid(), writer, reuse) { _, _, _ -> } }
                    catch (_: Exception) { rejected = true }
                    assertTrue("Malformed stream accepted (reuse=$reuse)", rejected)
                }
            }
        }
    }
    @Test fun schemaMigrationKeepsExistingNodes() {
        for (version in listOf(1, 2)) {
            val name = "phase6-migration-${UUID.randomUUID()}.db"
            val file = File(context.noBackupFilesDir, name)
            val id = UUID.randomUUID().toString()
            try {
                SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                    val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("index-v2.sql").bufferedReader().use { it.readText() }
                    for (sql in fixture.split(';')) if (sql.trim().isNotEmpty()) old.execSQL(sql)
                    if (version == 1) old.execSQL("DROP INDEX aggregate_incomplete")
                    old.execSQL("INSERT INTO sessions(id,root,backend,state,phase,started,scanner_uid,scanner_pid) VALUES(?,'/old','ordinary','completed','complete',1,10000,10000)", arrayOf(id))
                    old.execSQL("""INSERT INTO nodes(sid,id,parent,depth,name,name_key,path,link,kind,mode,uid,gid,device,inode,mount,nlink,size,allocated,seconds,nanos,valid,complete,skip)
                        VALUES(?,1,0,0,X'6f6c642d6e6f6465','6f6c642d6e6f6465',X'',X'',1,16832,10000,10000,X'01',X'02',1,1,0,4096,1,0,1,1,0)""", arrayOf(id))
                    old.version = version
                }
                IndexStore(context, name).use { migrated ->
                    assertEquals(4, migrated.readableDatabase.version)
                    migrated.readableDatabase.rawQuery("SELECT name FROM nodes WHERE sid=?", arrayOf(id)).use {
                        assertTrue(it.moveToFirst()); assertEquals("old-node", it.getBlob(0).toString(Charsets.UTF_8))
                    }
                    val session = JSONObject(IndexQueries(migrated).query(JSONObject().put("kind", "session").put("sessionId", id).toString()))
                    assertEquals("ok", session.getString("status")); assertEquals(0, session.getJSONObject("volume").length())
                    migrated.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='index' AND name IN ('aggregate_incomplete','sessions_recent')", null).use {
                        assertEquals(2, it.count)
                    }
                }
            } finally { SQLiteDatabase.deleteDatabase(file) }
        }
    }
    @Test fun scopedFilesAncestorsStatusesAndRecentSessions() = withStore { store ->
        val id = UUID.randomUUID().toString()
        store.begin(id, "/fixture", "ordinary", Process.myUid(), Process.myPid()).use {
            it.node(node(1, 0, "", 4096, 1))
            it.node(node(2, 1, "scope", 4096, 2).copy(kind=1, complete=false))
            it.node(node(3, 1, "scope2", 4096, 3).copy(kind=1, complete=false))
            it.node(node(4, 2, "one", 10, 4).copy(depth=2))
            it.node(node(5, 2, "nested", 4096, 5).copy(depth=2, kind=1, complete=false))
            it.node(node(6, 5, "two", 20, 6).copy(depth=3))
            it.node(node(7, 3, "outside", 100, 7).copy(depth=2))
            it.node(node(8, 2, "alias", 10, 4).copy(depth=2))
            it.node(node(9, 2, "denied", 0, 9).copy(depth=2, kind=0, valid=false, complete=false))
            it.issue(9, 1, 13, "stat")
            it.node(node(10, 2, "mount", 4096, 10).copy(depth=2, kind=1, complete=false, skip=1))
            for (directory in listOf(1L,2L,3L,5L)) it.directory(directory, true, true, 0)
            it.volume(1000000, 400000, 300000, 1000)
            it.flush()
        }
        store.finalize(id, StreamSummary("partial", 10, 1, 1, 2, 0, 1, 1)) { false }
        val queries = IndexQueries(store)
        fun query(kind: String, parent: Long=0, node: Long=0) = JSONObject(queries.query(JSONObject()
            .put("kind", kind).put("sessionId", id).put("parentId", parent).put("nodeId", node).toString()))
        val files = query("large-files", 2).getJSONArray("entries")
        assertEquals(setOf(4L,6L,8L), (0 until files.length()).map { files.getJSONObject(it).getLong("id") }.toSet())
        val chain = query("ancestors", node=6).getJSONArray("ancestors")
        assertEquals(listOf(1L,2L,5L,6L), (0 until chain.length()).map { chain.getJSONObject(it).getLong("id") })
        val children = query("children", 2).getJSONArray("entries")
        val denied = (0 until children.length()).map { children.getJSONObject(it) }.first { it.getLong("id")==9L }
        assertTrue(denied.getBoolean("inaccessible")); assertFalse(denied.getBoolean("metadataValid"))
        assertEquals("mount-boundary", query("detail", node=10).getJSONObject("detail").getString("skipped"))
        val owner = query("detail", node=4).getJSONObject("detail").getLong("ownerId")
        assertEquals(8L, owner) // alias sorts before one in raw path ordering
        val recent = JSONObject(queries.query("{\"kind\":\"recent\"}")).getJSONArray("sessions")
        assertEquals(id, recent.getJSONObject(0).getString("sessionId"))
        assertEquals(1000000, recent.getJSONObject(0).getJSONObject("volume").getLong("totalBytes"))
        assertEquals("not-found", query("large-files", 999).getString("status"))
    }
    @Test fun treemapIsBoundedAndPreservesBothMetricsAndUnknownRanges() = withStore { store ->
        val id = UUID.randomUUID().toString()
        val queries = IndexQueries(store)
        fun query(sort: String = "logical", parent: Long = 1, limit: Int = 100, cursor: String = "") =
            JSONObject(queries.query(JSONObject().put("sessionId", id).put("kind", "treemap")
                .put("parentId", parent).put("sort", sort).put("limit", limit).put("cursor", cursor).toString()))
        store.begin(id, "/fixture", "ordinary", Process.myUid(), Process.myPid()).use {
            it.node(node(1, 0, "", 4096, 1))
            assertTrue(query().getBoolean("provisional")); assertFalse(query().has("treemap"))
            it.node(node(2, 1, "sparse", 1L shl 30, 2).copy(allocated = 0))
            for (i in 3L..1001L) it.node(node(i, 1, "file-$i", i, i))
            it.node(node(1002, 1, "unknown", 0, 1002).copy(kind = 0, valid = false, complete = false, allocated = 0))
            it.node(node(1003, 1, "sparse-alias", 1L shl 30, 2).copy(allocated = 0))
            it.node(node(1004, 1, "mount", 4096, 1004).copy(kind = 1, complete = false, skip = 1))
            it.directory(1, true, true, 0); it.flush()
        }
        store.finalize(id, StreamSummary("partial", 1004, 0, 1, 2, 0, 1, 1)) { false }
        val detail = JSONObject(queries.query(JSONObject().put("kind", "detail").put("sessionId", id).put("nodeId", 1).toString())).getJSONObject("detail")
        for (sort in listOf("logical", "allocated")) {
            val result = query(sort)
            assertEquals("ok", result.getString("status")); assertFalse(result.getBoolean("provisional"))
            assertFalse(result.has("nextCursor")); assertFalse(result.has("previousCursor"))
            val rows = result.getJSONArray("entries"); assertEquals(100, rows.length())
            val summary = result.getJSONObject("treemap")
            assertEquals(1003, summary.getLong("childCount")); assertTrue(summary.getLong("zeroCount") >= 3)
            assertEquals(2, summary.getLong("unknownCount"))
            val key = if (sort == "logical") "logicalBytes" else "allocatedBytes"
            val represented = (0 until rows.length()).sumOf { rows.getJSONObject(it).getLong(key) }
            assertEquals(detail.getLong(key), summary.getLong("totalBytes"))
            assertEquals(detail.getLong(key), represented + summary.getLong("otherBytes"))
            assertTrue(summary.getLong("otherBytes") > 0)
        }
        assertEquals(2, query().getJSONArray("entries").getJSONObject(0).getLong("id"))
        assertEquals("not-found", query(parent = 99999).getString("status"))
        assertEquals("not-found", query(parent = 2).getString("status"))
        assertEquals("invalid-query", query(limit = 101).getString("status"))
        assertEquals("invalid-query", query(sort = "name").getString("status"))
        assertEquals("invalid-query", query(cursor = "unexpected").getString("status"))
    }
    @Test fun directoryReplacementRemovesPreviouslyCountedBlocks() = withStore { store ->
        val id = UUID.randomUUID().toString()
        store.begin(id, "/fixture", "ordinary", Process.myUid(), Process.myPid()).use {
            it.node(node(1, 0, "", 4096, 1))
            it.node(node(2, 1, "replaced", 4096, 2).copy(kind = 1, complete = false))
            it.node(node(3, 1, "file", 3, 3))
            it.issue(2, 6, 116, "directory-replaced"); it.directory(2, false, false, 6)
            it.directory(1, true, true, 0); it.flush()
        }
        val result = store.finalize(id, StreamSummary("partial", 3, 1, 1, 2, 0, 1, 1)) { false }
        assertEquals("partial", result.getString("state")); assertEquals(3, result.getLong("logicalBytes"))
        assertEquals(8192, result.getLong("allocatedBytes"))
    }
    @Test fun treemapDepthUsesRelativeLevelsAndKeepsBothMetricsAdditive() = withStore { store ->
        val id = UUID.randomUUID().toString()
        store.begin(id, "/fixture", "ordinary", Process.myUid(), Process.myPid()).use {
            it.node(node(1, 0, "", 4096, 1))
            it.node(node(2, 1, "one", 4096, 2).copy(kind = 1))
            it.node(node(3, 2, "two", 4096, 3).copy(kind = 1, depth = 2))
            it.node(node(4, 3, "three", 4096, 4).copy(kind = 1, depth = 3))
            it.node(node(5, 4, "four", 8192, 5).copy(depth = 4, allocated = 0))
            it.node(node(6, 1, "file", 100, 6))
            for (n in 1L..4) it.directory(n, true, true, 0)
            it.flush()
        }
        store.finalize(id, StreamSummary("completed", 6, 0, 1, 2, 0, 1, 1)) { false }
        fun query(depth: Int? = null, parent: Long = 1, sort: String = "logical"): JSONObject {
            val request = JSONObject().put("kind", "treemap").put("sessionId", id).put("parentId", parent).put("sort", sort)
            if (depth != null) request.put("depth", depth)
            return JSONObject(IndexQueries(store).query(request.toString()))
        }
        assertEquals(3, query().getJSONObject("treemap").getInt("depth"))
        for (sort in listOf("logical", "allocated")) for (depth in 0..6) {
            val result = query(depth, sort = sort); assertEquals("ok", result.getString("status"))
            val summary = result.getJSONObject("treemap"); val groups = summary.getJSONArray("groups")
            assertEquals(if (depth == 0) 0 else 2, result.getJSONArray("entries").length())
            val expected = when (depth) { 0, 1 -> emptyList(); 2 -> listOf(2L); 3 -> listOf(2L, 3L); else -> listOf(2L, 3L, 4L) }
            assertEquals(expected, (0 until groups.length()).map { groups.getJSONObject(it).getLong("parentId") })
            assertFalse(summary.getBoolean("truncated"))
            val key = if (sort == "logical") "logicalBytes" else "allocatedBytes"
            for (i in 0 until groups.length()) {
                val group = groups.getJSONObject(i); val rows = group.getJSONArray("entries")
                assertTrue((0 until rows.length()).sumOf { rows.getJSONObject(it).getLong(key) } <= group.getLong("totalBytes"))
            }
        }
        assertEquals(0, query(0).getJSONArray("entries").length())
        assertEquals(5, query(3, parent = 2).getJSONObject("treemap").getJSONArray("groups").getJSONObject(1).getJSONArray("entries").getJSONObject(0).getLong("id"))
    }
    @Test fun hierarchicalTreemapHasGlobalBudgetsAcrossWideDirectories() = withStore { store ->
        val id = UUID.randomUUID().toString(); var next = 2L
        store.begin(id, "/fixture", "ordinary", Process.myUid(), Process.myPid()).use { writer ->
            writer.node(node(1, 0, "", 4096, 1))
            repeat(100) { branch ->
                val parent = next++; writer.node(node(parent, 1, "dir-$branch", 4096, parent).copy(kind = 1))
                repeat(100) { leaf -> val current = next++; writer.node(node(current, parent, "file-$leaf", 100, current).copy(depth = 2)) }
                writer.directory(parent, true, true, 0)
            }
            writer.directory(1, true, true, 0); writer.flush()
        }
        store.finalize(id, StreamSummary("completed", next - 1, 0, 1, 2, 0, 1, 1)) { false }
        for (sort in listOf("logical", "allocated")) {
            val result = JSONObject(IndexQueries(store).query(JSONObject().put("kind", "treemap").put("sessionId", id)
                .put("parentId", 1).put("sort", sort).put("depth", 6).toString()))
            val summary = result.getJSONObject("treemap"); val groups = summary.getJSONArray("groups")
            assertTrue(summary.getBoolean("truncated")); assertTrue(groups.length() <= 48)
            var count = result.getJSONArray("entries").length()
            for (i in 0 until groups.length()) count += groups.getJSONObject(i).getJSONArray("entries").length()
            assertEquals(300, count); assertEquals(count, summary.getInt("nodeCount")); assertTrue(result.toString().length < 310000)
        }
        for (depth in listOf(-1, 7)) assertEquals("invalid-query", JSONObject(IndexQueries(store).query(JSONObject().put("kind", "treemap")
            .put("sessionId", id).put("parentId", 1).put("depth", depth).toString())).getString("status"))
    }
}
