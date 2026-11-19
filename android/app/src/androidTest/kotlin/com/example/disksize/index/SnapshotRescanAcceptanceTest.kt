package com.example.disksize.index

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.disksize.BuildConfig
import com.example.disksize.scan.CoordinatorConnection
import com.example.disksize.scan.CoordinatorService
import com.example.disksize.scan.ipc.IScanCoordinator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in acceptance on fixed DE storage; never opens the user's normal database. */
@RunWith(AndroidJUnit4::class)
class SnapshotRescanAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val storage get() = context.createDeviceProtectedStorageContext()
    private val control get() = File(storage.noBackupFilesDir, "snapshot-rescan-test-checkpoint.json")
    private val reached get() = File(storage.noBackupFilesDir, "snapshot-rescan-test-checkpoint.reached")
    private lateinit var fixture: File
    private lateinit var connection: CoordinatorConnection
    private lateinit var remote: IScanCoordinator
    private val snapshots = mutableListOf<String>()
    private val tasks = mutableListOf<String>()

    @Before fun isolatedHost() {
        check(BuildConfig.RESCAN_TEST_HOST && !BuildConfig.SCAN_BENCHMARK)
        check(InstrumentationRegistry.getArguments().getString("snapshotRescanAcceptance") == "true")
        control.delete(); reached.delete()
        fixture = File(context.filesDir, "rescan-acceptance-${UUID.randomUUID()}")
        check(File(fixture, "small").mkdirs())
        File(fixture, "small/old").writeText("old")
        instrumentation.openBenchmarkActivity()
        connection = CoordinatorConnection(context)
        remote = runBlocking { connection.getService() }
    }

    @After fun cleanOwnedData() {
        control.delete(); reached.delete()
        if (::remote.isInitialized) {
            for (id in tasks) try { remote.cancelSnapshotRescan(id); awaitTask(id) } catch (_: Exception) { }
            for (id in snapshots.asReversed()) try {
                waitUntil { query("snapshot-delete", "snapshotId" to id).optString("status") == "ok" }
            } catch (_: Exception) { }
        }
        if (::connection.isInitialized) connection.close()
        if (::fixture.isInitialized) {
            check(fixture.parentFile == context.filesDir && fixture.name.matches(Regex("rescan-acceptance-[0-9a-f-]{36}")))
            check(fixture.deleteRecursively())
        }
    }

    private fun query(kind: String, vararg args: Pair<String, Any>): JSONObject = JSONObject(remote.queryIndex(
        JSONObject().put("kind", kind).also { q -> args.forEach { q.put(it.first, it.second) } }.toString()))
    private fun session(id: String) = query("session", "sessionId" to id)
    private fun children(id: String, parent: Long = 1) = query("children", "sessionId" to id, "parentId" to parent, "limit" to 100)
    private fun content(page: JSONObject): String = page.also { it.remove("latestRescanTaskId") }.toString()
    private fun target(id: String, name: String): Long {
        val rows = children(id).getJSONArray("entries")
        return (0 until rows.length()).map(rows::getJSONObject).single { it.getString("nameHex") == name.toByteArray().hex() }.getLong("id")
    }
    private fun waitUntil(timeout: Long = 180_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) { check(SystemClock.elapsedRealtime() < deadline) { "Acceptance wait timed out" }; SystemClock.sleep(20) }
    }
    private fun foreground(id: String) {
        context.startForegroundService(Intent(context, CoordinatorService::class.java).setAction(CoordinatorService.ACTION_START)
            .putExtra(CoordinatorService.EXTRA_SESSION, id))
    }
    private fun full(): String {
        instrumentation.openBenchmarkActivity()
        val id = remote.prepareScan(fixture.path, "own"); snapshots.add(id); foreground(id)
        waitUntil { JSONObject(remote.scanSnapshot).let { it.optString("sessionId") == id && !it.optBoolean("active") } }
        assertEquals("completed", JSONObject(remote.scanSnapshot).getString("state"))
        return id
    }
    private fun prepare(id: String, node: Long): String {
        val response = JSONObject(remote.prepareSnapshotRescan(id, node, session(id).getLong("revision")))
        assertEquals(response.toString(), "ok", response.getString("status"))
        return response.getString("taskId").also(tasks::add)
    }
    private fun awaitTask(id: String): JSONObject {
        var result = JSONObject()
        waitUntil { result = JSONObject(remote.getSnapshotRescan(id)); result.optString("status") == "ok" && !result.optBoolean("active") }
        return result
    }
    private fun settle() { waitUntil { query("snapshot-list").optString("status") == "ok" }; SystemClock.sleep(100) }
    private fun hold(id: String, point: String) {
        reached.delete()
        control.writeText(JSONObject().put("taskId", id).put("point", point).toString())
        foreground(id)
        waitUntil(30_000) { reached.isFile && reached.readText() == "$id:$point" }
    }
    private fun restart() {
        val d = remote.nativeDiagnostics
        assertEquals(Process.myUid().toLong(), d.processUid)
        assertNotEquals(Process.myPid().toLong(), d.processId)
        assertEquals("${context.packageName}:scanner", d.processName)
        Process.sendSignal(d.processId.toInt(), 9)
        control.delete(); reached.delete(); SystemClock.sleep(600)
        remote = runBlocking { connection.getService() }; remote.scanSnapshot
    }

    @Test fun realProcessDeathBeforeAndAfterCommitHasExactlyOneDurableVersion() {
        for (point in listOf("OLD_REMOVED", "BEFORE_COMMIT", "AFTER_COMMIT")) {
            File(fixture, "small/new").delete(); File(fixture, "small/old").writeText("old")
            val id = full(); val node = target(id, "small"); val before = session(id)
            val old = content(children(id, node))
            File(fixture, "small/old").delete(); File(fixture, "small/new").writeText("new-content")
            val job = prepare(id, node); hold(job, point)
            if (point != "AFTER_COMMIT") assertEquals(old, content(children(id, node)))
            restart()
            val result = awaitTask(job); val after = session(id)
            val committed = point == "AFTER_COMMIT"
            assertEquals(if (committed) "completed" else "interrupted", result.getString("state"))
            assertTrue(result.getBoolean("stagingCleaned"))
            assertEquals(before.getLong("revision") + if (committed) 1 else 0, after.getLong("revision"))
            assertEquals(if (committed) "new".toByteArray().hex() else "old".toByteArray().hex(),
                children(id, node).getJSONArray("entries").getJSONObject(0).getString("nameHex"))
            if (!committed) assertEquals(old, content(children(id, node)))
            else {
                assertEquals("already-completed", JSONObject(remote.cancelSnapshotRescan(job)).getString("status"))
                assertEquals(after.getLong("revision"), JSONObject(remote.scanSnapshot).getLong("contentRevision"))
            }
            restart(); assertEquals(after.getLong("revision"), session(id).getLong("revision"))
            IndexStore(storage, "snapshot-rescan-service-test-v1.db").use {
                assertEquals("ok", it.readableDatabase.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst(); c.getString(0) })
                assertEquals(0, it.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { c -> c.count })
            }
            settle()
        }
    }

    @Test fun cancellationDuringRealApplyReleasesReadersAndRollsBack() {
        val id = full(); val node = target(id, "small"); val before = content(children(id, node))
        File(fixture, "small/old").delete(); File(fixture, "small/new").writeText("new")
        val job = prepare(id, node); hold(job, "NODES_INSERTED")
        val start = SystemClock.elapsedRealtime()
        assertEquals(before, content(children(id, node)))
        assertTrue(SystemClock.elapsedRealtime() - start < 2000)
        assertEquals("cancellation-requested", JSONObject(remote.cancelSnapshotRescan(job)).getString("status"))
        control.delete()
        assertEquals("cancelled", awaitTask(job).getString("state")); settle()
        assertEquals(before, content(children(id, node)))
    }

    /** One 100k history, then a million-node history: real native scans, no synthetic database rows. */
    @Test fun measuredSmallLargeAndMillionNodeHistoricalUpdates() {
        val reports = JSONArray()
        val started = System.nanoTime()
        fun record(value: JSONObject) {
            reports.put(value)
            File(storage.noBackupFilesDir, "snapshot-rescan-acceptance-performance.json")
                .writeText(JSONObject().put("samples", reports).put("complete", false).toString(2))
        }
        for (d in 0 until 100) {
            val directory = File(fixture, "large/d$d"); check(directory.mkdirs())
            for (f in 0 until 999) check(File(directory, "f$f").createNewFile())
        }
        var id = full()
        record(measure(id, "small", "100k-history-small"))
        record(measure(id, "large", "100k-history-large"))
        for (d in 0 until 900) {
            val directory = File(fixture, "outside/d$d"); check(directory.mkdirs())
            for (f in 0 until 999) check(File(directory, "f$f").createNewFile())
        }
        id = full()
        record(measure(id, "small", "1m-history-small"))
        record(measure(id, "large", "1m-history-large"))
        val result = JSONObject().put("samples", reports).put("fixtureAndMeasurementMillis", (System.nanoTime() - started) / 1_000_000)
            .put("serviceUid", remote.nativeDiagnostics.processUid).put("samplingIntervalMillis", 50).put("complete", true)
        File(storage.noBackupFilesDir, "snapshot-rescan-acceptance-performance.json").writeText(result.toString(2))
        println("RESCAN_ACCEPTANCE $result")
    }

    private fun measure(id: String, scope: String, label: String): JSONObject {
        settle(); instrumentation.openBenchmarkActivity()
        val node = target(id, scope); val before = session(id)
        val oldLogical = query("detail", "sessionId" to id, "nodeId" to node).getJSONObject("detail").getLong("logicalBytes")
        val added = File(fixture, "$scope/acceptance-added"); added.writeText(label)
        val samples = JSONArray(); val queries = JSONArray(); val running = AtomicBoolean(true)
        val executor = Executors.newSingleThreadExecutor()
        val pid = remote.nativeDiagnostics.processId
        val db = File(storage.noBackupFilesDir, "snapshot-rescan-service-test-v1.db")
        val job = prepare(id, node)
        reached.delete()
        control.writeText(JSONObject().put("taskId", job).put("point", "MEASURE").toString())
        val begin = System.nanoTime()
        val sampler = executor.submit {
            while (running.get()) {
                val sample = JSONObject().put("elapsedMillis", (System.nanoTime() - begin) / 1_000_000)
                    .put("databaseBytes", db.length()).put("walBytes", File(db.path + "-wal").length())
                val memory = File("/proc/$pid/status").readLines()
                for (key in listOf("VmRSS", "VmHWM")) {
                    memory.firstOrNull { it.startsWith("$key:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.let { sample.put("${key}KiB", it) }
                }
                val descriptors = File("/proc/$pid/fd").listFiles()
                var temporaryBytes = 0L
                if (descriptors != null) for (descriptor in descriptors) {
                    try {
                        if (android.system.Os.readlink(descriptor.path).contains("etilqs_"))
                            temporaryBytes += android.system.Os.stat(descriptor.path).st_size
                    } catch (_: Exception) { }
                }
                sample.put("fdCount", descriptors?.size ?: -1).put("sqliteTemporaryBytes", temporaryBytes)
                    .put("temporaryDescriptorsReadable", descriptors != null)
                samples.put(sample)
                val time = System.nanoTime()
                val page = children(id, node)
                queries.put(JSONObject().put("micros", (System.nanoTime() - time) / 1000).put("status", page.getString("status"))
                    .put("revision", page.optLong("revision", -1)))
                Thread.sleep(50)
            }
        }
        val result: JSONObject
        try { foreground(job); result = awaitTask(job) }
        finally { running.set(false); sampler.get(10, TimeUnit.SECONDS); executor.shutdownNow() }
        val elapsed = (System.nanoTime() - begin) / 1000
        assertEquals(result.toString(), "completed", result.getString("state")); settle()
        assertEquals(before.getLong("revision") + 1, session(id).getLong("revision"))
        assertEquals(oldLogical + label.length, query("detail", "sessionId" to id, "nodeId" to node).getJSONObject("detail").getLong("logicalBytes"))
        val checkpoints = JSONObject()
        reached.readLines().filter { it.contains(':') }.forEach {
            val parts = it.split(':'); checkpoints.put(parts[0], parts[1].toLong())
        }
        control.delete(); reached.delete()
        return IndexStore(storage, "snapshot-rescan-service-test-v1.db").use { store ->
            val record = store.rescans.get(job)!!
            JSONObject().put("label", label).put("snapshotNodes", before.getLong("nodeCount")).put("subtreeNodes", record.nodes)
                .put("endToEndMicros", elapsed).put("scanAndStageMillis", record.scanned - record.started)
                .put("applyUntilMarkerMillis", record.ended - record.scanned).put("mergeMetrics", JSONObject(record.metrics))
                .put("streamSummary", JSONObject(record.summary)).put("resourceSamples", samples).put("querySamples", queries)
                .put("checkpointNanos", checkpoints)
                .put("commitMicros", (checkpoints.getLong("AFTER_COMMIT") - checkpoints.getLong("BEFORE_COMMIT")) / 1000)
                .put("stagingRowsAfter", store.readableDatabase.rawQuery("SELECT COUNT(*) FROM rescan_nodes WHERE task_id=?", arrayOf(job)).use { c -> c.moveToFirst(); c.getLong(0) })
        }.also { check(added.delete()) }
    }
}
