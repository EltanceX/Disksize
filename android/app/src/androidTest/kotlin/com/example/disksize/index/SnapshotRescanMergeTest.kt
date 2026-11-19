package com.example.disksize.index

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import android.os.CancellationSignal
import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.disksize.scan.NativeBridge
import com.example.disksize.scan.SnapshotRescanContract as Contract
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SnapshotRescanMergeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun uuid() = UUID.randomUUID().toString()
    private fun bytes(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
    private fun withStore(policy: FinalizePolicy = FinalizePolicy.OPTIMIZED, block: (IndexStore) -> Unit) {
        val name = "rescan-merge-${uuid()}.db"
        try { IndexStore(context, name, finalizePolicy = policy).use(block) }
        finally { SQLiteDatabase.deleteDatabase(File(context.noBackupFilesDir, name)) }
    }

    @Before fun normalProductionClassesAreInstalled() {
        assertFalse(Class.forName("com.example.disksize.BuildConfig").getField("SCAN_BENCHMARK").getBoolean(null))
        Class.forName("com.example.disksize.index.SnapshotRescanMerger")
    }

    private data class Item(val path: ByteArray, val inode: Long, val size: Long = 0, val kind: Int = 2,
        val known: Boolean = true, val links: Long = 1, val seconds: Long = 1, val allocated: Long = 4096,
        val link: ByteArray = byteArrayOf(), val enumerated: Boolean = true, val skip: Int = 0)
    private fun file(path: String, inode: Long, size: Long = 7, links: Long = 1) = Item(path.toByteArray(), inode, size, links = links)
    private fun dir(path: String, inode: Long = 10) = Item(path.toByteArray(), inode, kind = 1)
    private fun root() = dir("", 1)
    private fun outside() = listOf(dir("a-", 20), file("a-/keep", 21, 11), dir("a2", 22), file("a2/keep", 23, 13),
        dir("outside", 24), file("outside/shared", 99, 7, links = 2), Item(byteArrayOf(0xff.toByte(), 10), 25, 6))
    private fun oldScope() = listOf(dir("a"), file("a/change", 11, 3), file("a/deleted", 12, 5),
        file("a/shared", 99, 7, links = 2), dir("a/replaced", 13), file("a/replaced/old", 14, 2))
    private fun newScope() = listOf(file("change", 11, 9), file("new", 15, 19), file("replaced", 16, 4),
        Item("link".toByteArray(), 17, 15, kind = 3, link = "../outside/keep".toByteArray()))
    private fun under(scope: ByteArray, values: List<Item>) = values.map { it.copy(path = scope + byteArrayOf(47) + it.path) }

    private fun import(writer: IndexConsumer, items: List<Item>) {
        val ids = mutableMapOf<String, Long>()
        for ((index, value) in items.withIndex()) {
            val id = index + 1L; val split = value.path.lastIndexOf(47.toByte())
            val parent = if (index == 0) 0L else ids.getValue(if (split < 0) "" else value.path.copyOf(split).hex())
            writer.node(IndexNode(id, parent, value.path.copyOfRange(split + 1, value.path.size), value.link, value.kind,
                if (index == 0) 0 else value.path.count { it == 47.toByte() } + 1,
                if (value.kind == 1) 16832 else if (value.kind == 3) 41471 else 33152,
                10000, 10000, bytes(1), bytes(value.inode), 1, value.links, value.size, value.allocated,
                value.seconds, 0, value.known, value.known, value.skip))
            if (value.kind == 1) writer.directory(id, value.enumerated, value.known, value.skip)
            if (!value.known) writer.issue(id, 1, 13, "open")
            ids[value.path.hex()] = id
        }
        writer.flush(IndexStore.FlushReason.SUMMARY)
    }

    private fun full(store: IndexStore, values: List<Item>, root: Item = root()): String {
        val id = uuid(); val items = listOf(root) + values
        store.begin(id, "/rescan-merge-fixture", "ordinary", Process.myUid(), Process.myPid()).use { import(it, items) }
        store.finalize(id, summary(items)) { false }
        return id
    }
    private fun summary(items: List<Item>, state: String = "completed") = StreamSummary(state, items.size.toLong(), items.count { !it.known }.toLong(), 0, 2, 0, 1, 1)
    private fun scalar(store: IndexStore, sql: String, vararg args: String) = store.readableDatabase.rawQuery(sql, args).use { assertTrue(it.moveToFirst()); it.getLong(0) }
    private fun revision(store: IndexStore, id: String) = scalar(store, "SELECT revision FROM snapshots WHERE id=?", id)
    private fun nodeId(store: IndexStore, id: String, path: ByteArray) = scalar(store, "SELECT id FROM nodes WHERE sid=? AND path=X'${path.hex()}'", id)
    private fun prepare(store: IndexStore, id: String, values: List<Item>, scope: ByteArray = "a".toByteArray(),
        root: Item = dir(""), state: String = "completed"): String {
        val task = uuid(); val items = listOf(root) + values
        store.rescans.create(task, Contract.Request(id, nodeId(store, id, scope), revision(store, id)))
        store.rescans.beginStaging(task).use { import(it, items); it.finish(summary(items, state)) }
        return task
    }

    private fun query(store: IndexStore, kind: String, vararg args: Pair<String, Any>): JSONObject = JSONObject(
        IndexQueries(store).query(JSONObject().put("kind", kind).also { q -> for ((key, value) in args) q.put(key, value) }.toString()))
    private fun compare(store: IndexStore, base: String, target: String, vararg args: Pair<String, Any>): JSONObject {
        var value: JSONObject
        do { value = query(store, "snapshot-compare", "baseId" to base, "targetId" to target, *args) } while (value.getString("status") == "building")
        return value
    }
    private fun rejection(error: Contract.Error, block: () -> Unit) {
        try { block(); fail("Expected ${error.wireName}") } catch (e: SnapshotRescanStore.Rejected) { assertEquals(error, e.reason) }
    }
    private fun targetRows(store: IndexStore, id: String): List<List<String?>> = buildList {
        for ((table, column) in listOf("sessions" to "id", "snapshots" to "sid", "nodes" to "sid", "aggregates" to "sid", "issues" to "sid", "hardlinks" to "sid")) {
            store.readableDatabase.rawQuery("SELECT * FROM $table WHERE $column=? ORDER BY 1,2", arrayOf(id)).use { c ->
                while (c.moveToNext()) add(listOf(table) + (0 until c.columnCount).map { n ->
                    if (c.isNull(n)) null else if (c.getType(n) == Cursor.FIELD_TYPE_BLOB) c.getBlob(n).hex() else c.getString(n)
                })
            }
        }
    }

    @Test fun uiMetadataDiscoversTaskAndKeepsOriginalTimesWithBoundedLocalHistory() = withStore { store ->
        val raw = byteArrayOf(0xff.toByte(), 10)
        val id = full(store, listOf(Item(raw, 10, kind = 1)))
        val before = query(store, "session", "sessionId" to id)
        assertEquals(id, before.getString("snapshotId"))
        assertFalse(before.getBoolean("retained")); assertEquals(0L, before.getLong("localUpdateCount"))
        assertFalse(before.has("latestRescanTaskId")); assertFalse(before.has("lastLocalUpdate"))
        val task = prepare(store, id, listOf(file("new", 11)), scope = raw)
        assertEquals(task, query(store, "session", "sessionId" to id).getString("latestRescanTaskId"))
        val result = store.rescans.apply(task)
        val metadata = query(store, "session", "sessionId" to id)
        assertEquals(before.getLong("startedAtMillis"), metadata.getLong("startedAtMillis"))
        assertEquals(before.getLong("endedAtMillis"), metadata.getLong("endedAtMillis"))
        assertEquals(1L, metadata.getLong("localUpdateCount")); assertTrue(metadata.getLong("lastLocalUpdateAtMillis") > 0)
        val history = metadata.getJSONObject("lastLocalUpdate")
        assertEquals(raw.hex(), history.getString("targetPathHex")); assertFalse(history.getBoolean("pathTruncated"))
        assertEquals(result.started, history.getLong("startedAtMillis")); assertEquals(result.scanned, history.getLong("scannedAtMillis"))
        assertEquals(result.committedRevision, history.getLong("revision"))
        val listed = query(store, "snapshot-list").getJSONArray("snapshots").getJSONObject(0)
        assertEquals(history.toString(), listed.getJSONObject("lastLocalUpdate").toString())
        val failed = prepare(store, id, listOf(file("different", 12)), scope = raw)
        store.rescans.transition(failed, Contract.State.FAILED, Contract.Error.INCOMPLETE_SCAN)
        val latest = query(store, "session", "sessionId" to id)
        assertEquals(failed, latest.getString("latestRescanTaskId"))
        assertEquals(history.toString(), latest.getJSONObject("lastLocalUpdate").toString())
        store.writableDatabase.execSQL("UPDATE rescan_jobs SET target_path=? WHERE id=?", arrayOf<Any>(ByteArray(5000) { 97 }, task))
        val bounded = query(store, "session", "sessionId" to id).getJSONObject("lastLocalUpdate")
        assertEquals(8192, bounded.getString("targetPathHex").length); assertTrue(bounded.getBoolean("pathTruncated"))
    }

    @Test fun rawPathRelocationFindsExactOrNearestAncestorAndRejectsOldRevision() = withStore { store ->
        val raw = byteArrayOf(0xff.toByte(), 11)
        val id = full(store, oldScope() + outside() + Item(raw, 30, kind = 1))
        val rev = revision(store, id)
        fun locate(path: ByteArray, parent: Long = 1, expected: Long = rev) = query(store, "locate", "sessionId" to id,
            "parentId" to parent, "pathHex" to path.hex(), "expectedRevision" to expected)
        assertEquals(raw.hex(), locate(raw).getJSONObject("detail").getString("pathHex"))
        assertTrue(locate("a-/keep".toByteArray()).getBoolean("pathMatched"))
        for (path in listOf("a/missing/deep", "a/change/deep")) {
            val fallback = locate(path.toByteArray())
            assertFalse(fallback.getBoolean("pathMatched")); assertEquals("a".toByteArray().hex(), fallback.getJSONObject("detail").getString("pathHex"))
        }
        val scope = nodeId(store, id, "a".toByteArray())
        assertEquals("a/change".toByteArray().hex(), locate("change".toByteArray(), scope).getJSONObject("detail").getString("pathHex"))
        assertEquals("stale-cursor", locate(raw, expected = rev - 1).getString("status"))
        for (invalid in listOf("/a", "a//b", "a/..", "a/.", "a/", "a\u0000b")) assertEquals("invalid-query", locate(invalid.toByteArray()).getString("status"))
        assertEquals("invalid-query", locate(ByteArray(3001) { 97 }).getString("status"))
    }

    @Test fun mixedTimeReplacementMatchesFullReplayAndPreservesScopeAndOtherSnapshots() = withStore { store ->
        val id = full(store, oldScope() + outside())
        val expectedId = full(store, listOf(dir("a")) + under("a".toByteArray(), newScope()) + outside())
        val old = SnapshotBaseline.capture(store, id); val expected = SnapshotBaseline.capture(store, expectedId)
        val untouched = targetRows(store, expectedId); val rev = revision(store, id)
        val high = scalar(store, "SELECT node_id_high_water FROM sessions WHERE id=?", id)
        val scope = nodeId(store, id, "a".toByteArray()); val child = nodeId(store, id, "a/deleted".toByteArray())
        val historical = store.readableDatabase.rawQuery("SELECT started,ended,metrics,volume,identity FROM sessions WHERE id=?", arrayOf(id)).use {
            it.moveToFirst(); (0..4).map(it::getString)
        }
        val task = prepare(store, id, newScope()); val result = store.rescans.apply(task)
        assertEquals(Contract.State.COMPLETED, result.state); assertTrue(result.stagingCleaned)
        assertEquals(rev + 1, result.committedRevision); assertEquals(expected, SnapshotBaseline.capture(store, id))
        assertEquals(scope, nodeId(store, id, "a".toByteArray()))
        assertEquals(0L, scalar(store, "SELECT COUNT(*) FROM nodes WHERE sid=? AND id=?", id, child.toString()))
        assertEquals(newScope().size.toLong(), scalar(store, "SELECT COUNT(*) FROM nodes WHERE sid=? AND id>?", id, high.toString()))
        assertEquals(untouched, targetRows(store, expectedId))
        store.readableDatabase.rawQuery("SELECT started,ended,metrics,volume,identity FROM sessions WHERE id=?", arrayOf(id)).use {
            assertTrue(it.moveToFirst()); assertEquals(historical, (0..4).map(it::getString))
        }
        val derived = setOf("owner_path", "own_logical", "own_allocated", "total_logical", "total_allocated", "subtree_complete")
        val actual = SnapshotBaseline.capture(store, id)
        for (entry in outside()) assertEquals(old.row(entry.path).filterKeys { it !in derived }, actual.row(entry.path).filterKeys { it !in derived })
        assertEquals("2", actual.row("outside/shared")["nlink"])
        assertEquals("7", actual.row("outside/shared")["own_logical"])
        assertEquals(1L, scalar(store, "SELECT local_updates FROM snapshots WHERE id=?", id))
        assertEquals(0L, scalar(store, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    @Test fun hardlinkConflictsBecomePartialAndCanBeRepairedWithoutStickyDerivedIssues() = withStore { store ->
        val id = full(store, listOf(dir("a"), file("a/shared", 99, links = 2)) + outside())
        val conflictId = full(store, listOf(dir("a"), file("a/shared", 99, 11, links = 2)) + outside())
        val repairedId = full(store, listOf(dir("a")) + outside())
        val task = prepare(store, id, listOf(file("shared", 99, 11, links = 2)))
        assertEquals(Contract.State.COMPLETED, store.rescans.apply(task).state)
        assertEquals(SnapshotBaseline.capture(store, conflictId), SnapshotBaseline.capture(store, id))
        assertEquals("partial", store.preview(id).getString("state")); assertEquals(2L, store.preview(id).getLong("issueCount"))
        val high = scalar(store, "SELECT node_id_high_water FROM sessions WHERE id=?", id)
        store.rescans.apply(prepare(store, id, emptyList()))
        assertEquals(SnapshotBaseline.capture(store, repairedId), SnapshotBaseline.capture(store, id))
        assertEquals("completed", store.preview(id).getString("state")); assertEquals(0L, store.preview(id).getLong("issueCount"))
        assertEquals(high, scalar(store, "SELECT node_id_high_water FROM sessions WHERE id=?", id))
    }

    @Test fun repeatedUpdatesDoNotReuseIdsAndNewSmallestAliasTakesOwnership() = withStore { store ->
        val id = full(store, listOf(dir("a"), file("a/old", 11)) + outside())
        val expected = full(store, listOf(dir("a"), file("a/00", 99, links = 2)) + outside())
        val originalId = nodeId(store, id, "a/old".toByteArray()); val scope = nodeId(store, id, "a".toByteArray())
        store.rescans.apply(prepare(store, id, listOf(file("first", 12))))
        val firstId = nodeId(store, id, "a/first".toByteArray())
        store.rescans.apply(prepare(store, id, listOf(file("00", 99, links = 2))))
        assertTrue(nodeId(store, id, "a/00".toByteArray()) > firstId && firstId > originalId)
        assertEquals(scope, nodeId(store, id, "a".toByteArray()))
        assertEquals(SnapshotBaseline.capture(store, expected), SnapshotBaseline.capture(store, id))
        assertEquals("a/00".toByteArray().hex(), SnapshotBaseline.capture(store, id).row("outside/shared")["owner_path"])
        assertEquals("not-found", query(store, "detail", "sessionId" to id, "nodeId" to originalId).getString("status"))
    }

    @Test fun repairingOneUnknownScopeKeepsUnrelatedUnknownUntilItTooIsRefreshed() = withStore { store ->
        val outside = listOf(dir("outside", 20), file("outside/denied", 21).copy(known = false))
        val id = full(store, listOf(dir("a"), file("a/denied", 11).copy(known = false)) + outside)
        val mixed = full(store, listOf(dir("a"), file("a/ok", 12)) + outside)
        val repaired = full(store, listOf(dir("a"), file("a/ok", 12), dir("outside", 20), file("outside/ok", 22)))
        store.rescans.apply(prepare(store, id, listOf(file("ok", 12))))
        assertEquals(SnapshotBaseline.capture(store, mixed), SnapshotBaseline.capture(store, id))
        assertFalse(store.preview(id).getBoolean("complete"))
        store.rescans.apply(prepare(store, id, listOf(file("ok", 22)), "outside".toByteArray(), dir("", 20)))
        assertEquals(SnapshotBaseline.capture(store, repaired), SnapshotBaseline.capture(store, id))
        assertTrue(store.preview(id).getBoolean("complete"))
    }

    @Test fun rootReplacementRetainsRootIdAndClearsOldGlobalIssues() = withStore { store ->
        val id = full(store, oldScope() + outside()); val expected = full(store, listOf(file("new-root-file", 99, 80)))
        store.writableDatabase.execSQL("INSERT INTO issues(sid,node,code,errno,operation) VALUES(?,0,3,5,'global')", arrayOf(id))
        store.writableDatabase.execSQL("UPDATE sessions SET issues=issues+1,state='partial' WHERE id=?", arrayOf(id))
        val task = prepare(store, id, listOf(file("new-root-file", 99, 80)), byteArrayOf(), root())
        store.rescans.apply(task)
        assertEquals(1L, nodeId(store, id, byteArrayOf()))
        assertEquals(SnapshotBaseline.capture(store, expected), SnapshotBaseline.capture(store, id))
    }

    @Test fun nonUtf8ScopeAndNamesStayBlobTypedAndNeverCaptureSimilarPrefixes() = withStore { store ->
        val scope = byteArrayOf(0xff.toByte(), 10)
        val oldDir = dir("a").copy(path = scope)
        val sibling = file("sibling", 40).copy(path = scope + byteArrayOf(45))
        val oldChild = file("old", 11).copy(path = scope + "/old".toByteArray())
        val newChild = file("new", 12).copy(path = byteArrayOf(0xfe.toByte(), 13))
        val id = full(store, listOf(oldDir, oldChild, sibling))
        val expected = full(store, listOf(oldDir) + under(scope, listOf(newChild)) + sibling)
        store.rescans.apply(prepare(store, id, listOf(newChild), scope))
        assertEquals(SnapshotBaseline.capture(store, expected), SnapshotBaseline.capture(store, id))
        assertEquals(0L, scalar(store, "SELECT COUNT(*) FROM nodes WHERE sid=? AND (typeof(path)<>'blob' OR typeof(name)<>'blob')", id))
    }

    @Test fun sparseIdsNearLongMaxAreHandledByActualPagesAndEmptyUpdatesStillWork() = withStore { store ->
        val id = full(store, listOf(dir("a"))); val expected = full(store, listOf(dir("a"), file("a/one", 11), file("a/two", 12)))
        store.writableDatabase.execSQL("UPDATE sessions SET node_id_high_water=? WHERE id=?", arrayOf<Any>(Long.MAX_VALUE - 2, id))
        store.rescans.apply(prepare(store, id, listOf(file("one", 11), file("two", 12))))
        assertEquals(Long.MAX_VALUE, nodeId(store, id, "a/two".toByteArray()))
        assertEquals(SnapshotBaseline.capture(store, expected), SnapshotBaseline.capture(store, id))
        store.rescans.apply(prepare(store, id, emptyList()))
        val before = targetRows(store, id)
        rejection(Contract.Error.APPLY_FAILED) { store.rescans.apply(prepare(store, id, listOf(file("overflow", 13)))) }
        assertEquals(before, targetRows(store, id))
    }

    @Test fun allFinalizePoliciesUseTheSameAtomicComputationsAsTheirFullReplay() {
        for (policy in FinalizePolicy.entries) withStore(policy) { store ->
            val id = full(store, oldScope() + outside())
            val expected = full(store, listOf(dir("a"), file("a/shared", 99, 12, links = 2)) + outside())
            store.rescans.apply(prepare(store, id, listOf(file("shared", 99, 12, links = 2))))
            assertEquals(policy.wireName, SnapshotBaseline.capture(store, expected), SnapshotBaseline.capture(store, id))
        }
    }

    @Test fun wideAndBranchingSubtreesCrossEveryMembershipMappingAndRebuildPage() = withStore { store ->
        fun scope(prefix: String, size: Long) = (1..4500).map { file("${prefix}flat-$it", (it - 1) / 2 + 100L, size, links = 2) } +
            (1..300).flatMap { listOf(dir("${prefix}dir-$it", it + 10_000L), file("${prefix}dir-$it/child", it + 20_000L, size)) }
        val id = full(store, listOf(dir("a")) + scope("a/", 1) + outside())
        val expected = full(store, listOf(dir("a")) + scope("a/", 2) + outside())
        val base = full(store, outside())
        compare(store, base, id)
        val cachedRows = scalar(store, "SELECT COUNT(*) FROM diff_rows"); assertTrue(cachedRows > 4096)
        val high = scalar(store, "SELECT node_id_high_water FROM sessions WHERE id=?", id)
        val target = nodeId(store, id, "a".toByteArray()); val rev = revision(store, id)
        val oldLogical = store.preview(id).getLong("logicalBytes")
        var purging = false; var cancelledAfterPage = false
        rejection(Contract.Error.CANCELLED) {
            store.rescans.apply(prepare(store, id, scope("", 2)), cancelled = {
                (purging && scalar(store, "SELECT COUNT(*) FROM diff_rows") < cachedRows).also { if (it) cancelledAfterPage = true }
            }, checkpoint = { if (it == SnapshotRescanMerger.Point.AGGREGATED) purging = true })
        }
        assertTrue(cancelledAfterPage); assertEquals(cachedRows, scalar(store, "SELECT COUNT(*) FROM diff_rows"))
        assertEquals(1L, scalar(store, "SELECT COUNT(*) FROM diff_cache WHERE signature=?", "$base|${revision(store, base)}|$id|$rev"))
        assertEquals(rev, revision(store, id)); assertEquals(high, scalar(store, "SELECT node_id_high_water FROM sessions WHERE id=?", id))
        assertEquals(oldLogical, store.preview(id).getLong("logicalBytes"))
        assertEquals(0L, scalar(store, "SELECT COUNT(*) FROM nodes WHERE sid=? AND id>?", id, high.toString()))
        val result = store.rescans.apply(prepare(store, id, scope("", 2)))
        assertEquals(0L, scalar(store, "SELECT COUNT(*) FROM diff_rows")); assertEquals(0L, scalar(store, "SELECT COUNT(*) FROM diff_cache"))
        assertEquals(5101L, JSONObject(result.metrics).getLong("oldSubtreeNodes"))
        assertEquals(5101L, JSONObject(result.metrics).getLong("newSubtreeNodes"))
        assertEquals(rev + 1, revision(store, id)); assertEquals(target, nodeId(store, id, "a".toByteArray()))
        assertEquals(high + 5100, scalar(store, "SELECT node_id_high_water FROM sessions WHERE id=?", id))
        assertEquals(5100L, scalar(store, "SELECT COUNT(*) FROM nodes WHERE sid=? AND id>?", id, high.toString()))
        // Compare all normalized rows in SQLite rather than materializing two large trees in the test heap.
        val raw = "depth,name,name_key,path,link,kind,mode,uid,gid,device,inode,mount,nlink,size,allocated,seconds,nanos,valid,complete,enumerated,skip"
        val columns = raw.split(',').joinToString(",") { "n.$it" }
        val normalized = """SELECT $columns,p.path,o.path,a.own_logical,a.own_allocated,a.logical,a.allocated,a.complete
            FROM nodes n JOIN aggregates a ON a.sid=n.sid AND a.id=n.id
            LEFT JOIN nodes p ON p.sid=n.sid AND p.id=n.parent LEFT JOIN nodes o ON o.sid=a.sid AND o.id=a.owner WHERE n.sid=?"""
        assertEquals(0L, scalar(store, "SELECT COUNT(*) FROM ($normalized EXCEPT $normalized)", id, expected))
        assertEquals(0L, scalar(store, "SELECT COUNT(*) FROM ($normalized EXCEPT $normalized)", expected, id))
        for (key in listOf("logicalBytes", "allocatedBytes", "nodeCount", "issueCount"))
            assertEquals(key, store.preview(expected).getLong(key), store.preview(id).getLong(key))
        // Traverse and remove the now-sparse, remapped descendants on a second update.
        store.rescans.apply(prepare(store, id, emptyList()))
        assertEquals(outside().size + 2L, store.preview(id).getLong("nodeCount"))
        assertEquals(high + 5100, scalar(store, "SELECT node_id_high_water FROM sessions WHERE id=?", id))
        assertEquals(0L, scalar(store, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    @Test fun partialMissingChangedTypeAndUnenumeratedStreamsNeverReplaceOldData() = withStore { store ->
        val id = full(store, oldScope() + outside()); val before = targetRows(store, id)
        val cases = listOf(
            Triple(dir(""), "partial", Contract.Error.INCOMPLETE_SCAN),
            Triple(dir("").copy(known = false), "completed", Contract.Error.TARGET_MISSING),
            Triple(file("", 10), "completed", Contract.Error.TARGET_TYPE_CHANGED),
            Triple(dir("").copy(enumerated = false), "completed", Contract.Error.INCOMPLETE_SCAN),
        )
        for ((root, state, expected) in cases) {
            val task = prepare(store, id, emptyList(), root = root, state = state)
            rejection(expected) { store.rescans.apply(task) }
            assertEquals(Contract.State.FAILED, store.rescans.get(task)!!.state)
            assertTrue(store.rescans.get(task)!!.stagingCleaned); assertEquals(before, targetRows(store, id))
        }
        val issue = prepare(store, id, listOf(file("denied", 11).copy(known = false)))
        rejection(Contract.Error.INCOMPLETE_SCAN) { store.rescans.apply(issue) }; assertEquals(before, targetRows(store, id))
    }

    @Test fun malformedStagingAndChangedMountAreRejectedBeforeAnyReplacement() = withStore { store ->
        val id = full(store, listOf(dir("a"))); val before = targetRows(store, id)
        val changes = listOf(
            "UPDATE rescan_nodes SET parent=999 WHERE task_id=? AND id=2" to Contract.Error.PROTOCOL_ERROR,
            "UPDATE rescan_nodes SET path=X'612d2f657363617065' WHERE task_id=? AND id=2" to Contract.Error.PROTOCOL_ERROR,
            "UPDATE rescan_nodes SET allocated=1.5 WHERE task_id=? AND id=2" to Contract.Error.PROTOCOL_ERROR,
            "UPDATE rescan_jobs SET nodes=3 WHERE id=?" to Contract.Error.PROTOCOL_ERROR,
            "UPDATE rescan_nodes SET mount=2 WHERE task_id=?" to Contract.Error.PROTOCOL_ERROR,
            "UPDATE rescan_jobs SET summary=json_set(summary,'$.rootMountId',2) WHERE id=?" to Contract.Error.INCOMPATIBLE_IDENTITY,
        )
        for ((sql, expected) in changes) {
            val task = prepare(store, id, listOf(file("child", 11)))
            store.writableDatabase.execSQL(sql, arrayOf(task))
            rejection(expected) { store.rescans.apply(task) }; assertEquals(before, targetRows(store, id))
        }
    }

    @Test fun revisionAndRetainAreRecheckedInsideTheFinalTransaction() = withStore { store ->
        val id = full(store, oldScope() + outside())
        val stale = prepare(store, id, newScope())
        store.writableDatabase.execSQL("UPDATE sessions SET revision=revision+1 WHERE id=?", arrayOf(id))
        store.writableDatabase.execSQL("UPDATE snapshots SET revision=revision+1 WHERE id=?", arrayOf(id))
        val beforeStale = targetRows(store, id)
        rejection(Contract.Error.STALE_REVISION) { store.rescans.apply(stale) }
        assertEquals(Contract.State.STALE, store.rescans.get(stale)!!.state); assertEquals(beforeStale, targetRows(store, id))
        val retained = prepare(store, id, newScope())
        store.writableDatabase.execSQL("UPDATE snapshots SET retained=1 WHERE id=?", arrayOf(id))
        val beforeRetain = targetRows(store, id)
        rejection(Contract.Error.RETAINED) { store.rescans.apply(retained) }; assertEquals(beforeRetain, targetRows(store, id))
    }

    @Test fun everyPrecommitFaultRollsBackRawDataDerivedDataRevisionWatermarkAndMetadata() = withStore { store ->
        val id = full(store, oldScope() + outside()); val before = targetRows(store, id)
        for (point in SnapshotRescanMerger.Point.entries.filter { it != SnapshotRescanMerger.Point.AFTER_COMMIT }) {
            val task = prepare(store, id, newScope())
            val storage = point.ordinal % 2 == 0
            rejection(if (storage) Contract.Error.STORAGE_ERROR else Contract.Error.APPLY_FAILED) {
                store.rescans.apply(task, checkpoint = { if (it == point) {
                    if (storage) throw SQLiteFullException("injected-full") else error("injected-fault")
                } })
            }
            assertEquals(point.name, before, targetRows(store, id))
            assertTrue(store.rescans.get(task)!!.stagingCleaned)
        }
        // Successful execution after all rollbacks also proves temporary tables did not leak.
        assertEquals(Contract.State.COMPLETED, store.rescans.apply(prepare(store, id, newScope())).state)
    }

    @Test fun sqlitePageLimitReallyFailsAReplacementWriteAndRollsBackTheSnapshot() = withStore { store ->
        val id = full(store, listOf(dir("a"))); val before = targetRows(store, id)
        val task = prepare(store, id, (1..600).map { file("child-$it-${"x".repeat(180)}", it + 100L) })
        val db = store.writableDatabase
        val previousLimit = scalar(store, "PRAGMA max_page_count")
        var reserved = false; var inserted = false; var full = false
        try {
            try {
                store.rescans.apply(task, checkpoint = {
                    if (it == SnapshotRescanMerger.Point.IDS_RESERVED) {
                        val pages = scalar(store, "PRAGMA page_count")
                        assertEquals(pages, scalar(store, "PRAGMA max_page_count=$pages"))
                        reserved = true
                    }
                    if (it == SnapshotRescanMerger.Point.NODES_INSERTED) inserted = true
                })
                fail("Expected SQLite to exhaust this fixture database's page limit")
            } catch (error: SnapshotRescanStore.Rejected) {
                assertEquals(Contract.Error.STORAGE_ERROR, error.reason)
                full = generateSequence<Throwable>(error) { it.cause }.any { it is SQLiteFullException }
            }
        } finally {
            db.rawQuery("PRAGMA max_page_count=$previousLimit", null).use { assertTrue(it.moveToFirst()) }
        }
        assertTrue(reserved); assertFalse(inserted); assertTrue("An engine SQLiteFullException, not an injected exception", full)
        assertEquals(before, targetRows(store, id)); assertNull(store.rescans.get(task)!!.committedRevision)
        store.recover()
        assertTrue(store.rescans.get(task)!!.state.terminal); assertTrue(store.rescans.get(task)!!.stagingCleaned)
        assertEquals(before, targetRows(store, id))
        assertEquals(Contract.State.COMPLETED, store.rescans.apply(prepare(store, id, newScope())).state)
    }

    @Test fun cancellationBeforeAndDuringRebuildRollsBackAndCannotReplayCancelledTask() = withStore { store ->
        val id = full(store, oldScope() + outside()); val before = targetRows(store, id)
        for (point in SnapshotRescanMerger.Point.entries.filter { it != SnapshotRescanMerger.Point.AFTER_COMMIT }) {
            val task = prepare(store, id, newScope()); val signal = CancellationSignal()
            rejection(Contract.Error.CANCELLED) { store.rescans.apply(task, signal, checkpoint = { if (it == point) signal.cancel() }) }
            assertEquals(before, targetRows(store, id)); assertEquals(Contract.State.CANCELLED, store.rescans.get(task)!!.state)
            assertEquals(Contract.State.CANCELLED, store.rescans.apply(task).state)
        }
        val task = prepare(store, id, newScope()); store.rescans.requestCancellation(task)
        rejection(Contract.Error.CANCELLED) { store.rescans.apply(task) }; assertEquals(before, targetRows(store, id))
    }

    @Test fun publicationFailureAfterCommitRemainsCompletedAndReplayNeverAllocatesTwice() = withStore { store ->
        val id = full(store, oldScope() + outside()); val task = prepare(store, id, newScope())
        val result = store.rescans.apply(task, checkpoint = { if (it == SnapshotRescanMerger.Point.AFTER_COMMIT) error("lost-publication") })
        assertEquals(Contract.State.COMPLETED, result.state); assertNotNull(result.committedRevision); assertFalse(result.stagingCleaned)
        val after = targetRows(store, id); store.close(); store.recover()
        assertTrue(store.rescans.get(task)!!.stagingCleaned)
        assertEquals(Contract.State.COMPLETED, store.rescans.apply(task).state); assertEquals(after, targetRows(store, id))
        assertEquals(Contract.Cancellation.ALREADY_COMPLETED, store.rescans.requestCancellation(task))
    }

    @Test fun aggregateOverflowAndGlobalDepthLimitRejectTheEntireUpdate() = withStore { store ->
        val id = full(store, listOf(dir("a"))); val before = targetRows(store, id)
        val huge = listOf(file("one", 11, Long.MAX_VALUE), file("two", 12, 1))
        rejection(Contract.Error.APPLY_FAILED) { store.rescans.apply(prepare(store, id, huge)) }
        assertEquals(before, targetRows(store, id))
        var path = ""; val deep = (1..512).map { path += (if (path.isEmpty()) "" else "/") + "d"; dir(path, it + 10L) }
        rejection(Contract.Error.INCOMPLETE_SCAN) { store.rescans.apply(prepare(store, id, deep)) }
        assertEquals(before, targetRows(store, id))
    }

    @Test fun walReadersSeeOldSnapshotUntilCommitAndOldCursorsBecomeStale() = withStore { store ->
        val id = full(store, listOf(dir("a"), file("a/old1", 11), file("a/old2", 12)))
        val scope = nodeId(store, id, "a".toByteArray())
        val page = query(store, "children", "sessionId" to id, "parentId" to scope, "limit" to 1)
        val cursor = page.getString("nextCursor"); val rev = revision(store, id)
        val executor = Executors.newSingleThreadExecutor()
        try {
            store.rescans.apply(prepare(store, id, listOf(file("new", 13))), checkpoint = { point ->
                if (point == SnapshotRescanMerger.Point.NODES_INSERTED) {
                    val old = executor.submit<JSONObject> { query(store, "children", "sessionId" to id, "parentId" to scope) }.get(5, TimeUnit.SECONDS)
                    assertEquals("ok", old.getString("status")); assertEquals(rev, old.getLong("revision"))
                    assertEquals(2, old.getJSONArray("entries").length())
                    val nonBlocking = executor.submit<String> {
                        IndexQueries(store).query("""{"kind":"snapshot-list"}""", nonBlockingSnapshots = true)
                    }.get(1, TimeUnit.SECONDS)
                    assertEquals("busy", JSONObject(nonBlocking).getString("status"))
                }
            })
            assertEquals("stale-cursor", query(store, "children", "sessionId" to id, "parentId" to scope, "limit" to 1, "cursor" to cursor).getString("status"))
            assertEquals(1, query(store, "children", "sessionId" to id, "parentId" to scope).getJSONArray("entries").length())
        } finally { executor.shutdownNow() }
    }

    @Test fun inMemoryComparisonBuildersAndCompletedCursorsCannotWriteAnOldRevisionBack() = withStore { store ->
        val base = full(store, emptyList())
        val id = full(store, listOf(dir("a")) + (1..1100).map { file("a/file-$it", it + 100L, 1) })
        val independent = SnapshotQueries(store)
        val request = JSONObject().put("kind", "snapshot-compare").put("baseId", base).put("targetId", id).put("limit", 1)
        assertEquals("building", independent.execute(request).getString("status"))
        store.rescans.apply(prepare(store, id, listOf(file("only", 11, 9))))
        var result: JSONObject
        do { result = independent.execute(request) } while (result.getString("status") == "building")
        assertEquals("ok", result.getString("status")); assertEquals(2, result.getJSONObject("counts").getInt("added"))
        val oldCursor = result.getString("nextCursor")
        store.rescans.apply(prepare(store, id, listOf(file("next", 12, 8))))
        assertEquals("stale-cursor", independent.execute(JSONObject(request.toString()).put("cursor", oldCursor)).getString("status"))
        compare(store, base, id, "limit" to 1)
        assertEquals("stale-cursor", independent.execute(JSONObject(request.toString()).put("cursor", oldCursor)).getString("status"))
        assertEquals(1L, scalar(store, "SELECT COUNT(*) FROM diff_cache WHERE signature LIKE ?", "%|${revision(store, id)}"))
    }

    @Test fun nativeSubtreeMergeMatchesARealFullScanOfTheControlledStaticTree() = withStore { store ->
        val root = File(context.filesDir, "rescan-merge-native-${uuid()}"); check(root.mkdir())
        fun write(path: String, size: Int) { FileOutputStream(File(root, path)).use { it.write(ByteArray(size) { 42 }); it.fd.sync() } }
        fun fullScan(): String {
            val id = uuid(); val handle = NativeBridge.nativeCreateScan(root.path.toByteArray())
            try {
                val summary = store.begin(id, root.path, "ordinary", Process.myUid(), Process.myPid()).use { LocalScanPipe.run(handle, id, it) { _, _, _ -> } }
                store.finalize(id, summary) { false }
            } finally { NativeBridge.nativeReleaseScan(handle) }
            return id
        }
        try {
            for (path in listOf("a", "a/replaced", "a-", "a2", "outside", "empty")) check(File(root, path).mkdir())
            for ((path, size) in listOf("a/change" to 3, "a/deleted" to 5, "a/shared" to 7, "a/replaced/old" to 2,
                "a-/keep" to 11, "a2/keep" to 13, "outside/keep" to 17, "outside/shared" to 7)) write(path, size)
            Os.symlink("../outside/keep", File(root, "a/link").path)
            val id = fullScan(); val history = fullScan(); val frozen = targetRows(store, history)
            check(File(root, "a/deleted").delete()); check(File(root, "a/shared").delete())
            write("a/change", 9); write("a/new", 19)
            check(File(root, "a/replaced/old").delete()); check(File(root, "a/replaced").delete()); write("a/replaced", 4)
            val expected = fullScan(); val task = uuid()
            store.rescans.create(task, Contract.Request(id, nodeId(store, id, "a".toByteArray()), revision(store, id)))
            val handle = NativeBridge.nativeCreateScan(File(root, "a").path.toByteArray())
            try { store.rescans.beginStaging(task).use {
                val summary = LocalScanPipe.run(handle, task, it) { _, _, _ -> }; it.finish(summary)
            } } finally { NativeBridge.nativeReleaseScan(handle) }
            store.rescans.apply(task)
            assertEquals(SnapshotBaseline.capture(store, expected), SnapshotBaseline.capture(store, id))
            assertEquals(80L, store.preview(id).getLong("logicalBytes")); assertEquals(14L, store.preview(id).getLong("nodeCount"))
            assertEquals(frozen, targetRows(store, history))
        } finally { File(root, "a/link").delete(); root.deleteRecursively() }
    }
}
