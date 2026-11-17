package com.example.disksize.index

import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.disksize.scan.NativeBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

@RunWith(AndroidJUnit4::class)
class IndexStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun withStore(block: (IndexStore) -> Unit) {
        val name = "phase5-test-${UUID.randomUUID()}.db"
        val store = IndexStore(context, name)
        try { block(store) } finally {
            store.close(); SQLiteDatabase.deleteDatabase(File(context.noBackupFilesDir, name))
        }
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
    @Test fun v3RejectsMalformedAndInterruptedStreams() = withStore { store ->
        val id = UUID.randomUUID().toString()
        fun payload(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use(block)
        }.toByteArray()
        fun frame(type: Int, sequence: Long, body: ByteArray) = payload {
            it.writeInt(0x44535a34); it.writeShort(3); it.writeShort(type); it.writeInt(body.size)
            it.write(uuidBytes(id)); it.writeLong(sequence); it.write(body)
        }
        val identity = frame(1, 0, payload { it.writeInt(Process.myUid()); it.writeInt(Process.myUid()); it.writeInt(Process.myPid()) })
        val summary = frame(5, 1, payload { out -> out.writeInt(7); repeat(7) { out.writeLong(0) } })
        val end = frame(6, 2, byteArrayOf())
        store.begin(id, "/fixture", "ordinary", Process.myUid(), Process.myPid()).use { writer ->
            val good = identity + summary + end
            assertEquals("failed", IndexProtocol.read(ByteArrayInputStream(good), id, Process.myUid(), Process.myPid(), writer) { _, _, _ -> }.state)
            val variants = listOf(
                good.copyOf(good.size - 1), good + byteArrayOf(1),
                good.copyOf().also { it[5] = 1 }, // obsolete protocol
                good.copyOf().also { it[8] = 127 }, // oversized payload
                good.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() },
                good.copyOf().also { it[35] = 1 }, // out-of-order sequence
                good.copyOf().also { it[39] = (it[39].toInt() xor 1).toByte() }, // wrong scanner UID
                identity + end // end before summary
            )
            for (bytes in variants) {
                var rejected = false
                try { IndexProtocol.read(ByteArrayInputStream(bytes), id, Process.myUid(), Process.myPid(), writer) { _, _, _ -> } }
                catch (_: Exception) { rejected = true }
                assertTrue("Malformed stream accepted", rejected)
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
}
