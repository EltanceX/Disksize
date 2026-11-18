package com.example.disksize.index

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.AtomicFile
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.example.disksize.BuildConfig
import com.example.disksize.MainActivity
import com.example.disksize.root.RootBackend
import com.example.disksize.scan.BenchmarkStorage
import com.example.disksize.scan.CoordinatorConnection
import com.example.disksize.scan.CoordinatorService
import com.example.disksize.scan.NativeBridge
import com.example.disksize.scan.ipc.IScanCoordinator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ScanPerformanceTest {
    private val test = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context = test.targetContext
    private val user = Process.myUid() / 100000
    private fun micros(since: Long) = (System.nanoTime() - since) / 1000
    private val size get() = requireNotNull(args.getString("size")).toInt().also {
        require(it in setOf(10_000, 100_000, 1_000_000))
    }
    private val dirs get() = if (size == 10_000) 100 else 1000
    private val filesPerDir get() = size / dirs - 1

    private inner class Fixture {
        val id = UUID.fromString(requireNotNull(args.getString("fixture"))).toString()
        val location = requireNotNull(args.getString("location")).also { require(it in setOf("internal", "external")) }
        private val parent = if (location == "internal") context.filesDir else requireNotNull(context.getExternalFilesDir(null))
        private val home = File(parent, "scan-perf-$id")
        private val marker = File(home, "owner")
        val tree = File(home, "tree")
        private val owner = "scan-perf-v1:$id:$size:$location"

        fun setup() {
            require(home.mkdir()) { "Benchmark fixture already exists or cannot be created" }
            marker.writeText(owner)
            check(tree.mkdir())
            val directoryCount = dirs
            val fileCount = filesPerDir
            repeat(directoryCount) { d ->
                val directory = File(tree, "d$d")
                check(directory.mkdir())
                repeat(fileCount) { f -> check(File(directory, "f$f").createNewFile()) }
            }
        }

        fun verify() {
            check(home.canonicalFile == File(parent.canonicalFile, home.name))
            check(marker.isFile && marker.length() == owner.length.toLong() && marker.readText() == owner) {
                "Fixture is not owned by this run"
            }
        }

        fun cleanup() {
            if (!home.exists()) return
            verify()
            check(home.deleteRecursively())
        }

        fun path(view: String): String {
            verify()
            val path = tree.canonicalPath
            if (view != "emulated-direct") return path
            check(location == "external" && Environment.isExternalStorageEmulated(tree))
            val prefix = "/storage/emulated/$user/"
            check(path.startsWith(prefix))
            return "/data/media/$user/${path.removePrefix(prefix)}"
        }
    }

    private fun report(value: JSONObject) {
        test.sendStatus(2, Bundle().apply { putString("scanPerformance", value.toString()) })
    }

    @Test fun runBenchmark() {
        assumeTrue(args.getString("scanPerformance") == "true")
        check(BuildConfig.SCAN_BENCHMARK) { "Build the optimized profile APK with -PdisksizeBenchmark=true" }
        val action = args.getString("action") ?: "scan"
        if (action in setOf("setup", "cleanup")) {
            val start = System.nanoTime()
            val fixture = Fixture()
            if (action == "setup") fixture.setup() else {
                fixture.cleanup()
                clearIndex()
            }
            report(JSONObject().put("action", action).put("micros", micros(start)).put("sample", manifest()))
            return
        }
        require(action == "scan")
        val mode = requireNotNull(args.getString("mode")).also { require(it in setOf("A", "B", "C", "Replay")) }
        val policy = args.getString("importVariant")?.let(ImportPolicy::benchmark) ?: ImportPolicy.DEFAULT
        val finalizer = args.getString("finalizeVariant")?.let(FinalizePolicy::benchmark) ?: FinalizePolicy.DEFAULT
        val recipe = if (mode == "Replay") FinalizeReplay(args.getString("replayFixture") ?: "empty", dirs, filesPerDir) else null
        val backend = requireNotNull(args.getString("backend")).also { require(it in setOf("ordinary", "root")) }
        val view = requireNotNull(args.getString("view")).also {
            require(it in setOf("internal", "fuse", "emulated-direct", "live-app-data"))
        }
        val live = view == "live-app-data"
        require(!live || (backend == "root" && mode == "C" && args.getString("allowLiveAppData") == "true"))
        require(view != "emulated-direct" || backend == "root")
        require(mode != "Replay" || (backend == "ordinary" && view == "internal"))
        val target = when {
            live -> "/data/data"
            mode == "Replay" -> null
            else -> Fixture().also {
                check(it.location == if (view == "internal") "internal" else "external")
            }.path(view)
        }
        val windowStart = SystemClock.elapsedRealtimeNanos()
        val result = when (mode) {
            "C" -> complete(checkNotNull(target), view, backend, policy, finalizer)
            "Replay" -> replay(checkNotNull(recipe), policy, finalizer)
            else -> stream(checkNotNull(target), backend, mode)
        }
        if (mode == "C" || mode == "Replay") {
            val timings = result.getJSONObject("performance")
            check(timings.getJSONObject("consumer").getString("importVariant") == policy.name)
            check(timings.getJSONObject("finalize").getString("variant") == finalizer.wireName)
            result.put("importVariant", policy.name).put("finalizeVariant", finalizer.wireName)
        }
        val fs = result.optLong("filesystemMagic", -1)
        if (mode != "Replay") {
            check(fs in if (view == "fuse") setOf(0x65735546L) else setOf(0xf2f52010L, 0xef53L)) {
                "Unexpected filesystem magic 0x${fs.toString(16)} for $view"
            }
            check(result.getLong("openFdsAtFinish") == 0L)
            check(result.getInt("scannerUid") == if (backend == "root") 0 else Process.myUid())
        }
        if (live) {
            check(result.getString("state") in setOf("completed", "partial"))
            check(result.getLong("nodes") > 4)
        } else {
            val issues = recipe?.issues ?: 0L
            val state = if (issues == 0L) "completed" else "partial"
            check(result.getString("state") == state && result.getLong("issues") == issues) {
                "Fixed fixture did not complete"
            }
            check(result.getLong("nodes") == size + 1L && result.getLong("logicalBytes") == (recipe?.logical ?: 0L))
            if (recipe != null) check(result.getLong("allocatedBytes") == recipe.allocated)
            val kinds = result.getJSONObject("nodesByKind")
            check(kinds.getLong("directory") == dirs + 1L && kinds.getLong("regular") == dirs.toLong() * filesPerDir)
        }
        report(result.put("reportVersion", 1).put("schemaVersion", IndexStore.SCHEMA_VERSION)
            .put("streamProtocolVersion", IndexProtocol.VERSION).put("mode", mode).put("backend", backend)
            .put("view", view).put("sample", if (live) JSONObject().put("kind", "live")
                else if (recipe != null && recipe.name != "empty") recipe.manifest() else manifest())
            .put("consumerUid", Process.myUid()).put("testPid", Process.myPid())
            .put("deviceWindowStartNanos", windowStart).put("deviceWindowEndNanos", SystemClock.elapsedRealtimeNanos())
            .put("onlineProcessors", Runtime.getRuntime().availableProcessors()))
    }

    private fun manifest(): JSONObject {
        val spec = "empty-tree-v1:root:dirs=$dirs:d0..dN:filesPerDir=$filesPerDir:f0..fN:size=0"
        val hash = MessageDigest.getInstance("SHA-256").digest(spec.toByteArray()).hex()
        return JSONObject().put("kind", "empty-tree-v1").put("recipeSha256", hash)
            .put("nodes", size + 1).put("directories", dirs + 1).put("regularFiles", dirs * filesPerDir)
            .put("depth", 2).put("logicalBytes", 0)
    }

    private fun clearIndex() {
        // The host starts a fresh instrumentation after stopping the package.
        val storage = BenchmarkStorage.context(context)
        val index = File(storage.noBackupFilesDir, BenchmarkStorage.INDEX)
        SQLiteDatabase.deleteDatabase(index)
        check(!index.exists() && !File(index.path + "-wal").exists() && !File(index.path + "-shm").exists())
        val journal = File(storage.noBackupFilesDir, BenchmarkStorage.JOURNAL)
        AtomicFile(journal).delete()
        File(journal.path + ".bak").let { check(!it.exists() || it.delete()) }
        File(storage.noBackupFilesDir, BenchmarkStorage.IMPORT_VARIANT).let { check(!it.exists() || it.delete()) }
        File(storage.noBackupFilesDir, BenchmarkStorage.FINALIZE_VARIANT).let { check(!it.exists() || it.delete()) }
    }

    private class Counter : IndexConsumer {
        var logical = 0L
        var allocated = 0L
        var depth = 0
        override fun node(n: IndexNode) {
            depth = maxOf(depth, n.depth)
            if (!n.valid || n.skip != 0) return
            if (n.kind == 2) logical = Math.addExact(logical, n.size)
            allocated = Math.addExact(allocated, n.allocated)
        }
        override fun directory(node: Long, enumerated: Boolean, complete: Boolean, skip: Int) = Unit
        override fun issue(node: Long, code: Int, errno: Int, op: String) = Unit
        override fun volume(total: Long, free: Long, available: Long, captured: Long) = Unit
        override fun flush(reason: IndexStore.FlushReason) = Unit
    }

    private fun stream(path: String, backend: String, mode: String): JSONObject {
        val root = if (backend == "root") RootBackend(context) else null
        try {
            val rootStart = System.nanoTime()
            val identity = if (root != null) {
                root.request()
                rootReady { root.status }
            } else JSONObject().put("uid", Process.myUid()).put("pid", Process.myPid())
                .put("mountNamespace", namespace())
            val rootMicros = if (root == null) null else micros(rootStart)
            val started = System.nanoTime()
            val cpuBefore = Process.getElapsedCpuTime()
            val result = if (mode == "A") {
                val r = if (root != null) root.benchmarkTraversal(path) else native(path, NativeBridge::nativeBenchmarkTraversal)
                check(r.size == 22 && r[0] == r[1] && r[0] == identity.getLong("uid") && r[2] == identity.getLong("pid"))
                JSONObject().put("state", if (r[3] == 5L) "completed" else if (r[3] == 6L) "partial" else "failed")
                    .put("nodes", r[4]).put("issues", r[5]).put("elapsedMicros", r[6])
                    .put("peakOpenFds", r[7]).put("openFdsAtFinish", r[8]).put("mountId", r[9]).put("filesystemMagic", r[10])
                    .put("logicalBytes", r[11]).put("reportedAllocatedBeforeDedupBytes", r[12]).put("maxDepth", r[13])
                    .put("nodesByKind", JSONObject().apply { kinds.forEachIndexed { i, kind -> put(kind, r[14 + i]) } })
                    .put("producerThreadUserMicros", r[19].takeIf { it >= 0 })
                    .put("producerThreadSystemMicros", r[20].takeIf { it >= 0 }).put("workers", r[21])
            } else {
                val counter = Counter()
                val id = UUID.randomUUID().toString()
                val summary = if (root != null) root.run(id, path, counter) { _, _, _ -> } else {
                    native(path) { LocalScanPipe.run(it, id, counter) { _, _, _ -> } }
                }
                val elapsed = micros(started)
                val performance = checkNotNull(summary.performance)
                JSONObject().put("state", summary.state).put("nodes", summary.nodes).put("issues", summary.issues)
                    .put("elapsedMicros", elapsed).put("scanMicros", summary.elapsed)
                    .put("peakOpenFds", summary.peakFds).put("openFdsAtFinish", summary.finishFds)
                    .put("mountId", summary.mount).put("filesystemMagic", summary.filesystem)
                    .put("logicalBytes", counter.logical).put("reportedAllocatedBeforeDedupBytes", counter.allocated)
                    .put("maxDepth", counter.depth).put("performance", performance)
                    .put("nodesByKind", performance.getJSONObject("producer").getJSONObject("nodesByKind"))
            }
            return result.put("scannerUid", identity.getInt("uid")).put("scannerPid", identity.getInt("pid"))
                .put("mountNamespace", identity.optString("mountNamespace").ifEmpty { null })
                .put("rootReadyMicros", rootMicros).put("testProcessCpuMillis", Process.getElapsedCpuTime() - cpuBefore)
        } finally { root?.close() }
    }

    private fun <T> native(path: String, action: (Long) -> T): T {
        val handle = NativeBridge.nativeCreateScan(path.toByteArray())
        return try { action(handle) } finally { NativeBridge.nativeReleaseScan(handle) }
    }

    private fun complete(path: String, view: String, backend: String, policy: ImportPolicy, finalizer: FinalizePolicy): JSONObject {
        clearIndex()
        val storage = BenchmarkStorage.context(context).noBackupFilesDir
        File(storage, BenchmarkStorage.IMPORT_VARIANT).writeText(policy.name)
        File(storage, BenchmarkStorage.FINALIZE_VARIANT).writeText(finalizer.wireName)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        test.waitForIdleSync()
        val connect = System.nanoTime()
        CoordinatorConnection(context).use { connection ->
            val remote = runBlocking { withTimeout(10_000) { connection.getService() } }
            val connectMicros = micros(connect)
            val rootStart = System.nanoTime()
            if (backend == "root") {
                remote.requestRootAccess()
                rootReady { remote.rootStatus }
            }
            val rootMicros = if (backend == "root") micros(rootStart) else null
            val started = System.nanoTime()
            val source = if (backend == "root") "root" else if (view == "fuse") "external-own" else "own"
            val id = remote.prepareScan(path, source)
            val result = try {
                context.startForegroundService(Intent(context, CoordinatorService::class.java)
                    .setAction(CoordinatorService.ACTION_START).putExtra(CoordinatorService.EXTRA_SESSION, id))
                terminal(remote, id)
            } catch (error: Throwable) {
                remote.cancelScan(id)
                throw error
            }
            val observed = micros(started)
            val performance = result.getJSONObject("performance")
            val servicePid = result.getInt("servicePid")
            val scannerPid = result.getInt("scannerPid")
            val query = JSONObject().put("sessionId", id).put("kind", "large-files").put("limit", 100).toString()
            val queryStart = System.nanoTime()
            val first = JSONObject(remote.queryIndex(query))
            val queryMicros = micros(queryStart)
            check(first.getString("status") == "ok" && !first.getBoolean("provisional"))
            val tailMicros = if (view != "live-app-data") {
                // Empty files tie on logical size, so a lower sentinel on the sort tuple
                // lets the real backward keyset query return the last page directly.
                val cursor = JSONObject().put("version", 2).put("query", "$id|large-files|0|0|logical|100")
                    .put("revision", first.getLong("revision")).put("direction", "before")
                    .put("key", "0").put("lastId", 1)
                val tailQuery = JSONObject(query).put("cursor", Base64.encodeToString(cursor.toString().toByteArray(),
                    Base64.URL_SAFE or Base64.NO_WRAP)).toString()
                val tailStart = System.nanoTime()
                val last = JSONObject(remote.queryIndex(tailQuery))
                val time = micros(tailStart)
                check(last.getString("status") == "ok")
                val headRows = first.getJSONArray("entries")
                val tailRows = last.getJSONArray("entries")
                check(headRows.length() == 100 && tailRows.length() == 100 &&
                    headRows.getJSONObject(99).getLong("id") > tailRows.getJSONObject(0).getLong("id"))
                time
            } else null
            return JSONObject().put("state", result.getString("state")).put("complete", result.getBoolean("complete"))
                .put("nodes", result.getLong("nodeCount")).put("issues", result.getLong("issueCount"))
                .put("logicalBytes", result.getLong("logicalBytes")).put("allocatedBytes", result.getLong("allocatedBytes"))
                .put("scannerUid", result.getInt("scannerUid")).put("scannerPid", scannerPid)
                .put("serviceUid", result.getInt("serviceUid")).put("servicePid", servicePid)
                .put("mountId", result.getLong("rootMountId")).put("filesystemMagic", result.getLong("rootFilesystemType"))
                .put("mountNamespace", result.optJSONObject("rootView")?.optString("mountNamespace") ?: namespace(servicePid))
                .put("peakOpenFds", result.getLong("peakOpenFds")).put("openFdsAtFinish", result.getLong("openFdsAtFinish"))
                .put("nodesByKind", performance.getJSONObject("producer").getJSONObject("nodesByKind"))
                .put("performance", performance).put("elapsedMicros", performance.getJSONObject("publication").getLong("prepareToPublishMicros"))
                .put("clientObservedMicros", observed).put("firstPageMicros", queryMicros).put("lastPageMicros", tailMicros)
                .put("coordinatorBindMicros", connectMicros).put("rootReadyMicros", rootMicros)
                .put("databaseBytes", File(storage, BenchmarkStorage.INDEX).length())
                .put("walBytes", File(storage, BenchmarkStorage.INDEX + "-wal").length())
        }
    }

    private fun terminal(remote: IScanCoordinator, id: String): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 1_800_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val value = JSONObject(remote.scanSnapshot)
            if (value.optString("sessionId") == id && !value.getBoolean("active")) return value
            SystemClock.sleep(50)
        }
        error("Scan timed out")
    }

    private fun rootReady(read: () -> String): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 35_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val value = JSONObject(read())
            when (value.optString("state")) {
                "ready" -> { check(value.getInt("uid") == 0 && value.getInt("effectiveUid") == 0); return value }
                "unavailable" -> error("Root authorization failed: ${value.optString("reason")}")
            }
            SystemClock.sleep(50)
        }
        error("Root authorization timed out")
    }

    private fun namespace(pid: Int = Process.myPid()): String? =
        runCatching { Os.readlink("/proc/$pid/ns/mnt") }.getOrNull()

    private fun replay(recipe: FinalizeReplay, policy: ImportPolicy, finalizer: FinalizePolicy): JSONObject {
        clearIndex()
        val storage = BenchmarkStorage.context(context)
        val id = UUID.randomUUID().toString()
        val directoryCount = dirs
        val fileCount = filesPerDir
        val totalNodes = size + 1L
        val cpu = Process.getElapsedCpuTime()
        val started = System.nanoTime()
        return IndexStore(storage, BenchmarkStorage.INDEX, policy, finalizer).use { store ->
            val performance = JSONObject().put("version", 1)
            val importStarted = System.nanoTime()
            store.begin(id, "/synthetic", "ordinary", Process.myUid(), Process.myPid()).use { writer ->
                recipe.write(writer)
                performance.put("consumer", writer.measurements())
            }
            val importMicros = micros(importStarted)
            val result = store.finalize(id, StreamSummary("completed", totalNodes, 0, 0, 0, 0, 1, 0,
                performance = performance)) { false }
            val elapsed = micros(started)
            val nodeKinds = JSONObject().put("directory", directoryCount + 1).put("regular", directoryCount * fileCount)
            val cpuMillis = Process.getElapsedCpuTime() - cpu
            JSONObject().put("state", result.getString("state")).put("nodes", result.getLong("nodeCount"))
                .put("issues", result.getLong("issueCount")).put("logicalBytes", result.getLong("logicalBytes"))
                .put("allocatedBytes", result.getLong("allocatedBytes")).put("nodesByKind", nodeKinds)
                .put("importElapsedMicros", importMicros).put("elapsedMicros", elapsed)
                .put("scannerUid", Process.myUid()).put("scannerPid", Process.myPid())
                .put("testProcessCpuMillis", cpuMillis)
                .put("databaseBytes", File(storage.noBackupFilesDir, BenchmarkStorage.INDEX).length())
                .put("walBytes", File(storage.noBackupFilesDir, BenchmarkStorage.INDEX + "-wal").length())
                .put("performance", result.getJSONObject("performance"))
                .also { if (recipe.name != "empty") it.put("resultSha256", recipe.resultDigest(store.readableDatabase, id)) }
        }
    }
}
