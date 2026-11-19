package com.example.disksize.scan

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.CancellationSignal
import android.util.Log
import com.example.disksize.scan.ipc.IScanCoordinator
import com.example.disksize.scan.ipc.NativeDiagnosticsParcel
import com.example.disksize.MainActivity
import com.example.disksize.BuildConfig
import com.example.disksize.root.RootBackend
import com.example.disksize.root.RootFailure
import com.example.disksize.index.IndexStore
import com.example.disksize.index.LocalScanPipe
import com.example.disksize.index.IndexQueries
import com.example.disksize.index.SnapshotRescanSource
import com.example.disksize.index.SnapshotRescanStore
import com.example.disksize.scan.SnapshotRescanContract as Rescan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CoordinatorService : Service() {
    private class Active(val id: String, var root: String, var monitorShared: Boolean, var rootMode: Boolean,
        val prepareStart: Long, val rescanRequest: Rescan.Request? = null) {
        val preparedAt = System.nanoTime()
        var foregroundAt = 0L
        var handle = 0L
        @Volatile var started = false
        @Volatile var finalizing = false
        @Volatile var cancelled = false
        @Volatile var forcedState: String? = null
        var reason = ""
        val indexCancellation = CancellationSignal()
        @Volatile var applying = false
        @Volatile var rescanNodes = 0L
        @Volatile var rescanIssues = 0L
        @Volatile var publicationPending = false
    }
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val initialized = CompletableFuture<Unit>()
    private val pendingWrite = AtomicReference<String?>(null)
    private val writing = AtomicBoolean(false)
    private lateinit var journal: SessionJournal
    private lateinit var rootBackend: RootBackend
    private lateinit var index: IndexStore
    private var pipeline = ScanPipeline.OPTIMIZED
    private var current: JSONObject? = null
    @Volatile private var encoded = ""
    @Volatile private var destroyed = false
    @Volatile private var journalHealthy = true
    private var active: Active? = null
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.RESCAN_TEST_HOST) {
            val storage = createDeviceProtectedStorageContext()
            journal = SessionJournal(storage, "snapshot-rescan-service-test-v1.json")
            index = IndexStore(storage, "snapshot-rescan-service-test-v1.db")
        } else if (BuildConfig.SCAN_BENCHMARK) {
            val storage = BenchmarkStorage.context(this)
            journal = SessionJournal(storage, BenchmarkStorage.JOURNAL)
            index = IndexStore(storage, BenchmarkStorage.INDEX, BenchmarkStorage.importPolicy(storage),
                BenchmarkStorage.finalizePolicy(storage))
            pipeline = BenchmarkStorage.pipeline(storage)
        } else {
            journal = SessionJournal(this)
            index = IndexStore(this)
        }
        rootBackend = RootBackend(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "目录扫描", NotificationManager.IMPORTANCE_LOW),
        )
        // Queue recovery after a previous service instance's cancelled work.
        worker.execute { journalExecutor.execute {
            try {
                index.recover()
                var restored = journal.read()
                if (restored != null && (restored.optBoolean("active") || restored.optString("state") in ACTIVE_STATES)) {
                    index.interrupt(restored.getString("sessionId"), "interrupted", "process-interrupted")
                    restored.put("state", "interrupted").put("reason", "process-interrupted")
                        .put("active", false).put("complete", false)
                        .put("endedAtMillis", now()).put("updatedAtMillis", now())
                    journal.write(restored.toString())
                }
                val aligned = SnapshotRescanStatus.alignJournal(index, restored)
                if (aligned?.toString() != restored?.toString()) { journal.write(aligned.toString()); restored = aligned }
                synchronized(lock) { current = restored; encoded = restored?.toString() ?: "" }
            } catch (error: Exception) {
                Log.e(TAG, "Session recovery failed", error)
                synchronized(lock) {
                    current = newSnapshot(UUID.randomUUID().toString(), "").put("state", "failed")
                        .put("active", false).put("reason", "journal-unreadable").put("endedAtMillis", now())
                    encoded = current.toString()
                }
            } finally { initialized.complete(Unit) }
        } }
    }

    private val binder = object : IScanCoordinator.Stub() {
        override fun requestRootAccess() { checkCallingUid(); rootBackend.request() }
        override fun getRootStatus(): String { checkCallingUid(); return rootBackend.status }
        override fun prepareScan(rootPath: String, source: String): String {
            checkCallingUid()
            val prepareStart = System.nanoTime()
            initialized.get(5, TimeUnit.SECONDS)
            require(source in setOf("own", "external-own", "shared", "path", "root")) { "Unknown scan source" }
            require(rootPath.startsWith('/') && !rootPath.contains('\u0000') && rootPath.toByteArray().size <= 4096) {
                "Invalid root path"
            }
            // Resolve root aliases only in the scanner's mount namespace.
            val root = if (source == "root") rootPath else File(rootPath).canonicalPath
            check(source != "root" || rootBackend.ready()) { "Explicit root authorization required" }
            val rootView = if (source == "root") JSONObject(rootBackend.status) else null
            check(rootView == null || rootView.optString("state") == "ready") { "Root connection was lost" }
            val access = ScanAccess.read(this@CoordinatorService)
            check(source != "shared" || access.sharedAccessGranted) { "Shared storage permission required" }
            val task = synchronized(lock) {
                check(!destroyed && journalHealthy) { "Session storage unavailable" }
                check(active == null) { "A scan is already active" }
                Active(UUID.randomUUID().toString(), root,
                    source != "root" && access.sharedAccessGranted && ScanAccess.requiresSharedAccess(this@CoordinatorService, root),
                    source == "root", prepareStart).also {
                    active = it
                    current = newSnapshot(it.id, root).put("source", source)
                        .put("backend", if (it.rootMode) "root" else "ordinary")
                    if (rootView != null) {
                        current!!.put("rootView", rootView).put("scannerUid", rootView.getLong("uid"))
                            .put("scannerPid", rootView.getLong("pid"))
                    }
                    publishLocked()
                }
            }
            main.postDelayed({ rejectPrepared(task.id, "foreground-start-not-received") }, 10_000)
            return task.id
        }

        override fun getScanSnapshot(): String {
            checkCallingUid()
            initialized.get(5, TimeUnit.SECONDS)
            return encoded
        }

        override fun cancelScan(sessionId: String) { checkCallingUid(); cancel(sessionId) }
        override fun prepareSnapshotRescan(snapshotId: String, nodeId: Long, expectedRevision: Long): String {
            checkCallingUid(); initialized.get(5, TimeUnit.SECONDS)
            val request = Rescan.Request(snapshotId, nodeId, expectedRevision)
            if (Rescan.admissionError(request, null, false) == Rescan.Error.INVALID_REQUEST)
                return rescanResponse(Rescan.Error.INVALID_REQUEST)
            val task = synchronized(lock) {
                if (destroyed || !journalHealthy) return rescanResponse(Rescan.Error.STORAGE_ERROR)
                if (active != null) return rescanResponse(Rescan.Error.BUSY)
                Active(UUID.randomUUID().toString(), "", false, false, System.nanoTime(), request).also { active = it }
            }
            main.postDelayed({ rejectPrepared(task.id, "foreground-start-not-received") }, 10_000)
            return try {
                worker.submit<String> {
                    try {
                        index.rescans.create(task.id, request)
                        val scope = index.rescans.source(task.id)
                        task.root = scope.root; task.rootMode = scope.backend == "root"
                        if (task.rootMode && !rootBackend.ready()) throw SnapshotRescanStore.Rejected(Rescan.Error.ROOT_UNAVAILABLE)
                        task.monitorShared = !task.rootMode && ScanAccess.requiresSharedAccess(this@CoordinatorService, scope.root)
                        if (task.monitorShared && !ScanAccess.read(this@CoordinatorService).sharedAccessGranted)
                            throw SnapshotRescanStore.Rejected(Rescan.Error.SCAN_FAILED)
                        if (task.finalizing) throw SnapshotRescanStore.Rejected(Rescan.Error.SCAN_FAILED)
                        if (task.cancelled || destroyed) throw SnapshotRescanStore.Rejected(cancellationReason(task))
                        SnapshotRescanStatus.encode(requireNotNull(index.rescans.get(task.id))).toString()
                    } catch (error: Exception) {
                        val reason = (error as? SnapshotRescanStore.Rejected)?.reason ?: Rescan.Error.STORAGE_ERROR
                        failRescan(task, reason); finishRescan(task)
                        JSONObject(rescanResponse(reason)).also {
                            if (index.rescans.get(task.id) != null) it.put("taskId", task.id)
                        }.toString()
                    }
                }.get(5, TimeUnit.SECONDS)
            } catch (error: Exception) {
                rejectPrepared(task.id, "admission-failed-or-timeout")
                JSONObject(rescanResponse(Rescan.Error.STORAGE_ERROR)).put("taskId", task.id).toString()
            }
        }

        override fun getSnapshotRescan(taskId: String): String {
            checkCallingUid(); initialized.get(5, TimeUnit.SECONDS)
            if (!validTaskId(taskId)) return rescanResponse(Rescan.Error.INVALID_REQUEST)
            val job = index.rescans.get(taskId) ?: return JSONObject().put("status", "not-found").toString()
            val result = SnapshotRescanStatus.encode(job)
            synchronized(lock) { active?.takeIf { it.id == taskId && it.rescanRequest != null }?.let {
                result.put("nodeCount", maxOf(job.nodes, it.rescanNodes)).put("issueCount", maxOf(job.issues, it.rescanIssues))
                    .put("cancellationRequested", job.cancellationRequested || it.cancelled)
                    .put("publicationPending", it.publicationPending)
            } }
            return result.toString()
        }

        override fun cancelSnapshotRescan(taskId: String): String {
            checkCallingUid(); initialized.get(5, TimeUnit.SECONDS)
            if (!validTaskId(taskId)) return rescanResponse(Rescan.Error.INVALID_REQUEST)
            val job = index.rescans.get(taskId) ?: return JSONObject().put("status", "not-found").toString()
            val result = Rescan.cancellation(job.state, job.committedRevision)
            if (result == Rescan.Cancellation.REQUESTED) {
                val owned = synchronized(lock) { active?.let { it.id == taskId && it.rescanRequest != null } == true }
                if (owned) cancel(taskId) else worker.execute {
                    try {
                        val pending = index.rescans.get(taskId)
                        if (pending != null && !pending.state.terminal && pending.committedRevision == null) {
                            index.rescans.requestCancellation(taskId)
                            index.rescans.transition(taskId, Rescan.State.CANCELLED, Rescan.Error.CANCELLED)
                        }
                    } catch (error: Exception) {
                        journalHealthy = false; Log.e(TAG, "Orphan rescan cancellation deferred to recovery", error)
                    }
                }
            }
            return JSONObject().put("status", result.wireName).put("taskId", taskId).toString()
        }
        override fun queryIndex(requestJson: String): String {
            checkCallingUid(); initialized.get(5, TimeUnit.SECONDS)
            require(requestJson.length <= 8192)
            val query = try { JSONObject(requestJson) } catch (_: org.json.JSONException) {
                return JSONObject().put("status", "invalid-query").toString()
            }
            synchronized(lock) {
                val running = active
                if (running?.rescanRequest != null) {
                    val kind = query.optString("kind")
                    if (kind in setOf("snapshot-delete", "snapshot-retain", "snapshot-compare") ||
                        (running.applying && kind == "snapshot-list"))
                        return rescanResponse(Rescan.Error.BUSY)
                }
                val id = active?.id
                if (id != null && query.optString("kind").startsWith("snapshot-") &&
                    listOf("snapshotId", "baseId", "targetId").any { query.optString(it) == id }) {
                    return JSONObject().put("status", "index-finalizing").toString()
                }
            }
            val result = JSONObject(IndexQueries(index).query(requestJson, nonBlockingSnapshots = true))
            if (query.optString("kind") == "snapshot-delete" && result.optString("status") == "ok") {
                journalExecutor.submit {
                    synchronized(lock) {
                        if (active == null && current?.optString("sessionId") == query.optString("snapshotId")) {
                            pendingWrite.set(null); journal.clear(); current = null; encoded = ""
                        }
                    }
                }.get(5, TimeUnit.SECONDS)
            }
            // The journal must be durable before a public query can expose a
            // terminal index. SQLite may finish just ahead of that write.
            synchronized(lock) {
                val snapshots = result.optJSONArray("snapshots")
                if (snapshots != null) {
                    val visible = JSONArray()
                    for (i in 0 until snapshots.length()) {
                        val item = snapshots.getJSONObject(i)
                        if (item.optString("id") != active?.id) visible.put(item)
                    }
                    result.put("snapshots", visible)
                }
                val recent = result.optJSONArray("sessions")
                for (i in 0 until (recent?.length() ?: 0)) {
                    val session = recent!!.getJSONObject(i)
                    if (active?.id == session.optString("sessionId") && session.optString("state") in setOf("completed", "partial", "failed")) {
                        session.put("state", "running").put("phase", "index-finalizing").put("provisional", true)
                    }
                }
                if (active?.id == query.optString("sessionId") && result.optString("state") in setOf("completed", "partial", "failed")) {
                    return JSONObject().put("status", "index-finalizing").put("provisional", true).toString()
                }
            }
            return result.toString()
        }
        override fun rejectPreparedScan(sessionId: String, reason: String) {
            checkCallingUid(); rejectPrepared(sessionId, reason)
        }

        override fun getNativeDiagnostics(): NativeDiagnosticsParcel {
            checkCallingUid()
            try {
                val diagnostics = NativeBridge.readDiagnostics(
                    Application.getProcessName(),
                    filesDir.absolutePath,
                )
                check(diagnostics.processId == Process.myPid().toLong())
                check(diagnostics.processUid == Process.myUid().toLong())
                return diagnostics
            } catch (error: LinkageError) {
                Log.e("DiskSize.Service", "Native engine loading failed", error)
                return nativeFailure("无法加载原生引擎")
            } catch (error: Exception) {
                Log.e("DiskSize.Service", "Native diagnostics failed", error)
                return nativeFailure("原生引擎自检失败")
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancel(intent.getStringExtra(EXTRA_SESSION) ?: "")
            releaseIfIdle()
            return START_NOT_STICKY
        }
        // Promotion happens before any native work and within the FGS deadline.
        try {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (error: Exception) {
            Log.e(TAG, "Foreground promotion failed", error)
            rejectPrepared(intent?.getStringExtra(EXTRA_SESSION) ?: "", "foreground-start-denied")
            releaseIfIdle()
            return START_NOT_STICKY
        }
        val task = synchronized(lock) {
            active?.takeIf { it.id == intent?.getStringExtra(EXTRA_SESSION) && !it.started && !it.finalizing }?.also {
                it.started = true
                it.foregroundAt = System.nanoTime()
                if (it.rescanRequest == null) { current!!.put("state", "running"); publishLocked() }
            }
        }
        if (task != null) {
            worker.execute { if (task.rescanRequest == null) runScan(task) else runRescan(task) }
            main.post(permissionWatch)
        } else releaseIfIdle()
        return START_NOT_STICKY
    }

    private fun runScan(task: Active) {
        val workerStart = System.nanoTime()
        var performance: JSONObject? = null
        try {
            // Flush the active marker before JNI. Abrupt process death is then
            // distinguishable from a completed scan on the next binding.
            journalExecutor.submit {}.get(5, TimeUnit.SECONDS)
            check(journalHealthy) { "Session journal is unavailable" }
            val progress: (Long, Long, Long) -> Unit = { nodes, logical, issues ->
                synchronized(lock) {
                    if (!destroyed && active === task) {
                        current!!.put("nodeCount", nodes).put("logicalBytes", logical).put("issueCount", issues)
                        publishLocked()
                    }
                }
                main.post { updateNotification() }
            }
            val view = synchronized(lock) { JSONObject(current.toString()) }
            val indexStart = System.nanoTime()
            var pipelineStart = indexStart
            val summary = index.begin(task.id, task.root, if (task.rootMode) "root" else "ordinary",
                view.getInt("scannerUid"), view.getInt("scannerPid")).use { importer ->
                pipelineStart = System.nanoTime()
                if (task.rootMode) {
                    // A queued cancellation must not dispatch new privileged work.
                    if (synchronized(lock) { task.cancelled || destroyed }) throw RootFailure("cancelled-before-root-start")
                    rootBackend.run(task.id, task.root, importer, pipeline) { nodes, logical, issues ->
                        progress(nodes, logical, issues)
                        if (synchronized(lock) { task.cancelled || destroyed }) rootBackend.cancel(task.id)
                    }
                } else {
                    val handle = NativeBridge.nativeCreateScan(task.root.toByteArray(Charsets.UTF_8))
                    synchronized(lock) {
                        task.handle = handle
                        if (task.cancelled || destroyed) NativeBridge.nativeCancelScan(handle)
                    }
                    LocalScanPipe.run(handle, task.id, importer, pipeline, progress)
                }
            }
            val pipelineEnd = System.nanoTime()
            performance = summary.performance?.put("coordinator", JSONObject()
                .put("prepareMicros", (task.preparedAt - task.prepareStart) / 1000)
                .put("foregroundMicros", (task.foregroundAt - task.preparedAt) / 1000)
                .put("workerQueueMicros", (workerStart - task.foregroundAt) / 1000)
                .put("beforeIndexMicros", (indexStart - workerStart) / 1000)
                .put("indexSetupMicros", (pipelineStart - indexStart) / 1000)
                .put("pipelineMicros", (pipelineEnd - pipelineStart) / 1000))
            if (BuildConfig.SCAN_BENCHMARK) performance?.put("pipelineVariant", pipeline.wireName)
            synchronized(lock) {
                if (!destroyed && active === task) {
                    current!!.put("peakOpenFds", summary.peakFds).put("openFdsAtFinish", summary.finishFds)
                        .put("rootMountId", summary.mount)
                }
            }
            val result = index.finalize(task.id, summary, task.indexCancellation) { synchronized(lock) { task.cancelled || destroyed } }
            synchronized(lock) {
                if (!destroyed && active === task) {
                    result.keys().forEach { key -> current!!.put(key, result.get(key)) }
                    if (task.monitorShared && !ScanAccess.read(this).sharedAccessGranted) {
                        task.forcedState = "interrupted"; task.reason = "permission-revoked"
                    }
                    if (task.forcedState != null) {
                        current!!.put("state", task.forcedState).put("reason", task.reason).put("complete", false)
                    } else if (task.cancelled) {
                        current!!.put("state", "cancelled").put("complete", false)
                    }
                    if (task.forcedState != null || task.cancelled) {
                        index.interrupt(task.id, current!!.getString("state"), task.reason)
                        current!!.put("provisional", true)
                    }
                    finishLocked(task)
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Scan failed", error)
            val failedState = synchronized(lock) { task.forcedState ?: if (task.cancelled) "cancelled" else if (task.rootMode) "interrupted" else "failed" }
            try { index.interrupt(task.id, failedState, task.reason.ifEmpty { "scan-or-index-failure" }) }
            catch (storageError: Exception) { Log.e(TAG, "Index failure persistence failed", storageError) }
            val persisted = try { index.preview(task.id) } catch (_: Exception) { null }
            synchronized(lock) {
                if (!destroyed && active === task) {
                    persisted?.keys()?.forEach { key -> current!!.put(key, persisted.get(key)) }
                    if (!current!!.has("performance") && performance != null) current!!.put("performance", performance)
                    val rootReason = (error as? RootFailure)?.reason
                    current!!.put("state", task.forcedState ?: if (task.cancelled) "cancelled" else if (rootReason != null) "interrupted" else "failed")
                        .put("reason", task.reason.ifEmpty { if (task.cancelled) "" else rootReason ?: "native-or-journal-failure" })
                        .put("complete", false).put("provisional", true)
                    finishLocked(task)
                }
            }
        } finally {
            synchronized(lock) {
                if (task.handle != 0L) {
                    NativeBridge.nativeReleaseScan(task.handle)
                    task.handle = 0
                }
            }
            journalExecutor.execute { main.post { releaseIfIdle() } }
        }
    }

    private fun rescanResponse(error: Rescan.Error) = JSONObject().put("status", error.wireName).toString()
    private fun validTaskId(id: String) = id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
    private fun cancellationReason(task: Active) =
        if (destroyed || task.forcedState != null) Rescan.Error.PROCESS_INTERRUPTED else Rescan.Error.CANCELLED

    private fun runRescan(task: Active) {
        try {
            journalExecutor.submit {}.get(5, TimeUnit.SECONDS)
            check(journalHealthy)
            if (task.cancelled || destroyed) throw SnapshotRescanStore.Rejected(cancellationReason(task))
            // Read the source again at dispatch; no path supplied by Flutter or an Intent is used.
            val scope = index.rescans.source(task.id)
            if (task.rootMode && !rootBackend.ready()) throw SnapshotRescanStore.Rejected(Rescan.Error.ROOT_UNAVAILABLE)
            val progress: (Long, Long, Long) -> Unit = { nodes, _, issues ->
                task.rescanNodes = nodes; task.rescanIssues = issues
                main.post { updateNotification() }
            }
            index.rescans.beginStaging(task.id).use { staging ->
                val summary = if (task.rootMode) rootBackend.runSubtree(task.id, scope.root.toByteArray(Charsets.UTF_8),
                    scope.path, scope.identities, scope.filesystem, staging, { task.cancelled || destroyed }, progress)
                else {
                    val handle = NativeBridge.nativeCreateSubtreeScan(scope.root.toByteArray(Charsets.UTF_8), scope.path, scope.identities, scope.filesystem)
                    synchronized(lock) {
                        task.handle = handle
                        if (task.cancelled || destroyed) NativeBridge.nativeCancelScan(handle)
                    }
                    LocalScanPipe.run(handle, task.id, staging, ScanPipeline.OPTIMIZED, progress)
                }
                if (task.cancelled || destroyed) throw SnapshotRescanStore.Rejected(cancellationReason(task))
                staging.finish(summary)
            }
            index.rescans.scopeError(task.id)?.let { throw SnapshotRescanStore.Rejected(it) }
            task.applying = true
            index.rescans.apply(task.id, task.indexCancellation, cancelled = { task.cancelled || destroyed },
                checkpoint = { if (BuildConfig.RESCAN_TEST_HOST) rescanTestCheckpoint(task.id, it.name) },
                cancellationError = { cancellationReason(task) })
        } catch (error: Throwable) {
            Log.w(TAG, "Snapshot rescan stopped", error)
            val reason = when {
                task.cancelled || destroyed -> cancellationReason(task)
                error is SnapshotRescanStore.Rejected -> error.reason
                error is RootFailure && error.reason == "root-process-died" -> Rescan.Error.PROCESS_INTERRUPTED
                error is RootFailure && error.reason == "root-unavailable" -> Rescan.Error.ROOT_UNAVAILABLE
                error is RootFailure || error is IllegalArgumentException -> Rescan.Error.PROTOCOL_ERROR
                error is android.database.sqlite.SQLiteFullException || error is android.database.sqlite.SQLiteDiskIOException -> Rescan.Error.STORAGE_ERROR
                else -> Rescan.Error.SCAN_FAILED
            }
            failRescan(task, reason)
        } finally {
            synchronized(lock) {
                if (task.handle != 0L) { NativeBridge.nativeReleaseScan(task.handle); task.handle = 0L }
            }
            finishRescan(task)
        }
    }

    /** Debug isolated-host only: let instrumentation kill a real process on either side of COMMIT. */
    private fun rescanTestCheckpoint(id: String, point: String) {
        check(BuildConfig.RESCAN_TEST_HOST)
        val directory = createDeviceProtectedStorageContext().noBackupFilesDir
        val control = java.io.File(directory, "snapshot-rescan-test-checkpoint.json")
        if (!control.isFile) return
        val request = JSONObject(control.readText())
        if (request.optString("taskId") != id) return
        val marker = java.io.File(directory, "snapshot-rescan-test-checkpoint.reached")
        if (request.optString("point") == "MEASURE") {
            marker.appendText("$point:${System.nanoTime()}\n")
            return
        }
        if (request.optString("point") != point) return
        marker.writeText("$id:$point")
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (control.isFile && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        check(!control.isFile) { "Test checkpoint timed out" }
    }

    private fun failRescan(task: Active, reason: Rescan.Error) {
        try {
            val job = index.rescans.get(task.id) ?: return
            if (job.committedRevision != null || job.state.terminal) return
            val state = when (reason) {
                Rescan.Error.CANCELLED -> Rescan.State.CANCELLED
                Rescan.Error.PROCESS_INTERRUPTED -> Rescan.State.INTERRUPTED
                Rescan.Error.STALE_REVISION -> Rescan.State.STALE
                else -> Rescan.State.FAILED
            }
            index.rescans.transition(task.id, state, reason)
        } catch (error: Exception) {
            journalHealthy = false
            Log.e(TAG, "Rescan failure persistence deferred to recovery", error)
        }
    }

    private fun finishRescan(task: Active) {
        synchronized(lock) { task.finalizing = true }
        try {
            val job = index.rescans.get(task.id)
            if (job?.committedRevision != null) {
                val before = synchronized(lock) { current?.let { JSONObject(it.toString()) } }
                val aligned = SnapshotRescanStatus.alignJournal(index, before)
                if (aligned?.toString() != before?.toString()) {
                    pendingWrite.set(null)
                    journalExecutor.submit {
                        journal.write(aligned.toString())
                        synchronized(lock) {
                            if (active === task && current?.optString("sessionId") == job.sessionId) {
                                current = aligned; encoded = aligned.toString()
                            }
                        }
                    }.get(5, TimeUnit.SECONDS)
                }
            }
        } catch (error: Exception) {
            // The durable SQLite marker wins even if the independent journal cannot be published.
            journalHealthy = false; task.publicationPending = true
            Log.e(TAG, "Committed rescan journal publication deferred to recovery", error)
        } finally {
            synchronized(lock) { if (active === task) active = null }
            main.post { releaseIfIdle() }
        }
    }

    private fun finishLocked(task: Active) {
        check(active === task)
        task.finalizing = true
        current!!.put("active", false).put("endedAtMillis", now()).put("updatedAtMillis", now())
        val terminal = current.toString()
        // Keep the last published view active until the terminal record is
        // durable. A caller that observes Completed may then kill/rebind safely.
        val queuedAt = System.nanoTime()
        journalExecutor.execute {
            val writeStart = System.nanoTime()
            var writtenAt: Long? = null
            try { journal.write(terminal); writtenAt = System.nanoTime() }
            catch (error: Exception) {
                journalHealthy = false
                Log.e(TAG, "Terminal persistence failed", error)
                synchronized(lock) {
                    current!!.put("state", "failed").put("reason", "journal-write-failed").put("complete", false)
                }
            }
            synchronized(lock) {
                if (active === task) {
                    // Keep this post-write sample live; a second write would distort the measurement.
                    if (writtenAt != null) current!!.optJSONObject("performance")?.put("publication", JSONObject()
                        .put("journalQueueMicros", (writeStart - queuedAt) / 1000)
                        .put("journalWriteMicros", (writtenAt - writeStart) / 1000)
                        .put("prepareToPublishMicros", (System.nanoTime() - task.prepareStart) / 1000))
                    active = null
                    encoded = current.toString()
                }
            }
            main.post { releaseIfIdle() }
        }
    }

    private fun rejectPrepared(id: String, reason: String) {
        synchronized(lock) {
            val task = active?.takeIf { it.id == id && !it.started && !it.finalizing } ?: return
            if (task.rescanRequest != null) {
                task.finalizing = true
                worker.execute { failRescan(task, Rescan.Error.SCAN_FAILED); finishRescan(task) }
                return
            }
            current!!.put("state", "failed").put("reason", reason).put("complete", false)
            finishLocked(task)
        }
        main.post { releaseIfIdle() }
    }

    private fun cancel(id: String) {
        val rescan = synchronized(lock) { active?.takeIf { it.id == id && it.rescanRequest != null && !it.finalizing }?.also { it.cancelled = true } }
        if (rescan != null) {
            // Signal SQL before queuing any persistence write behind its transaction.
            rescan.indexCancellation.cancel()
            synchronized(lock) { if (rescan.handle != 0L) NativeBridge.nativeCancelScan(rescan.handle) }
            if (rescan.rootMode) rootBackend.cancel(id)
            worker.execute {
                try {
                    val job = index.rescans.get(id)
                    if (job != null && !job.state.terminal) index.rescans.requestCancellation(id)
                    if (!rescan.started) { failRescan(rescan, cancellationReason(rescan)); finishRescan(rescan) }
                } catch (error: Exception) {
                    journalHealthy = false; Log.e(TAG, "Rescan cancellation persistence deferred to recovery", error)
                }
            }
            main.post { updateNotification(); releaseIfIdle() }
            return
        }
        synchronized(lock) {
            val task = active?.takeIf { it.id == id && !it.finalizing } ?: return
            task.cancelled = true
            task.indexCancellation.cancel()
            current!!.put("state", "cancelling")
            if (task.handle != 0L) NativeBridge.nativeCancelScan(task.handle)
            if (task.rootMode) rootBackend.cancel(task.id)
            if (!task.started) {
                current!!.put("state", task.forcedState ?: "cancelled")
                finishLocked(task)
            } else publishLocked()
        }
        main.post { updateNotification(); releaseIfIdle() }
    }

    private fun interrupt(reason: String, state: String = "interrupted") {
        val id = synchronized(lock) {
            val task = active?.takeUnless { it.finalizing } ?: return
            task.forcedState = state; task.reason = reason
            if (task.rescanRequest == null) current!!.put("reason", reason)
            task.id
        }
        cancel(id)
    }

    private val permissionWatch = object : Runnable {
        override fun run() {
            val check = synchronized(lock) { active?.monitorShared == true }
            if (check && !ScanAccess.read(this@CoordinatorService).sharedAccessGranted) interrupt("permission-revoked")
            if (synchronized(lock) { active != null } && !destroyed) main.postDelayed(this, 500)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        interrupt("foreground-timeout")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        synchronized(lock) {
            destroyed = true
            active?.takeUnless { it.finalizing }?.let { task ->
                task.cancelled = true
                task.indexCancellation.cancel()
                if (task.handle != 0L) NativeBridge.nativeCancelScan(task.handle)
                if (task.rootMode) rootBackend.cancel(task.id)
                if (task.rescanRequest == null) {
                    current!!.put("state", task.forcedState ?: "interrupted")
                        .put("reason", task.reason.ifEmpty { "service-destroyed" })
                        .put("complete", false).put("active", false).put("endedAtMillis", now())
                    publishLocked()
                } else {
                    task.forcedState = "interrupted"
                    if (!task.started) worker.execute { failRescan(task, Rescan.Error.PROCESS_INTERRUPTED); finishRescan(task) }
                }
            }
        }
        main.removeCallbacksAndMessages(null)
        rootBackend.close()
        worker.execute { index.close() }
        super.onDestroy()
    }

    private fun publishLocked() {
        current!!.put("updatedAtMillis", now())
        encoded = current.toString()
        pendingWrite.set(encoded)
        scheduleWrite()
    }

    private fun scheduleWrite() {
        if (!journalHealthy || !writing.compareAndSet(false, true)) return
        journalExecutor.execute {
            try {
                while (true) { journal.write(pendingWrite.getAndSet(null) ?: break) }
            } catch (error: Exception) {
                journalHealthy = false
                Log.e(TAG, "Session persistence failed", error)
                main.post { interrupt("journal-write-failed", "failed") }
            } finally {
                writing.set(false)
                if (pendingWrite.get() != null && journalHealthy) scheduleWrite()
            }
        }
    }

    private fun newSnapshot(id: String, root: String) = JSONObject()
        .put("schemaVersion", 1).put("sessionId", id).put("rootPath", root)
        .put("state", "queued").put("reason", "").put("startedAtMillis", now())
        .put("updatedAtMillis", now()).put("endedAtMillis", 0)
        .put("nodeCount", 0).put("logicalBytes", 0).put("allocatedBytes", 0)
        .put("provisional", true).put("complete", false).put("issueCount", 0)
        .put("previewTruncated", false).put("serviceUid", Process.myUid()).put("servicePid", Process.myPid())
        .put("backend", "ordinary").put("scannerUid", Process.myUid()).put("scannerPid", Process.myPid())
        .put("active", true).put("peakOpenFds", 0).put("openFdsAtFinish", -1)
        .put("entries", JSONArray()).put("issues", JSONArray())

    private fun notification(): Notification {
        val snapshot = synchronized(lock) {
            val task = active
            if (task?.rescanRequest != null) JSONObject().put("sessionId", task.id).put("nodeCount", task.rescanNodes)
                .put("state", if (task.cancelled) "cancelling" else if (task.applying) "applying" else "scanning")
            else current?.let { JSONObject(it.toString()) }
        }
        val id = snapshot?.optString("sessionId") ?: ""
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1,
            Intent(this, CoordinatorService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_SESSION, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (snapshot?.optString("state") == "cancelling") "正在停止扫描" else "正在扫描目录")
            .setContentText("已发现 ${snapshot?.optLong("nodeCount") ?: 0} 项")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "取消", cancel).build()).build()
    }

    private fun updateNotification() {
        if (SystemClock.elapsedRealtime() - lastNotificationAt < 1000) return
        if (!destroyed && synchronized(lock) { active?.started == true }) {
            lastNotificationAt = SystemClock.elapsedRealtime()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        }
    }

    private fun releaseIfIdle() {
        if (synchronized(lock) { active == null }) {
            main.removeCallbacks(permissionWatch)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun checkCallingUid() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw SecurityException("Caller does not belong to this application")
        }
    }

    private fun nativeFailure(message: String): NativeDiagnosticsParcel {
        return NativeDiagnosticsParcel().apply {
            errorCode = "native-failure"
            errorMessage = message
        }
    }

    companion object {
        const val ACTION_START = "com.example.disksize.START_SCAN"
        const val ACTION_CANCEL = "com.example.disksize.CANCEL_SCAN"
        const val EXTRA_SESSION = "sessionId"
        private const val TAG = "DiskSize.Scan"
        private const val CHANNEL = "directory-scan"
        private const val NOTIFICATION = 1001
        private val ACTIVE_STATES = setOf("queued", "running", "cancelling")
        // Shared across service instances in this process; old shutdown writes
        // cannot race a new instance's recovery of the same AtomicFile.
        private val journalExecutor = Executors.newSingleThreadExecutor()
        private val worker = Executors.newSingleThreadExecutor()
    }
}
