package com.example.disksize.index

import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.disksize.scan.NativeBridge
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SnapshotStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun withStore(block: (IndexStore) -> Unit) {
        val name = "phase8-test-${UUID.randomUUID()}.db"
        IndexStore(context, name).use { store ->
            try { block(store) } finally { store.close(); SQLiteDatabase.deleteDatabase(File(context.noBackupFilesDir, name)) }
        }
    }
    private data class Item(val path: String, val bytes: Long = 0, val directory: Boolean = false, val known: Boolean = true, val inode: Long = 0, val skip: Int = 0, val raw: ByteArray? = null)
    private fun fixture(store: IndexStore, items: List<Item>, root: String = "/fixture", backend: String = "ordinary"): String {
        val id = UUID.randomUUID().toString()
        val ids = mutableMapOf("" to 1L)
        fun node(n: Long, parent: Long, name: String, depth: Int, item: Item) = IndexNode(n, parent, item.raw ?: name.toByteArray(), byteArrayOf(),
            if (item.directory) 1 else 2, depth, if (item.directory) 16832 else 33152, 10000, 10000,
            ByteBuffer.allocate(8).putLong(1).array(), ByteBuffer.allocate(8).putLong(if (item.inode == 0L) n else item.inode).array(),
            1, 2, item.bytes, if (item.directory || item.bytes > 0) 4096 else 0, 1, 0, item.known, item.known, item.skip)
        store.begin(id, root, backend, Process.myUid(), Process.myPid()).use { writer ->
            writer.node(node(1, 0, "", 0, Item("", directory = true)))
            for ((i, item) in items.withIndex()) {
                val number = i + 2L
                val parent = item.path.substringBeforeLast('/', "")
                writer.node(node(number, ids.getValue(parent), item.path.substringAfterLast('/'), item.path.count { it == '/' } + 1, item))
                ids[item.path] = number
                if (item.directory) writer.directory(number, item.known, item.known, item.skip)
                if (!item.known) writer.issue(number, 1, 13, "open")
            }
            writer.directory(1, true, true, 0); writer.flush()
        }
        store.finalize(id, StreamSummary("completed", items.size + 1L, 0, 1, 2, 0, 1, 1)) { false }
        return id
    }
    private fun call(store: IndexStore, kind: String, vararg fields: Pair<String, Any>): JSONObject {
        val query = JSONObject().put("kind", kind)
        fields.forEach { query.put(it.first, it.second) }
        return JSONObject(IndexQueries(store).query(query.toString()))
    }
    private fun compare(store: IndexStore, a: String, b: String, vararg fields: Pair<String, Any>): JSONObject {
        repeat(1000) {
            val value = call(store, "snapshot-compare", "baseId" to a, "targetId" to b, *fields)
            if (value.getString("status") != "building") return value
        }
        error("Comparison did not finish")
    }
    private fun rows(value: JSONObject): Map<String, JSONObject> = value.getJSONArray("entries").let { array ->
        (0 until array.length()).associate { val row = array.getJSONObject(it); row.getString("pathHex") to row }
    }
    @Test fun controlledNativeChangesAreFrozenAndDeletionNeverTouchesFiles() = withStore { store ->
        val root = File(context.filesDir, "phase8-native-${UUID.randomUUID()}"); check(root.mkdir())
        try {
            File(root, "keep").writeText("abc"); File(root, "removed").writeText("12345")
            val folder = File(root, "dir"); check(folder.mkdir()); File(folder, "grow").writeText("12")
            fun scan(): String {
                val id = UUID.randomUUID().toString(); val handle = NativeBridge.nativeCreateScan(root.path.toByteArray())
                val summary = try { store.begin(id, root.path, "ordinary", Process.myUid(), Process.myPid()).use { LocalScanPipe.run(handle, id, it) { _, _, _ -> } } }
                    finally { NativeBridge.nativeReleaseScan(handle) }
                store.finalize(id, summary) { false }; return id
            }
            val before = scan()
            File(root, "removed").delete(); File(root, "added\n文件").writeText("1234567"); File(folder, "grow").appendText("123456")
            val after = scan()
            assertTrue(call(store, "snapshot-list").getJSONArray("snapshots").getJSONObject(0).getJSONObject("identity").getString("volumeUuid").isNotEmpty())
            val value = compare(store, before, after)
            assertEquals("ok", value.getString("status")); assertEquals(8, value.getLong("logicalDelta"))
            val changes = rows(value)
            assertEquals("deleted", changes.getValue("removed".toByteArray().hex()).getString("status"))
            assertEquals("added", changes.getValue("added\n文件".toByteArray().hex()).getString("status"))
            assertEquals(6, changes.getValue("dir".toByteArray().hex()).getLong("logicalDelta"))
            assertFalse(changes.containsKey("keep".toByteArray().hex()))
            val growth = compare(store, before, after, "filter" to "growth")
            assertEquals(1, growth.getJSONArray("entries").length())
            assertEquals(10, store.preview(before).getLong("logicalBytes"))
            try { store.finalize(before, StreamSummary("completed", 5, 0, 1, 2, 0, 1, 1)) { false }; fail("Immutable snapshot accepted another finalize") }
            catch (_: IllegalStateException) { }
            assertEquals("ok", call(store, "snapshot-delete", "snapshotId" to before).getString("status"))
            assertEquals("abc", File(root, "keep").readText()); assertEquals(8, File(folder, "grow").length())
            assertEquals("not-found", compare(store, before, after).getString("status"))
        } finally { root.deleteRecursively() }
    }
    @Test fun stableVolumeIdentitySurvivesChangedBootAndMountButRejectsOtherVolume() = withStore { store ->
        val a = fixture(store, listOf(Item("file", 4))); val b = fixture(store, listOf(Item("file", 7)))
        for ((id, boot) in listOf(a to "boot-a", b to "boot-b")) {
            val original = store.readableDatabase.rawQuery("SELECT identity FROM sessions WHERE id=?", arrayOf(id)).use { it.moveToFirst(); JSONObject(it.getString(0)) }
            store.writableDatabase.execSQL("UPDATE sessions SET identity=? WHERE id=?", arrayOf(original.put("volumeUuid", "known-volume").put("boot", boot).toString(), id))
        }
        val metrics = store.readableDatabase.rawQuery("SELECT metrics FROM sessions WHERE id=?", arrayOf(b)).use { it.moveToFirst(); JSONObject(it.getString(0)) }
        store.writableDatabase.execSQL("UPDATE sessions SET metrics=? WHERE id=?", arrayOf(metrics.put("rootMountId", 999).toString(), b))
        assertEquals(3, compare(store, a, b).getLong("logicalDelta"))
        val changed = store.readableDatabase.rawQuery("SELECT identity FROM sessions WHERE id=?", arrayOf(b)).use { it.moveToFirst(); JSONObject(it.getString(0)) }
        store.writableDatabase.execSQL("UPDATE sessions SET identity=? WHERE id=?", arrayOf(changed.put("volumeUuid", "other-volume").toString(), b))
        assertEquals("incompatible", compare(store, a, b).getString("status"))
    }
    @Test fun inaccessibleAndSkippedAncestorsProduceUnknownInBothDirections() = withStore { store ->
        val a = fixture(store, listOf(Item("private", directory = true), Item("private/lost", 8), Item("removed", 5), Item("grow", 1)))
        val b = fixture(store, listOf(Item("private", directory = true, known = false), Item("grow", 6)))
        val value = compare(store, a, b)
        assertFalse(value.getBoolean("complete")); assertTrue(value.isNull("logicalDelta"))
        assertEquals("unknown", rows(value).getValue("private/lost".toByteArray().hex()).getString("status"))
        assertEquals("deleted", rows(value).getValue("removed".toByteArray().hex()).getString("status"))
        assertEquals(5, rows(value).getValue("grow".toByteArray().hex()).getLong("logicalDelta"))
        assertEquals("unknown", rows(compare(store, b, a)).getValue("private/lost".toByteArray().hex()).getString("status"))
        val skipped = fixture(store, listOf(Item("private", directory = true, skip = 1)))
        assertEquals("unknown", rows(compare(store, a, skipped)).getValue("private/lost".toByteArray().hex()).getString("status"))
    }
    @Test fun directoryTypeReplacementAndSimilarPrefixesStaySeparate() = withStore { store ->
        val a = fixture(store, listOf(Item("a", directory = true), Item("a/file", 3), Item("a-", 2), Item("ab", directory = true), Item("ab/file", 4)))
        val b = fixture(store, listOf(Item("a", 10), Item("a-", 2), Item("ab", directory = true), Item("ab/file", 4)))
        val changes = rows(compare(store, a, b))
        assertEquals("changed", changes.getValue("61").getString("status"))
        assertEquals("deleted", changes.getValue("a/file".toByteArray().hex()).getString("status"))
        assertFalse(changes.containsKey("ab/file".toByteArray().hex()))
        assertEquals(0, compare(store, a, b, "filter" to "growth").getJSONArray("entries").length())
    }
    @Test fun reorderedHardlinksHaveIdenticalOwnersAndNoFalseGrowth() = withStore { store ->
        val items = listOf(Item("z", 17, inode = 999), Item("a", 17, inode = 999), Item("x", 5))
        val a = fixture(store, items); val b = fixture(store, items.reversed())
        val value = compare(store, a, b)
        assertEquals(0, value.getLong("logicalDelta")); assertEquals(0, value.getJSONArray("entries").length())
        for (id in listOf(a, b)) store.readableDatabase.rawQuery("SELECT o.path FROM aggregates a JOIN nodes n ON n.sid=a.sid AND n.id=a.id JOIN nodes o ON o.sid=a.sid AND o.id=a.owner WHERE a.sid=? AND n.name_key='7a'", arrayOf(id)).use {
            assertTrue(it.moveToFirst()); assertEquals("a", it.getBlob(0).toString(Charsets.UTF_8))
        }
    }
    @Test fun compatibilityExplainsEveryBoundaryAndLegacyIdentity() = withStore { store ->
        val a = fixture(store, emptyList()); val b = fixture(store, emptyList())
        assertEquals("ok", compare(store, a, b).getString("status"))
        for ((column, value) in listOf("root" to "/other", "backend" to "root", "identity" to "{}")) {
            val original = store.readableDatabase.rawQuery("SELECT $column FROM sessions WHERE id=?", arrayOf(b)).use { it.moveToFirst(); it.getString(0) }
            store.writableDatabase.execSQL("UPDATE sessions SET $column=? WHERE id=?", arrayOf(value, b))
            assertEquals("incompatible", compare(store, a, b).getString("status"))
            store.writableDatabase.execSQL("UPDATE sessions SET $column=? WHERE id=?", arrayOf(original, b))
        }
        val original = store.readableDatabase.rawQuery("SELECT identity FROM sessions WHERE id=?", arrayOf(b)).use { it.moveToFirst(); it.getString(0) }
        for (key in listOf("rules", "user", "boot")) {
            val identity = JSONObject(original).put(key, if (key == "user") 10 else "different")
            store.writableDatabase.execSQL("UPDATE sessions SET identity=? WHERE id=?", arrayOf(identity.toString(), b))
            assertEquals("incompatible", compare(store, a, b).getString("status"))
        }
        store.writableDatabase.execSQL("UPDATE sessions SET identity=? WHERE id=?", arrayOf(original, b))
        val metrics = store.readableDatabase.rawQuery("SELECT metrics FROM sessions WHERE id=?", arrayOf(b)).use { it.moveToFirst(); it.getString(0) }
        for (key in listOf("rootMountId", "rootFilesystemType")) {
            store.writableDatabase.execSQL("UPDATE sessions SET metrics=? WHERE id=?", arrayOf(JSONObject(metrics).put(key, 999).toString(), b))
            assertEquals("incompatible", compare(store, a, b).getString("status"))
        }
        store.writableDatabase.execSQL("UPDATE sessions SET metrics=? WHERE id=?", arrayOf(metrics, b))
        store.writableDatabase.execSQL("UPDATE sessions SET identity=?,revision=revision+1 WHERE id=?", arrayOf(original, b))
        assertEquals("incompatible", compare(store, a, b).getString("status"))
    }
    @Test fun snapshotHistoryPaginatesEqualCreationTimesWithoutDuplicates() = withStore { store ->
        val ids = (1..23).map { fixture(store, emptyList()) }.toSet()
        store.writableDatabase.execSQL("UPDATE snapshots SET created=123")
        val seen = mutableSetOf<String>(); var token = ""; var pages = 0
        do {
            val page = call(store, "snapshot-list", "cursor" to token, "limit" to 7)
            val list = page.getJSONArray("snapshots")
            for (i in 0 until list.length()) assertTrue(seen.add(list.getJSONObject(i).getString("id")))
            token = page.optString("nextCursor"); pages++
        } while (token.isNotEmpty())
        assertEquals(ids, seen); assertEquals(4, pages)
    }
    @Test fun snapshotStorageEstimatesReflectSavedRecordsAndExcludeFreePages() = withStore { store ->
        // Keep reusable pages in this test database even on devices whose
        // SQLite configuration otherwise vacuums automatically.
        store.writableDatabase.execSQL("PRAGMA auto_vacuum=NONE")
        store.writableDatabase.execSQL("VACUUM")
        // A huge scanned file costs one metadata record; many empty files cost
        // more local index storage despite having no scanned file content.
        val small = fixture(store, listOf(Item("huge", 1L shl 40)))
        val large = fixture(store, (1..80).map { Item("empty-$it") })
        fun sizes(): Map<String, Long> {
            val list = call(store, "snapshot-list").getJSONArray("snapshots")
            return (0 until list.length()).associate { list.getJSONObject(it).let { it.getString("id") to it.getLong("estimatedStorageBytes") } }
        }
        val before = sizes()
        assertTrue(before.getValue(small) > 0)
        assertTrue(before.getValue(large) > before.getValue(small))
        assertTrue(before.getValue(small) < (1L shl 40))
        val db = store.writableDatabase
        db.execSQL("CREATE TABLE disposable_size_test(payload BLOB)")
        db.execSQL("INSERT INTO disposable_size_test VALUES(zeroblob(1048576))")
        db.execSQL("DROP TABLE disposable_size_test")
        val free = db.rawQuery("PRAGMA freelist_count", null).use { it.moveToFirst(); it.getLong(0) }
        val pageSize = db.rawQuery("PRAGMA page_size", null).use { it.moveToFirst(); it.getLong(0) }
        assertTrue("Fixture should leave reusable database pages", free > 0)
        val after = sizes()
        for ((id, bytes) in before) assertTrue(kotlin.math.abs(after.getValue(id) - bytes) <= pageSize)
        // Non-snapshot sessions still own their share of database storage.
        store.begin(UUID.randomUUID().toString(), "/unfinished", "ordinary", Process.myUid(), Process.myPid()).use { it.flush() }
        val currentFree = db.rawQuery("PRAGMA freelist_count", null).use { it.moveToFirst(); it.getLong(0) }
        assertTrue(sizes().values.sum() < db.rawQuery("PRAGMA page_count", null).use { it.moveToFirst(); (it.getLong(0) - currentFree) * pageSize })
    }
    @Test fun unknownPagesKeepNullDeltasAndStableOrdering() = withStore { store ->
        val a = fixture(store, emptyList()); val b = fixture(store, (1..45).map { Item("unknown-$it", known = false) })
        for (sort in listOf("logical", "allocated")) {
            val seen = mutableSetOf<String>(); var token = ""
            do {
                val page = compare(store, a, b, "sort" to sort, "filter" to "unknown", "limit" to 9, "cursor" to token)
                assertEquals("ok", page.getString("status")); assertTrue(page.isNull("logicalDelta"))
                for ((path, row) in rows(page)) { assertTrue(seen.add(path)); assertTrue(row.isNull("logicalDelta")); assertTrue(row.isNull("allocatedDelta")) }
                token = page.optString("nextCursor")
            } while (token.isNotEmpty())
            assertEquals(45, seen.size)
        }
    }
    @Test fun boundedBuildPagesPreserveTiesAndRejectForeignCursors() = withStore { store ->
        val a = fixture(store, emptyList())
        val b = fixture(store, (1..1100).map { Item("file-$it", 8) })
        val firstWork = call(store, "snapshot-compare", "baseId" to a, "targetId" to b)
        assertEquals("building", firstWork.getString("status")); assertTrue(firstWork.getLong("processed") in 1..512)
        for (sort in listOf("logical", "allocated", "path")) {
            val seen = mutableSetOf<String>(); var cursor = ""; var pages = 0
            do {
                val page = compare(store, a, b, "sort" to sort, "limit" to 37, "cursor" to cursor)
                assertEquals("ok", page.getString("status"))
                for (path in rows(page).keys) assertTrue(seen.add(path))
                cursor = page.optString("nextCursor"); pages++
            } while (cursor.isNotEmpty())
            assertEquals(1100, seen.size); assertEquals(30, pages)
        }
        val token = compare(store, a, b, "limit" to 37).getString("nextCursor")
        assertEquals("invalid-query", compare(store, a, b, "cursor" to token).getString("status"))
        assertEquals("invalid-query", compare(store, a, b, "sort" to "bogus").getString("status"))
    }
    @Test fun retainedSnapshotsSurviveReopenAndDeleteClearsOnlySelectedIndex() = withStore { store ->
        val a = fixture(store, listOf(Item("file", 9))); val b = fixture(store, listOf(Item("file", 10)))
        assertEquals("ok", call(store, "snapshot-retain", "snapshotId" to a, "retained" to true).getString("status"))
        store.close()
        assertTrue(call(store, "snapshot-list").getJSONArray("snapshots").let { array -> (0 until array.length()).any { array.getJSONObject(it).getString("id") == a && array.getJSONObject(it).getBoolean("retained") } })
        assertEquals("retained", call(store, "snapshot-delete", "snapshotId" to a).getString("status"))
        compare(store, a, b)
        call(store, "snapshot-retain", "snapshotId" to a, "retained" to false)
        assertEquals("ok", call(store, "snapshot-delete", "snapshotId" to a).getString("status"))
        for (table in listOf("snapshots", "sessions", "nodes", "aggregates", "issues", "hardlinks")) {
            val column = if (table in listOf("sessions", "snapshots")) "id" else "sid"
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE $column=?", arrayOf(a)).use { it.moveToFirst(); assertEquals(0, it.getLong(0)) }
        }
        assertEquals(10, store.preview(b).getLong("logicalBytes"))
        assertEquals(1, call(store, "snapshot-list").getJSONArray("snapshots").length())
    }
    @Test fun rawByteNamesRemainDistinctAndBuildRestartsAfterProcessStateLoss() = withStore { store ->
        val a = fixture(store, listOf(Item("fe", 5, raw = byteArrayOf(0xfe.toByte()))))
        val b = fixture(store, listOf(Item("ff", 3, raw = byteArrayOf(0xff.toByte())), Item("fe", 5, raw = byteArrayOf(0xfe.toByte())), Item("换行\n😀", 2)))
        val value = compare(store, a, b)
        assertEquals(5, value.getLong("logicalDelta"))
        assertEquals("added", rows(value).getValue("ff").getString("status")); assertFalse(rows(value).containsKey("fe"))
        val many = fixture(store, (1..1100).map { Item("item-$it", 4) })
        call(store, "snapshot-compare", "baseId" to a, "targetId" to many)
        val query = JSONObject().put("kind", "snapshot-compare").put("baseId", a).put("targetId", many)
        // A fresh query owner simulates losing the in-memory builder after service death.
        val restarted = SnapshotQueries(store)
        var result: JSONObject
        do { result = restarted.execute(query) } while (result.getString("status") == "building")
        assertEquals("ok", result.getString("status")); assertEquals(1100, result.getJSONObject("counts").getInt("added"))
    }
    @Test fun deepPathsUseSmallCursorsAndBoundedBinderPages() = withStore { store ->
        val items = mutableListOf<Item>(); var path = ""
        repeat(80) { index -> path += (if (path.isEmpty()) "" else "/") + "d".repeat(235) + index; items.add(Item(path, directory = true)) }
        items.add(Item("$path/leaf", 1))
        val a = fixture(store, emptyList()); val b = fixture(store, items)
        var token = ""; val seen = mutableSetOf<String>(); var pages = 0
        do {
            val result = compare(store, a, b, "cursor" to token, "limit" to 100)
            assertEquals("ok", result.getString("status")); assertTrue(result.toString().toByteArray().size < 400000)
            for (key in rows(result).keys) assertTrue(seen.add(key))
            token = result.optString("nextCursor"); assertTrue(token.length < 2048); pages++
        } while (token.isNotEmpty())
        assertEquals(81, seen.size); assertTrue(pages > 1)
    }
    @Test fun schemaThreeUpgradePreservesHistoricalSnapshotsWithoutInventingIdentity() {
        val name = "phase8-migration-${UUID.randomUUID()}.db"; val file = File(context.noBackupFilesDir, name); val id = UUID.randomUUID().toString()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("index-v2.sql").bufferedReader().use { it.readText() }
                for (sql in fixture.split(';')) if (sql.trim().isNotEmpty()) db.execSQL(sql)
                db.execSQL("ALTER TABLE sessions ADD COLUMN volume TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("CREATE INDEX sessions_recent ON sessions(started DESC,id DESC)")
                db.execSQL("INSERT INTO sessions(id,root,backend,state,phase,started,scanner_uid,scanner_pid) VALUES(?,'/old','ordinary','completed','final',1,10000,10000)", arrayOf(id))
                db.execSQL("INSERT INTO snapshots VALUES(?,?,0,1,0,0,1)", arrayOf(id, id)); db.version = 3
            }
            IndexStore(context, name).use { store ->
                assertEquals(4, store.readableDatabase.version)
                val snapshot = call(store, "snapshot-list").getJSONArray("snapshots").getJSONObject(0)
                assertEquals(id, snapshot.getString("id")); assertFalse(snapshot.getBoolean("retained"))
                assertTrue(snapshot.getLong("estimatedStorageBytes") > 0)
                val other = fixture(store, emptyList(), root = "/old")
                assertEquals("incompatible", compare(store, id, other).getString("status"))
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }
}
