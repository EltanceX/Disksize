package com.example.disksize.index

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.disksize.BuildConfig
import com.example.disksize.MainActivity
import com.example.disksize.bridge.ScannerPlatformApi
import com.example.disksize.scan.CoordinatorConnection
import com.example.disksize.scan.CoordinatorService
import com.example.disksize.scan.ipc.IScanCoordinator
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SnapshotRescanServiceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var fixture: File
    private lateinit var connection: CoordinatorConnection
    private lateinit var remote: IScanCoordinator
    private val scans = mutableListOf<String>()
    private val tasks = mutableListOf<String>()

    @Before fun startIsolatedHost() {
        check(BuildConfig.RESCAN_TEST_HOST && !BuildConfig.SCAN_BENCHMARK) { "Requires the isolated, optimized test host" }
        fixture = File(context.filesDir, "rescan-service-${UUID.randomUUID()}"); check(fixture.mkdir())
        check(File(fixture, "a").mkdir()); check(File(fixture, "outside").mkdir())
        File(fixture, "outside/keep").writeText("outside")
        instrumentation.openBenchmarkActivity()
        connection = CoordinatorConnection(context)
        remote = runBlocking { connection.getService() }
    }

    @After fun cleanupOwnedFixtures() {
        if (::remote.isInitialized) {
            for (id in tasks) try {
                if (JSONObject(remote.cancelSnapshotRescan(id)).optString("status") != "not-found") awaitTask(id)
            } catch (_: Exception) { }
            for (id in scans.asReversed()) try {
                val deadline = SystemClock.elapsedRealtime() + 5000
                do {
                    val result = query("snapshot-delete", "snapshotId" to id)
                    if (result.optString("status") != "busy") break
                    SystemClock.sleep(20)
                } while (SystemClock.elapsedRealtime() < deadline)
            } catch (_: Exception) { }
        }
        if (::connection.isInitialized) connection.close()
        if (::fixture.isInitialized) {
            val shell = Shell.getCachedShell()
            if (shell?.isRoot == true) {
                check(fixture.parentFile == context.filesDir && fixture.name.matches(Regex("rescan-service-[0-9a-f-]{36}")))
                check(shell.newJob().add("rm -rf -- '${fixture.path}'").exec().isSuccess)
            } else fixture.deleteRecursively()
        }
    }

    private fun query(kind: String, vararg values: Pair<String, Any>): JSONObject = JSONObject(remote.queryIndex(
        JSONObject().put("kind", kind).also { q -> values.forEach { q.put(it.first, it.second) } }.toString()))
    private fun session(id: String) = query("session", "sessionId" to id)
    private fun children(id: String, node: Long = 1) = query("children", "sessionId" to id, "parentId" to node, "limit" to 100)
    private fun node(id: String, name: ByteArray, parent: Long = 1): Long {
        val rows = children(id, parent).getJSONArray("entries")
        return (0 until rows.length()).map(rows::getJSONObject).single { it.getString("nameHex") == name.hex() }.getLong("id")
    }
    private fun foreground(id: String) = context.startForegroundService(Intent(context, CoordinatorService::class.java)
        .setAction(CoordinatorService.ACTION_START).putExtra(CoordinatorService.EXTRA_SESSION, id))
    private fun full(root: Boolean = false): String {
        val id = remote.prepareScan(fixture.path, if (root) "root" else "own"); scans.add(id); foreground(id)
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val result = JSONObject(remote.scanSnapshot)
            if (result.optString("sessionId") == id && !result.optBoolean("active")) {
                assertEquals(result.toString(), "completed", result.getString("state"))
                return id
            }
            SystemClock.sleep(20)
        }
        remote.cancelScan(id); error("Full fixture scan timed out")
    }
    private fun prepare(id: String, target: Long = node(id, "a".toByteArray()), rev: Long = session(id).getLong("revision")): JSONObject {
        val value = JSONObject(remote.prepareSnapshotRescan(id, target, rev))
        value.optString("taskId").takeIf { it.isNotEmpty() }?.let(tasks::add)
        return value
    }
    private fun start(id: String, target: Long = node(id, "a".toByteArray())): String {
        val value = prepare(id, target); assertEquals(value.toString(), "ok", value.getString("status"))
        val task = value.getString("taskId"); foreground(task); return task
    }
    private fun awaitTask(id: String, predicate: (JSONObject) -> Boolean = { !it.getBoolean("active") }): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        var last = JSONObject()
        while (SystemClock.elapsedRealtime() < deadline) {
            last = JSONObject(remote.getSnapshotRescan(id))
            if (last.optString("status") == "ok" && predicate(last)) return last
            SystemClock.sleep(10)
        }
        error("Task timed out: $last")
    }
    private fun readyForNext() {
        // Admission can remain busy for the brief post-commit journal publication interval.
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (query("snapshot-list").optString("status") == "ok") { SystemClock.sleep(50); return }
            SystemClock.sleep(20)
        }
    }
    private fun restart() {
        val diagnostic = remote.nativeDiagnostics
        assertEquals(Process.myUid().toLong(), diagnostic.processUid)
        assertNotEquals(Process.myPid().toLong(), diagnostic.processId)
        assertEquals("${context.packageName}:scanner", diagnostic.processName)
        Process.sendSignal(diagnostic.processId.toInt(), 9)
        SystemClock.sleep(600)
        remote = runBlocking { connection.getService() }
        remote.scanSnapshot
    }
    private fun root() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("snapshotRescanRoot") == "true")
        remote.requestRootAccess()
        val deadline = SystemClock.elapsedRealtime() + 35_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = JSONObject(remote.rootStatus)
            if (status.optString("state") == "ready") {
                assertEquals(0, status.getInt("uid")); assertEquals(0, status.getInt("effectiveUid"))
                assertEquals(Process.myUid() / 100000, status.getInt("userId")); return
            }
            check(status.optString("state") != "unavailable") { "Existing app Root grant required: $status" }
            SystemClock.sleep(100)
        }
        error("Root request timed out")
    }

    @Test fun platformBridgeUpdatesLatestJournalAndInvalidatesDirectoryCursor() {
        File(fixture, "a/one").writeText("1"); File(fixture, "a/two").writeText("22")
        val id = full(); val target = node(id, "a".toByteArray()); val before = session(id)
        val cursor = query("children", "sessionId" to id, "parentId" to target, "limit" to 1).getString("nextCursor")
        check(File(fixture, "a/one").delete()); File(fixture, "a/new").writeText("123456")
        val activity = AtomicReference<MainActivity>()
        instrumentation.runOnMainSync {
            activity.set(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single())
        }
        ScannerPlatformApi(activity.get()).use { api ->
            val prepared = JSONObject(runBlocking { api.startSnapshotRescan(id, target, before.getLong("revision")) })
            assertEquals("ok", prepared.getString("status")); val task = prepared.getString("taskId"); tasks.add(task)
            assertEquals("completed", awaitTask(task).getString("state")); readyForNext()
            val after = session(id)
            assertEquals(before.getLong("revision") + 1, after.getLong("revision"))
            assertEquals(before.getLong("startedAtMillis"), after.getLong("startedAtMillis"))
            assertEquals(before.getLong("endedAtMillis"), after.getLong("endedAtMillis"))
            assertEquals(15L, JSONObject(remote.scanSnapshot).getLong("logicalBytes"))
            assertEquals(after.getLong("revision"), JSONObject(remote.scanSnapshot).getLong("contentRevision"))
            assertEquals("stale-cursor", query("children", "sessionId" to id, "parentId" to target, "limit" to 1, "cursor" to cursor).getString("status"))
            assertEquals("already-completed", JSONObject(runBlocking { api.cancelSnapshotRescan(task) }).getString("status"))
            assertEquals("completed", JSONObject(runBlocking { api.getSnapshotRescan(task) }).getString("state"))
        }
        restart()
        assertEquals(15L, JSONObject(remote.scanSnapshot).getLong("logicalBytes"))
    }

    @Test fun refreshingHistoricalSnapshotNeverReplacesANewerFullScanJournal() {
        File(fixture, "a/old").writeText("old")
        val old = full(); val newer = full(); val journal = remote.scanSnapshot
        File(fixture, "a/old").writeText("changed")
        val task = prepare(old).getString("taskId")
        assertEquals("busy", query("snapshot-delete", "snapshotId" to newer).getString("status"))
        assertEquals("busy", query("snapshot-compare", "baseId" to old, "targetId" to newer).getString("status"))
        foreground(task)
        assertEquals("completed", awaitTask(task).getString("state")); readyForNext()
        assertEquals(journal, remote.scanSnapshot); assertEquals(newer, JSONObject(remote.scanSnapshot).getString("sessionId"))
        assertEquals(10L, query("detail", "sessionId" to newer, "nodeId" to 1).getJSONObject("detail").getLong("logicalBytes"))
        restart(); assertEquals(newer, JSONObject(remote.scanSnapshot).getString("sessionId"))
    }

    @Test fun preparingOwnsTheWriteSlotAndFailedForegroundStartAndCancellationAreDurable() {
        val id = full(); val journal = remote.scanSnapshot; val before = session(id).toString()
        val task = prepare(id).getString("taskId")
        assertEquals("busy", prepare(id).getString("status"))
        assertEquals("busy", query("snapshot-retain", "snapshotId" to id, "retained" to true).getString("status"))
        assertEquals("busy", query("snapshot-delete", "snapshotId" to id).getString("status"))
        try { remote.prepareScan(fixture.path, "own"); fail("Full scan admitted during rescan") } catch (_: IllegalStateException) { }
        remote.rejectPreparedScan(task, "foreground-start-denied")
        assertEquals("failed", awaitTask(task).getString("state")); readyForNext()
        val cancelled = prepare(id).getString("taskId")
        assertEquals("cancellation-requested", JSONObject(remote.cancelSnapshotRescan(cancelled)).getString("status"))
        assertEquals("cancelled", awaitTask(cancelled).getString("state")); readyForNext()
        val after = session(id)
        assertEquals(cancelled, after.getString("latestRescanTaskId"))
        after.remove("latestRescanTaskId") // Discovery changes; the original snapshot content does not.
        assertEquals(before, after.toString()); assertEquals(journal, remote.scanSnapshot)
    }

    @Test fun admissionKeepsInvalidStaleRetainedAndNonDirectoryErrorsStructured() {
        File(fixture, "file").writeText("f"); val id = full(); val rev = session(id).getLong("revision")
        assertEquals("invalid-request", JSONObject(remote.prepareSnapshotRescan("bad", -1, 0)).getString("status"))
        assertEquals("not-found", JSONObject(remote.getSnapshotRescan(UUID.randomUUID().toString())).getString("status"))
        assertEquals("invalid-request", JSONObject(remote.cancelSnapshotRescan("bad")).getString("status"))
        assertEquals("stale-revision", prepare(id, rev = rev - 1).getString("status")); readyForNext()
        assertEquals("not-directory", prepare(id, node(id, "file".toByteArray())).getString("status")); readyForNext()
        assertEquals("ok", query("snapshot-retain", "snapshotId" to id, "retained" to true).getString("status"))
        assertEquals("retained", prepare(id).getString("status")); readyForNext()
        assertEquals("ok", query("snapshot-retain", "snapshotId" to id, "retained" to false).getString("status"))
        assertEquals(rev, session(id).getLong("revision"))
    }

    @Test fun incompatiblePersistedUserBootAndRulesNeverDispatchAProducer() {
        val id = full(); val before = session(id); val original = AtomicReference<String>()
        fun writeIdentity(value: String) = IndexStore(context.createDeviceProtectedStorageContext(), "snapshot-rescan-service-test-v1.db").use {
            it.writableDatabase.execSQL("UPDATE sessions SET identity=? WHERE id=?", arrayOf(value, id))
        }
        IndexStore(context.createDeviceProtectedStorageContext(), "snapshot-rescan-service-test-v1.db").use { store ->
            store.readableDatabase.rawQuery("SELECT identity FROM sessions WHERE id=?", arrayOf(id)).use {
                assertTrue(it.moveToFirst()); original.set(it.getString(0))
            }
        }
        try {
            for ((key, value) in listOf("boot" to "another-boot", "rules" to "another-rule", "user" to 999)) {
                writeIdentity(JSONObject(original.get()).put(key, value).toString())
                val result = prepare(id); assertEquals("incompatible-identity", result.getString("status"))
                val job = awaitTask(result.getString("taskId")); assertEquals("failed", job.getString("state"))
                assertEquals(0L, job.getLong("nodeCount")); assertTrue(job.getBoolean("stagingCleaned")); readyForNext()
            }
            assertEquals(before.getLong("revision"), session(id).getLong("revision"))
        } finally { writeIdentity(original.get()) }
    }

    @Test fun missingTypeChangesAndReplacedAncestorsCannotRedirectTheScan() {
        check(File(fixture, "a/b").mkdir()); File(fixture, "a/b/old").writeText("old")
        val id = full(); val target = node(id, "a".toByteArray()); val before = session(id).getLong("revision")
        check(File(fixture, "a").renameTo(File(fixture, "detached")))
        assertEquals("target-missing", awaitTask(start(id, target)).getString("error")); readyForNext()
        Os.symlink("outside", File(fixture, "a").path)
        assertEquals("target-type-changed", awaitTask(start(id, target)).getString("error")); readyForNext()
        check(File(fixture, "a").delete()); File(fixture, "a").writeText("file")
        assertEquals("target-type-changed", awaitTask(start(id, target)).getString("error")); readyForNext()
        check(File(fixture, "a").delete()); check(File(fixture, "a/b").mkdirs())
        val descendant = node(id, "b".toByteArray(), target)
        assertEquals("incompatible-identity", awaitTask(start(id, descendant)).getString("error")); readyForNext()
        assertEquals(before, session(id).getLong("revision"))
        File(fixture, "a/new").writeText("new directory")
        assertEquals("completed", awaitTask(start(id, target)).getString("state")); readyForNext()
        assertEquals(target, node(id, "a".toByteArray()))
    }

    @Test fun cancellingOrdinaryProducerAndKillingCoordinatorPreserveTheOldSnapshot() {
        val id = full(); val before = session(id).getLong("revision")
        repeat(30_000) { check(File(fixture, "a/f$it").createNewFile()) }
        var task = start(id)
        awaitTask(task) { it.getLong("nodeCount") >= 4096 && it.getBoolean("active") }
        remote.cancelSnapshotRescan(task)
        assertEquals("cancelled", awaitTask(task).getString("state")); readyForNext()
        assertEquals(before, session(id).getLong("revision"))
        task = start(id); awaitTask(task) { it.getLong("nodeCount") >= 4096 && it.getBoolean("active") }
        restart()
        val recovered = awaitTask(task)
        assertEquals("interrupted", recovered.getString("state")); assertTrue(recovered.getBoolean("stagingCleaned"))
        assertEquals(before, session(id).getLong("revision")); assertEquals(0, children(id, node(id, "a".toByteArray())).getJSONArray("entries").length())
    }

    @Test fun committedDatabaseWinsWhenJournalPublicationFailsAndRecoveryRepairsIt() {
        val id = full(); val old = JSONObject(remote.scanSnapshot).getLong("logicalBytes")
        val blocker = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "snapshot-rescan-service-test-v1.json.new")
        check(blocker.mkdir())
        try {
            File(fixture, "a/new").writeText("new")
            val task = start(id); assertEquals("completed", awaitTask(task).getString("state"))
            SystemClock.sleep(300)
            assertEquals(old, JSONObject(remote.scanSnapshot).getLong("logicalBytes"))
            assertEquals("already-completed", JSONObject(remote.cancelSnapshotRescan(task)).getString("status"))
            check(blocker.delete()); restart()
            assertEquals(old + 3, JSONObject(remote.scanSnapshot).getLong("logicalBytes"))
            assertEquals("completed", awaitTask(task).getString("state"))
        } finally { blocker.delete() }
    }

    @Test fun rootUsesRawBytesAndRequiresAnExplicitConnectionAfterRestart() {
        root(); val shell = Shell.getShell(); check(shell.isRoot)
        fun rawCommand(command: String) = """
            raw=${'$'}(printf '\377\012_')
            raw=${'$'}{raw%_}
            $command
        """.trimIndent()
        check(shell.newJob().add(rawCommand("mkdir '${fixture.path}/a/'\"\$raw\"; printf raw >'${fixture.path}/a/'\"\$raw\"/child")).exec().isSuccess)
        val id = full(true); val target = node(id, "a".toByteArray()); val raw = node(id, byteArrayOf(0xff.toByte(), 10), target)
        restart()
        assertEquals("root-unavailable", prepare(id, raw).getString("status")); readyForNext()
        root()
        check(shell.newJob().add(rawCommand("printf changed >'${fixture.path}/a/'\"\$raw\"/child")).exec().isSuccess)
        val result = awaitTask(start(id, raw)); assertEquals("completed", result.getString("state")); readyForNext()
        assertEquals("ff0a", query("detail", "sessionId" to id, "nodeId" to raw).getJSONObject("detail").getString("nameHex"))
        assertEquals(7L, query("detail", "sessionId" to id, "nodeId" to raw).getJSONObject("detail").getLong("logicalBytes"))
        assertEquals(0, JSONObject(remote.rootStatus).getInt("uid"))
    }

    @Test fun rootCancellationAndProducerDeathDoNotCommitAndCanReconnect() {
        root(); val id = full(true); val rev = session(id).getLong("revision")
        repeat(30_000) { check(File(fixture, "a/f$it").createNewFile()) }
        var task = start(id); awaitTask(task) { it.getLong("nodeCount") >= 4096 && it.getBoolean("active") }
        remote.cancelSnapshotRescan(task); assertEquals("cancelled", awaitTask(task).getString("state")); readyForNext()
        val pid = JSONObject(remote.rootStatus).getInt("pid"); assertTrue(pid > 1)
        task = start(id); awaitTask(task) { it.getLong("nodeCount") >= 4096 && it.getBoolean("active") }
        val command = """
            name=${'$'}(tr '\000' '\n' </proc/$pid/cmdline | head -n 1)
            case "${'$'}name" in
              '${context.packageName}:root:'*)
                grep -Eq '^Uid:[[:space:]]+0[[:space:]]+0[[:space:]]+0[[:space:]]+0${'$'}' /proc/$pid/status && kill -KILL $pid ;;
              *) false ;;
            esac
        """.trimIndent()
        check(Shell.getShell().newJob().add(command).exec().isSuccess)
        assertEquals("interrupted", awaitTask(task).getString("state")); readyForNext()
        assertEquals(rev, session(id).getLong("revision")); root()
        assertEquals("completed", awaitTask(start(id, node(id, "outside".toByteArray()))).getString("state"))
    }
}
