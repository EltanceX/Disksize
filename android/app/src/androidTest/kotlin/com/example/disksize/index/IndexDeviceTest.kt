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
    @Test fun ordinaryServicePersistsAndQueriesAfterProcessDeath() {
        val root = File(context.filesDir, "phase5-ipc-${UUID.randomUUID()}"); check(root.mkdir())
        val (connection, remote) = service()
        try {
            repeat(300) { File(root, "f$it").writeText("sample") }
            val result = scan(remote, root.path, "own")
            assertEquals("completed", result.getString("state")); assertEquals(301, result.getLong("nodeCount"))
            assertEquals(1800, result.getLong("logicalBytes")); assertEquals(0, result.getLong("openFdsAtFinish"))
            val id = result.getString("sessionId")
            val query = JSONObject().put("sessionId", id).put("kind", "children").put("parentId", 1).toString()
            val first = JSONObject(remote.queryIndex(query))
            assertEquals(100, first.getJSONArray("entries").length())
            Process.sendSignal(result.getInt("servicePid"), 9)
            SystemClock.sleep(400)
            val rebound = runBlocking { connection.getService() }
            val second = JSONObject(rebound.queryIndex(query))
            assertEquals(first.getLong("revision"), second.getLong("revision")); assertFalse(second.getBoolean("provisional"))
            assertEquals("completed", JSONObject(rebound.scanSnapshot).getString("state"))
        } finally { connection.close(); root.deleteRecursively() }
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
            val start = SystemClock.elapsedRealtime()
            val result = scan(remote, fixture.path, if (backend == "root") "root" else "own")
            val elapsed = SystemClock.elapsedRealtime() - start
            assertEquals("completed", result.getString("state")); assertEquals(size + 1L, result.getLong("nodeCount"))
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
                .put("metrics", meta.getJSONObject("metrics")).put("pages", pages).put("files", count)
                .put("firstPageMicros", firstMicros).put("lastPageMicros", lastMicros).put("maxPageMicros", maxMicros)
                .put("servicePid", result.getInt("servicePid")).put("scannerPid", result.getInt("scannerPid"))
                .put("databaseBytes", File(context.noBackupFilesDir, "scan-index.db").length())
                .put("walBytes", File(context.noBackupFilesDir, "scan-index.db-wal").length())
            File(context.filesDir, "phase5-scale-$size-$backend.json").writeText(report.toString())
            Log.i("DiskSize.Phase5", "SCALE_RESULT $report")
        } finally {
            connection.close()
            check(File(root, ".disksize-owned-fixture").readText() == "phase5")
            root.deleteRecursively()
        }
    }
}
