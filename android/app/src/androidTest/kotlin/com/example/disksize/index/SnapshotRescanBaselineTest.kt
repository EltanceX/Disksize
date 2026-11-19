package com.example.disksize.index

import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.disksize.scan.NativeBridge
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SnapshotRescanBaselineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun requiresNormalInstalledApp() {
        val config = Class.forName("com.example.disksize.BuildConfig")
        assertFalse("Baseline runner must not use the benchmark main APK", config.getField("SCAN_BENCHMARK").getBoolean(null))
    }

    private fun withStore(block: (IndexStore) -> Unit) {
        val name = "rescan-baseline-${UUID.randomUUID()}.db"
        try { IndexStore(context, name).use(block) }
        finally { SQLiteDatabase.deleteDatabase(File(context.noBackupFilesDir, name)) }
    }

    private data class Item(
        val path: ByteArray,
        val inode: Long,
        val bytes: Long = 0,
        val directory: Boolean = false,
        val links: Long = 1,
        val known: Boolean = true,
        val seconds: Long = 1,
    )

    private fun item(path: String, inode: Long, bytes: Long = 0, directory: Boolean = false, links: Long = 1) =
        Item(path.toByteArray(), inode, bytes, directory, links)

    private fun outside() = listOf(
        item("a-", 20, 11), item("a2", 21, directory = true), item("a2/keep", 22, 13),
        item("outside", 23, directory = true), item("outside/shared", 99, 7, links = 2),
        Item(byteArrayOf(0xff.toByte(), 10), 24, 6),
    )

    private fun before() = listOf(
        item("a", 10, directory = true), item("a/change", 11, 3), item("a/deleted", 12, 5),
        item("a/shared", 99, 7, links = 2),
    ) + outside()

    private fun mixedAfter() = listOf(
        item("a", 10, directory = true), item("a/change", 11, 9), item("a/new", 13, 19),
    ) + outside()

    private fun replay(store: IndexStore, items: List<Item>, extraFlush: Boolean = false): String {
        val session = UUID.randomUUID().toString()
        val ids = mutableMapOf("" to 1L)
        val root = Item(byteArrayOf(), 1, directory = true)
        fun identity(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
        store.begin(session, "/rescan-contract-fixture", "ordinary", Process.myUid(), Process.myPid()).use { writer ->
            for ((index, value) in (listOf(root) + items).withIndex()) {
                val split = value.path.lastIndexOf(47.toByte())
                val parent = if (index == 0) 0 else ids.getValue(if (split < 0) "" else value.path.copyOf(split).hex())
                val name = value.path.copyOfRange(split + 1, value.path.size)
                val id = index + 1L
                val depth = if (index == 0) 0 else value.path.count { it == 47.toByte() } + 1
                writer.node(IndexNode(id, parent, name, byteArrayOf(), if (value.directory) 1 else 2, depth,
                    if (value.directory) 16832 else 33152, 10000, 10000, identity(1), identity(value.inode),
                    1, value.links, value.bytes, 4096, value.seconds, 0, value.known, value.known, 0))
                ids[value.path.hex()] = id
                if (value.directory) writer.directory(id, value.known, value.known, 0)
                if (!value.known) writer.issue(id, 1, 13, "open")
                if (extraFlush) writer.flush()
            }
            writer.flush()
        }
        store.finalize(session, StreamSummary("completed", items.size + 1L, items.count { !it.known }.toLong(), 0, 2, 0, 1, 1)) { false }
        return session
    }

    @Test fun normalizationIgnoresSessionNodeAndRevisionValuesButKeepsRawNamesAndMetadata() = withStore { store ->
        val original = before()
        val reordered = original.takeLast(1) + original.drop(4).dropLast(1) + original.take(4)
        val a = replay(store, original)
        val b = replay(store, reordered, extraFlush = true)
        val baseline = SnapshotBaseline.capture(store, a)
        assertEquals(baseline, SnapshotBaseline.capture(store, b))
        assertEquals("45", baseline.summary["logical"])
        assertEquals("40960", baseline.summary["allocated"])
        assertEquals("7", baseline.row("a/shared")["own_logical"])
        assertEquals("0", baseline.row("outside/shared")["own_logical"])
        assertEquals("a/shared".toByteArray().hex(), baseline.row("outside/shared")["owner_path"])
        assertEquals("ff0a", baseline.row(byteArrayOf(0xff.toByte(), 10))["path"])
        val changed = replay(store, original.map { if (it.inode == 11L) it.copy(seconds = 2) else it })
        assertNotEquals(baseline, SnapshotBaseline.capture(store, changed))
    }

    @Test fun mixedTimeReferenceRetainsOutsideMetadataAndReelectsGlobalHardlinkOwner() = withStore { store ->
        val beforeId = replay(store, before())
        val old = SnapshotBaseline.capture(store, beforeId)
        val after = SnapshotBaseline.capture(store, replay(store, mixedAfter()))
        assertEquals("65", after.summary["logical"])
        assertEquals("40960", after.summary["allocated"])
        assertEquals("28", after.row("a")["total_logical"])
        assertEquals("7", after.row("outside")["total_logical"])
        assertEquals("outside/shared".toByteArray().hex(), after.row("outside/shared")["owner_path"])
        assertEquals("2", after.row("outside/shared")["nlink"])
        val derived = setOf("owner_path", "own_logical", "own_allocated", "total_logical", "total_allocated", "subtree_complete")
        for (value in outside()) {
            assertEquals(old.row(value.path).filterKeys { it !in derived }, after.row(value.path).filterKeys { it !in derived })
        }
        assertEquals(old, SnapshotBaseline.capture(store, beforeId))
    }

    @Test fun unrelatedUnknownScopeRemainsPartialAndIssuesAreNormalizedByPath() = withStore { store ->
        val unknown = Item("outside/denied".toByteArray(), 25, known = false)
        val before = SnapshotBaseline.capture(store, replay(store, before() + unknown))
        val after = SnapshotBaseline.capture(store, replay(store, mixedAfter() + unknown))
        assertEquals("partial", after.summary["state"])
        assertEquals("0", after.summary["complete"])
        assertEquals("1", after.row("a")["subtree_complete"])
        assertEquals(before.issues, after.issues)
        assertEquals("outside/denied".toByteArray().hex(), after.issues.single()["path"])
    }

    private fun scan(store: IndexStore, root: File): String {
        val id = UUID.randomUUID().toString()
        val handle = NativeBridge.nativeCreateScan(root.path.toByteArray())
        val summary = try {
            store.begin(id, root.path, "ordinary", Process.myUid(), Process.myPid()).use { writer ->
                LocalScanPipe.run(handle, id, writer) { _, _, _ -> }
            }
        } finally { NativeBridge.nativeReleaseScan(handle) }
        assertEquals("completed", summary.state)
        assertEquals(0L, summary.issues)
        store.finalize(id, summary) { false }
        return id
    }

    private fun write(file: File, count: Int) {
        FileOutputStream(file).use { it.write(ByteArray(count) { 42 }); it.fd.sync() }
    }

    private fun assertGolden(actual: SnapshotBaseline, expected: JSONObject) {
        val sizes = expected.getJSONObject("logical")
        val directories = expected.getJSONArray("directories").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        val owners = expected.getJSONObject("owners")
        val symlinks = expected.getJSONObject("symlinks")
        assertEquals(sizes.length(), actual.nodes.size)
        assertEquals("completed", actual.summary["state"])
        assertEquals("1", actual.summary["complete"])
        for (path in sizes.keys()) {
            val row = actual.row(path)
            assertEquals(path, sizes.getLong(path).toString(), row["total_logical"])
            assertEquals(path, when { path in directories -> "1"; symlinks.has(path) -> "3"; else -> "2" }, row["kind"])
            assertEquals(path, owners.optString(path, path).toByteArray().hex(), row["owner_path"])
            assertEquals("1", row["subtree_complete"])
            if (symlinks.has(path)) assertEquals(symlinks.getString(path).toByteArray().hex(), row["link"])
        }
        assertEquals(sizes.getLong("").toString(), actual.summary["logical"])
        // The native fixture has no hardlinks: each valid node contributes its own allocation once.
        assertEquals(actual.nodes.sumOf { it.getValue("allocated")!!.toLong() }, actual.summary.getValue("allocated")!!.toLong())
    }

    @Test fun nativeFixedTreeMatchesPortableGoldenBeforeAndAfterControlledChanges() = withStore { store ->
        val root = File(context.filesDir, "rescan-baseline-${UUID.randomUUID()}")
        check(root.mkdir())
        try {
            for (path in listOf("a", "a/replaced", "a-", "a2", "outside", "empty")) check(File(root, path).mkdir())
            for ((path, bytes) in listOf("a/change" to 3, "a/deleted" to 5, "a/shared" to 7, "a/replaced/old" to 2,
                "a-/keep" to 11, "a2/keep" to 13, "outside/keep" to 17)) write(File(root, path), bytes)
            // This device denies app-UID hardlink creation; shared identities are tested by replay above.
            write(File(root, "outside/shared"), 7)
            Os.symlink("../outside/keep", File(root, "a/link").path)
            val golden = InstrumentationRegistry.getInstrumentation().context.assets.open("snapshot-rescan-baseline-v1.json").bufferedReader().use { JSONObject(it.readText()) }
            assertEquals(1, golden.getInt("version"))
            val beforeId = scan(store, root)
            val before = SnapshotBaseline.capture(store, beforeId)
            assertGolden(before, golden.getJSONObject("before"))
            assertEquals(before, SnapshotBaseline.capture(store, scan(store, root)))
            assertTrue(File(root, "a/deleted").delete())
            assertTrue(File(root, "a/shared").delete())
            write(File(root, "a/change"), 9)
            write(File(root, "a/new"), 19)
            assertTrue(File(root, "a/replaced/old").delete())
            assertTrue(File(root, "a/replaced").delete())
            write(File(root, "a/replaced"), 4)
            val after = SnapshotBaseline.capture(store, scan(store, root))
            assertGolden(after, golden.getJSONObject("after"))
            assertEquals(after, SnapshotBaseline.capture(store, scan(store, root)))
            assertEquals(before, SnapshotBaseline.capture(store, beforeId))
            assertEquals("7", before.row("outside/shared")["own_logical"])
            assertEquals("7", after.row("outside/shared")["own_logical"])
            assertTrue(before.summary.getValue("allocated")!!.toLong() >= 0)
            assertTrue(after.summary.getValue("allocated")!!.toLong() >= 0)
        } finally {
            // This unique fixture contains only a known file symlink; never follow it during cleanup.
            File(root, "a/link").delete()
            root.deleteRecursively()
        }
    }
}
