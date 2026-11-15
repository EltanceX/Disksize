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
        assertEquals("invalid-query", JSONObject(queries.query(request("children", "allocated", token).toString())).getString("status"))
        store.writableDatabase.execSQL("UPDATE sessions SET revision=revision+1 WHERE id=?", arrayOf(id))
        assertEquals("stale-cursor", JSONObject(queries.query(request("children", "name", token).toString())).getString("status"))
        val detail = JSONObject(queries.query(JSONObject().put("sessionId", id).put("kind", "detail").put("nodeId", 2).toString())).getJSONObject("detail")
        assertEquals("66696c652d32", detail.getString("pathHex")); assertEquals(8, detail.getLong("rawSize"))
    }
    @Test fun v2RejectsMalformedAndInterruptedStreams() = withStore { store ->
        val id = UUID.randomUUID().toString()
        fun payload(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use(block)
        }.toByteArray()
        fun frame(type: Int, sequence: Long, body: ByteArray) = payload {
            it.writeInt(0x44535a34); it.writeShort(2); it.writeShort(type); it.writeInt(body.size)
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
    @Test fun schemaMigrationKeepsExistingNodes() = withStore { store ->
        val id = UUID.randomUUID().toString()
        store.begin(id, "/fixture", "ordinary", Process.myUid(), Process.myPid()).use { it.node(node(1, 0, "", 0, 1)); it.flush() }
        val name = File(store.writableDatabase.path).name
        store.writableDatabase.execSQL("DROP INDEX aggregate_incomplete")
        store.writableDatabase.execSQL("PRAGMA user_version=1")
        store.close()
        IndexStore(context, name).use { migrated ->
            assertEquals(2, migrated.readableDatabase.version)
            assertEquals(1, migrated.preview(id).getLong("nodeCount"))
            migrated.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='index' AND name='aggregate_incomplete'", null).use {
                assertTrue(it.moveToFirst())
            }
        }
    }
}
