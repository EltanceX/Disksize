package com.example.disksize.index

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.disksize.MainActivity
import com.example.disksize.scan.CoordinatorConnection
import com.example.disksize.scan.CoordinatorService
import com.example.disksize.scan.ipc.IScanCoordinator
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.BitSet
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IndexDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun service(): Pair<CoordinatorConnection, IScanCoordinator> {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(1200)
        val connection = CoordinatorConnection(context)
        return connection to runBlocking { connection.getService() }
    }
    private fun scan(service: IScanCoordinator, root: String, source: String, onProgress: (JSONObject) -> Unit = {}): JSONObject {
        val id = service.prepareScan(root, source)
        context.startForegroundService(Intent(context, CoordinatorService::class.java)
            .setAction(CoordinatorService.ACTION_START).putExtra(CoordinatorService.EXTRA_SESSION, id))
        val deadline = SystemClock.elapsedRealtime() + 1_800_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val value = JSONObject(service.scanSnapshot)
            if (value.getString("sessionId") == id) {
                onProgress(value)
                if (!value.getBoolean("active")) return value
            }
            SystemClock.sleep(100)
        }
        service.cancelScan(id); error("Scan timed out")
    }
    private fun root(service: IScanCoordinator) {
        service.requestRootAccess()
        val deadline = SystemClock.elapsedRealtime() + 35_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = JSONObject(service.rootStatus)
            if (status.getString("state") == "ready") return
            if (status.getString("state") == "unavailable") error("Root test requires the development package's existing root grant: $status")
            SystemClock.sleep(100)
        }
        error("Root authorization timed out")
    }
    private fun assertPerformance(result: JSONObject): JSONObject {
        val performance = result.getJSONObject("performance")
        assertEquals(1, performance.getInt("version"))
        val producer = performance.getJSONObject("producer")
        val consumer = performance.getJSONObject("consumer")
        val types = producer.getJSONObject("nodesByKind")
        assertEquals(result.getLong("nodeCount"), types.keys().asSequence().sumOf { types.getLong(it) })
        assertTrue(producer.getInt("maxNodesPerBatch") in 1..256)
        assertTrue(producer.getLong("wireBytes") > producer.getLong("frames") * 36)
        assertTrue(producer.getLong("frames") >= producer.getLong("nodeBatches") + 2)
        val writes = producer.getLong("pipeWriteMicros")
        assertTrue(producer.getLong("pipePollMicros") in 0..writes)
        assertTrue(producer.getLong("elapsedMicros") >= maxOf(writes, result.getLong("scanMicros")))
        val transactions = consumer.getLong("transactions")
        assertTrue(transactions > 0)
        assertEquals(transactions, listOf("limitCommits", "progressCommits", "summaryCommits", "intervalCommits").sumOf { consumer.getLong(it) })
        assertEquals(ImportPolicy.DEFAULT.name, consumer.getString("importVariant"))
        assertTrue(consumer.getInt("maxActionsPerTransaction") in 1..ImportPolicy.DEFAULT.actionLimit)
        assertEquals(result.getLong("nodeCount") - 1, consumer.getLong("parentCacheHits") + consumer.getLong("parentCacheMisses"))
        val importMicros = consumer.getLong("importMicros")
        assertTrue(consumer.getLong("importCommitMicros") in 0..importMicros)
        assertTrue(importMicros >= result.getLong("importDbMicros"))
        assertTrue(listOf("receiveMicros", "decodeMicros", "progressMicros", "importMicros").sumOf {
            consumer.getLong(it).also { elapsed -> assertTrue(elapsed >= 0) }
        } <= consumer.getLong("elapsedMicros"))
        val finalize = performance.getJSONObject("finalize")
        for (key in listOf("dedupMicros", "initializeMicros", "aggregateMicros", "commitMicros", "previewMicros")) {
            assertTrue(finalize.getLong(key) >= 0)
        }
        val pipeline = performance.getJSONObject("coordinator").getLong("pipelineMicros")
        assertTrue(pipeline >= consumer.getLong("elapsedMicros"))
        val publication = performance.getJSONObject("publication")
        assertTrue(publication.getLong("journalWriteMicros") >= 0 && publication.getLong("journalQueueMicros") >= 0)
        assertTrue(publication.getLong("prepareToPublishMicros") >= pipeline + publication.getLong("journalWriteMicros"))
        return performance
    }

    @Test fun ordinaryServicePersistsAndQueriesAfterProcessDeath() {
        val root = File(context.filesDir, "phase5-ipc-${UUID.randomUUID()}"); check(root.mkdir())
        val (connection, remote) = service()
        try {
            repeat(300) { File(root, "f$it").writeText("sample") }
            val result = scan(remote, root.path, "own")
            assertEquals("completed", result.getString("state")); assertEquals(301, result.getLong("nodeCount"))
            assertEquals(1800, result.getLong("logicalBytes")); assertEquals(0, result.getLong("openFdsAtFinish"))
            val performance = assertPerformance(result)
            val id = result.getString("sessionId")
            val query = JSONObject().put("sessionId", id).put("kind", "children").put("parentId", 1).toString()
            val first = JSONObject(remote.queryIndex(query))
            assertEquals(100, first.getJSONArray("entries").length())
            val stored = first.getJSONObject("metrics").getJSONObject("performance")
            assertEquals(performance.getJSONObject("producer").toString(), stored.getJSONObject("producer").toString())
            assertFalse(stored.getJSONObject("finalize").has("commitMicros"))
            assertFalse(stored.has("publication"))
            Process.sendSignal(result.getInt("servicePid"), 9)
            SystemClock.sleep(400)
            val rebound = runBlocking { connection.getService() }
            val second = JSONObject(rebound.queryIndex(query))
            assertEquals(first.getLong("revision"), second.getLong("revision")); assertFalse(second.getBoolean("provisional"))
            val restored = JSONObject(rebound.scanSnapshot)
            assertEquals("completed", restored.getString("state"))
            assertTrue(restored.getJSONObject("performance").getJSONObject("finalize").has("commitMicros"))
            assertFalse(restored.getJSONObject("performance").has("publication"))
        } finally { connection.close(); root.deleteRecursively() }
    }

    @Test fun rootScanUsesSamePerformanceContract() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("scanPerformanceRoot") == "true")
        val fixture = File(context.filesDir, "scan-performance-${UUID.randomUUID()}")
        check(fixture.mkdir())
        val (connection, remote) = service()
        try {
            repeat(300) { check(File(fixture, "f$it").createNewFile()) }
            root(remote)
            val result = scan(remote, fixture.path, "root")
            assertEquals("completed", result.getString("state"))
            assertEquals(301, result.getLong("nodeCount")); assertEquals(0, result.getInt("scannerUid"))
            assertEquals(Process.myUid(), result.getInt("serviceUid"))
            assertPerformance(result)
        } finally { connection.close(); fixture.deleteRecursively() }
    }
    @Test fun rootBaselineUsesOrdinaryUidIndex() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phase5Root") == "true")
        val (connection, remote) = service()
        try {
            root(remote)
            val result = scan(remote, "/data/local/tmp/disksize-phase0/run-20261115-02", "root")
            assertEquals("completed", result.getString("state")); assertEquals(169, result.getLong("nodeCount"))
            assertEquals(1_073_746_077, result.getLong("logicalBytes")); assertEquals(660480, result.getLong("allocatedBytes"))
            assertEquals(0, result.getInt("scannerUid")); assertEquals(Process.myUid(), result.getInt("serviceUid"))
            val query = JSONObject().put("sessionId", result.getString("sessionId")).put("kind", "large-files")
            val page = JSONObject(remote.queryIndex(query.toString()))
            assertEquals("ok", page.getString("status")); assertFalse(page.getBoolean("provisional"))
            Log.i("DiskSize.Phase5", "ROOT_BASELINE_OK uid=${result.getInt("serviceUid")} scannerUid=0")
        } finally { connection.close() }
    }
    @Test fun rootAppDataUsesGlobalMountNamespace() {
        val arguments = InstrumentationRegistry.getArguments()
        // Explicit opt-in: this test indexes live application metadata, never file contents.
        assumeTrue(arguments.getString("rootAppData") == "true")
        val expectedNamespace = requireNotNull(arguments.getString("rootMountNamespace")) {
            "Pass the device's independently read /proc/1/ns/mnt as rootMountNamespace"
        }
        val fixture = File(context.filesDir, "root-namespace-${UUID.randomUUID()}"); check(fixture.mkdir())
        val (connection, remote) = service()
        try {
            File(fixture, "sample").writeText("namespace")
            val nested = File(fixture, "nested"); check(nested.mkdir())
            File(nested, "sample").writeText("root")
            val ordinary = scan(remote, fixture.path, "own")
            assertEquals("completed", ordinary.getString("state"))
            assertEquals(4, ordinary.getLong("nodeCount"))
            root(remote)
            val view = JSONObject(remote.rootStatus)
            assertEquals(0, view.getInt("uid")); assertEquals(0, view.getInt("effectiveUid"))
            assertEquals(expectedNamespace, view.getString("mountNamespace"))
            val cePath = "/data/user/${Process.myUid() / 100000}"
            val roots = view.getJSONArray("roots")
            val ce = (0 until roots.length()).map { roots.getJSONObject(it) }
                .single { it.getString("requestedPath") == cePath }
            assertTrue(ce.getBoolean("readable"))
            assertNotEquals("tmpfs", ce.getString("filesystem"))
            for (path in listOf(fixture.path, "$cePath/${context.packageName}/files/${fixture.name}")) {
                val sample = scan(remote, path, "root")
                assertEquals("completed", sample.getString("state"))
                assertEquals(ordinary.getLong("nodeCount"), sample.getLong("nodeCount"))
                assertEquals(ordinary.getLong("logicalBytes"), sample.getLong("logicalBytes"))
                assertEquals(ordinary.getLong("allocatedBytes"), sample.getLong("allocatedBytes"))
                assertEquals(0, sample.getLong("openFdsAtFinish"))
            }
            // Reproduce the reported failure through the real Binder/pipe/index path.
            // A live tree may change while being scanned, so Partial is legitimate.
            val result = scan(remote, "/data/data", "root")
            assertTrue(result.getString("state") in setOf("completed", "partial"))
            assertTrue(result.getLong("nodeCount") > 4)
            assertTrue(result.getLong("logicalBytes") > 0)
            assertTrue(result.getLong("allocatedBytes") > 0)
            assertNotEquals(0x01021994L, result.getLong("rootFilesystemType")) // TMPFS_MAGIC
            assertEquals(0, result.getInt("scannerUid"))
            assertEquals(Process.myUid(), result.getInt("serviceUid"))
            assertEquals(0, result.getLong("openFdsAtFinish"))
            val page = JSONObject(remote.queryIndex(JSONObject().put("kind", "children")
                .put("sessionId", result.getString("sessionId")).put("parentId", 1).put("limit", 100).toString()))
            assertEquals("ok", page.getString("status")); assertFalse(page.getBoolean("provisional"))
            val children = page.getJSONArray("entries")
            assertTrue((0 until children.length()).any {
                val child = children.getJSONObject(it)
                child.getString("skipped") == "none" && child.getLong("logicalBytes") > 0
            })
            File(context.filesDir, "root-namespace-result.json").writeText(result.toString())
            Log.i("DiskSize.RootNamespace", "APP_DATA_OK namespace=$expectedNamespace nodes=${result.getLong("nodeCount")} " +
                "logical=${result.getLong("logicalBytes")} allocated=${result.getLong("allocatedBytes")} " +
                "mount=${result.getLong("rootMountId")} state=${result.getString("state")}")
        } finally { connection.close(); fixture.deleteRecursively() }
    }
    @Test fun cancellationAndMidImportDeathKeepProvisionalIndex() {
        val root = File(context.filesDir, "phase5-lifecycle-${UUID.randomUUID()}"); check(root.mkdir())
        val (connection, remote) = service()
        try {
            repeat(100) { d -> val dir = File(root, "d$d"); check(dir.mkdir()); repeat(100) { check(File(dir, "f$it").createNewFile()) } }
            fun start(service: IScanCoordinator): String {
                val id = service.prepareScan(root.path, "own")
                context.startForegroundService(Intent(context, CoordinatorService::class.java)
                    .setAction(CoordinatorService.ACTION_START).putExtra(CoordinatorService.EXTRA_SESSION, id))
                return id
            }
            fun terminal(service: IScanCoordinator, id: String): JSONObject {
                val deadline = SystemClock.elapsedRealtime() + 30_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val value = JSONObject(service.scanSnapshot)
                    if (value.getString("sessionId") == id && !value.getBoolean("active")) return value
                    SystemClock.sleep(20)
                }
                error("Terminal state timed out")
            }
            var id = start(remote)
            val runningDeadline = SystemClock.elapsedRealtime() + 5000
            while (JSONObject(remote.scanSnapshot).getString("state") != "running" && SystemClock.elapsedRealtime() < runningDeadline) SystemClock.sleep(10)
            val cancelStart = System.nanoTime()
            remote.cancelScan(id)
            val cancelled = terminal(remote, id)
            Log.i("DiskSize.ImportCheck", "cancelToTerminalMicros=${(System.nanoTime() - cancelStart) / 1000}")
            assertEquals("cancelled", cancelled.getString("state")); assertTrue(cancelled.getBoolean("provisional"))
            assertEquals("cancelled", JSONObject(remote.queryIndex(JSONObject().put("kind", "session").put("sessionId", id).toString())).getString("state"))
            id = start(remote)
            val deadline = SystemClock.elapsedRealtime() + 15_000; var killed = false
            while (SystemClock.elapsedRealtime() < deadline) {
                val value = JSONObject(remote.scanSnapshot)
                if (value.getString("sessionId") == id && value.getLong("nodeCount") > 256 && value.getBoolean("active")) {
                    val startQuery = System.nanoTime()
                    val page = JSONObject(remote.queryIndex(JSONObject().put("sessionId", id).put("kind", "children")
                        .put("parentId", 1).toString()))
                    val queryMicros = (System.nanoTime() - startQuery) / 1000
                    assertTrue(page.getString("status") in setOf("ok", "stale-cursor"))
                    if (page.getString("status") == "ok") {
                        assertTrue(page.getBoolean("provisional"))
                        assertTrue(page.getLong("nodeCount") > 0)
                    }
                    Log.i("DiskSize.ImportCheck", "activeQueryMicros=$queryMicros")
                    Process.sendSignal(value.getInt("servicePid"), 9); killed = true; break
                }
                SystemClock.sleep(10)
            }
            assertTrue("Did not observe active import", killed); SystemClock.sleep(300)
            val rebound = runBlocking { connection.getService() }
            val recovered = terminal(rebound, id)
            assertEquals("interrupted", recovered.getString("state"))
            val meta = JSONObject(rebound.queryIndex(JSONObject().put("kind", "session").put("sessionId", id).toString()))
            assertEquals("interrupted", meta.getString("state")); assertTrue(meta.getBoolean("provisional"))
            assertTrue(meta.getLong("nodeCount") > 0)
            val complete = scan(rebound, root.path, "own")
            assertEquals("completed", complete.getString("state")); assertEquals(10101, complete.getLong("nodeCount"))
        } finally { connection.close(); root.deleteRecursively() }
    }
    @Test fun realFileScaleBaseline() {
        val size = InstrumentationRegistry.getArguments().getString("phase5Scale")?.toInt() ?: 0
        assumeTrue(size in setOf(10_000, 100_000, 1_000_000))
        val backend = InstrumentationRegistry.getArguments().getString("phase5Backend") ?: "ordinary"
        val root = File(context.filesDir, "phase5-scale-${UUID.randomUUID()}"); check(root.mkdir())
        // The marker is outside the scanned subtree. Cleanup only touches this freshly created directory.
        val fixture = File(root, "tree"); check(fixture.mkdir())
        File(root, ".disksize-owned-fixture").writeText("phase5")
        val (connection, remote) = service()
        try {
            val dirs = if (size == 10_000) 100 else 1000
            val files = size / dirs - 1
            repeat(dirs) { d ->
                val directory = File(fixture, "d$d"); check(directory.mkdir())
                repeat(files) { check(File(directory, "f$it").createNewFile()) }
            }
            if (backend == "root") root(remote)
            Log.i("DiskSize.Phase5", "SCALE_START size=$size backend=$backend")
            val dbBefore = File(context.noBackupFilesDir, "scan-index.db").length()
            val start = SystemClock.elapsedRealtime()
            val result = scan(remote, fixture.path, if (backend == "root") "root" else "own")
            val elapsed = SystemClock.elapsedRealtime() - start
            assertEquals("completed", result.getString("state")); assertEquals(size + 1L, result.getLong("nodeCount"))
            val performance = assertPerformance(result)
            val id = result.getString("sessionId")
            val meta = JSONObject(remote.queryIndex(JSONObject().put("sessionId", id).put("kind", "session").toString()))
            val seen = BitSet(size + 2); var cursor = ""; var pages = 0; var count = 0
            var firstMicros = 0L; var lastMicros = 0L; var maxMicros = 0L
            do {
                val query = JSONObject().put("sessionId", id).put("kind", "large-files").put("limit", 100).put("cursor", cursor)
                val before = System.nanoTime(); val page = JSONObject(remote.queryIndex(query.toString()))
                lastMicros = (System.nanoTime() - before) / 1000
                if (pages == 0) firstMicros = lastMicros
                maxMicros = maxOf(maxMicros, lastMicros); pages++
                assertEquals("ok", page.getString("status"))
                val rows = page.getJSONArray("entries")
                for (i in 0 until rows.length()) {
                    val node = rows.getJSONObject(i).getInt("id"); assertFalse("Duplicate node", seen[node]); seen.set(node); count++
                }
                cursor = page.optString("nextCursor")
            } while (cursor.isNotEmpty())
            assertEquals(dirs * files, count)
            val report = JSONObject().put("size", size + 1).put("backend", backend).put("totalMillis", elapsed)
                .put("metrics", meta.getJSONObject("metrics")).put("performance", performance).put("pages", pages).put("files", count)
                .put("firstPageMicros", firstMicros).put("lastPageMicros", lastMicros).put("maxPageMicros", maxMicros)
                .put("servicePid", result.getInt("servicePid")).put("scannerPid", result.getInt("scannerPid"))
                .put("databaseBytes", File(context.noBackupFilesDir, "scan-index.db").length())
                .put("databaseDeltaBytes", File(context.noBackupFilesDir, "scan-index.db").length() - dbBefore)
                .put("walBytes", File(context.noBackupFilesDir, "scan-index.db-wal").length())
            val memory = try { File("/proc/${result.getInt("servicePid")}/status").readText() } catch (_: Exception) { "" }
            report.put("serviceVmHwmKiB", Regex("VmHWM:\\s+(\\d+)").find(memory)?.groupValues?.get(1)?.toLong() ?: 0)
            File(context.filesDir, "phase5-scale-$size-$backend.json").writeText(report.toString())
            Log.i("DiskSize.Phase5", "SCALE_RESULT $report")
        } finally {
            connection.close()
            check(File(root, ".disksize-owned-fixture").readText() == "phase5")
            root.deleteRecursively()
        }
    }
}
