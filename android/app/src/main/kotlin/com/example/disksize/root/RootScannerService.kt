package com.example.disksize.root

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.UserManager
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.example.disksize.BuildConfig
import com.example.disksize.index.IndexProtocol
import com.example.disksize.scan.NativeBridge
import com.example.disksize.scan.ScanPipeline
import com.example.disksize.scan.ipc.IRootScanner
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// This process never opens the app's journal, database, export or cache paths.
// A normal (non-daemon) libsu service dies with its ordinary coordinator client.
class RootScannerService : RootService() {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor()
    private var active: Job? = null
    private var destroyed = false
    private val clientUid by lazy { applicationInfo.uid }

    private inner class Job(val id: String, val fd: ParcelFileDescriptor, val client: IBinder) {
        var handle = 0L
        var cancelled = false
        val done = CountDownLatch(1)
        val death = IBinder.DeathRecipient { synchronized(lock) { stop(this) } }
    }

    private fun authorize() {
        check(Process.myUid() == 0 && Os.geteuid() == 0) { "Not a root process" }
        if (Binder.getCallingUid() != clientUid) throw SecurityException("Unexpected root client UID")
    }

    private val binder = object : IRootScanner.Stub() {
        override fun inspect(): String {
            authorize()
            val native = NativeBridge.readDiagnostics("root", "")
            check(native.processUid == 0L && native.effectiveUid == 0L && native.selfTestPassed)
            val user = clientUid / 100000
            val mounts = File("/proc/self/mountinfo").readLines().take(8192).mapNotNull { line ->
                val fields = line.split(' ')
                val dash = fields.indexOf("-")
                if (fields.size < 7 || dash < 0 || fields.size <= dash + 1) null else
                    Triple(fields[0], unescape(fields[4]), fields[dash + 1])
            }
            val roots = JSONArray()
            listOf("/data", "/data/user/$user", "/data/user_de/$user", "/data/media/$user",
                "/storage/emulated/$user").forEach { path ->
                val canonical = try { File(path).canonicalPath } catch (_: Exception) { path }
                val mount = mounts.filter { canonical == it.second || canonical.startsWith(it.second.trimEnd('/') + "/") }
                    .maxByOrNull { it.second.length }
                val readable = try {
                    val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
                    try { OsConstants.S_ISDIR(Os.fstat(fd).st_mode) } finally { Os.close(fd) }
                } catch (_: Exception) { false }
                roots.put(JSONObject().put("path", canonical).put("requestedPath", path).put("readable", readable)
                    .put("mountId", mount?.first ?: "unknown").put("filesystem", mount?.third ?: "unknown")
                    .put("aliasGroup", if (path.contains("/media/") || path.contains("/emulated/")) "shared-user-$user" else ""))
            }
            val unlocked: Any = try { getSystemService(UserManager::class.java)?.isUserUnlocked ?: JSONObject.NULL }
                catch (_: Exception) { JSONObject.NULL }
            return JSONObject().put("state", "ready").put("protocolVersion", IndexProtocol.VERSION)
                .put("uid", native.processUid).put("effectiveUid", native.effectiveUid).put("pid", native.processId)
                .put("coreVersion", native.coreVersion).put("mountNamespace", Os.readlink("/proc/self/ns/mnt"))
                .put("selinux", File("/proc/self/attr/current").readText().trim().trimEnd('\u0000'))
                .put("userId", user).put("userUnlocked", unlocked)
                .put("users", JSONArray((File("/data/user").list() ?: emptyArray()).filter { it.toIntOrNull() != null }.sorted()))
                .put("roots", roots).toString()
        }

        override fun start(sessionId: String, rootPath: ByteArray, output: ParcelFileDescriptor, client: IBinder) =
            startScan(sessionId, rootPath, output, client, null)

        override fun startBenchmark(sessionId: String, rootPath: ByteArray, output: ParcelFileDescriptor,
            client: IBinder, pipeline: Int) = startScan(sessionId, rootPath, output, client, pipeline)

        override fun startSubtree(taskId: String, rootPath: ByteArray, relativePath: ByteArray, identities: LongArray,
            filesystem: Long, output: ParcelFileDescriptor, client: IBinder) =
            startScan(taskId, rootPath, output, client, null, Triple(relativePath, identities, filesystem))

        private fun startScan(sessionId: String, rootPath: ByteArray, output: ParcelFileDescriptor,
            client: IBinder, benchmarkPipeline: Int?, scope: Triple<ByteArray, LongArray, Long>? = null) {
            try {
                authorize()
                val pipeline = benchmarkPipeline?.let(ScanPipeline::benchmark) ?: ScanPipeline.OPTIMIZED
                UUID.fromString(sessionId)
                require(rootPath.isNotEmpty() && rootPath.size <= 4096 && rootPath[0] == '/'.code.toByte() && !rootPath.contains(0))
                scope?.let { require(it.first.size <= 512 * 256 && it.second.size in 3..513 * 3 && it.second.size % 3 == 0 && it.third != 0L) }
                val job = synchronized(lock) {
                    check(!destroyed && active == null) { "Root scan already active" }
                    Job(sessionId, output, client).also { it.client.linkToDeath(it.death, 0); active = it }
                }
                worker.execute {
                    try {
                        val handle = if (scope == null) NativeBridge.nativeCreateScan(rootPath) else
                            NativeBridge.nativeCreateSubtreeScan(rootPath, scope.first, scope.second, scope.third)
                        synchronized(lock) { job.handle = handle; if (job.cancelled) NativeBridge.nativeCancelScan(handle) }
                        val uuid = UUID.fromString(sessionId)
                        val bytes = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
                        NativeBridge.nativeStreamScan(handle, output.fd, bytes, pipeline.nativeCode)
                    } catch (error: Throwable) {
                        Log.w("DiskSize.Root", "Root scan/transport stopped", error)
                    } finally {
                        synchronized(lock) {
                            if (job.handle != 0L) NativeBridge.nativeReleaseScan(job.handle)
                            job.handle = 0
                            try { job.client.unlinkToDeath(job.death, 0); job.fd.close() }
                            finally { if (active === job) active = null; job.done.countDown() }
                        }
                    }
                }
            } catch (error: Throwable) { output.close(); throw error }
        }

        override fun cancel(sessionId: String) {
            authorize()
            synchronized(lock) { active?.takeIf { it.id == sessionId }?.let { stop(it) } }
        }

        override fun awaitIdle(taskId: String, timeoutMillis: Long): Boolean {
            authorize(); require(timeoutMillis in 0..5000)
            val job = synchronized(lock) { active?.takeIf { it.id == taskId } } ?: return true
            return job.done.await(timeoutMillis, TimeUnit.MILLISECONDS)
        }

        override fun benchmarkTraversal(rootPath: ByteArray, pipeline: Int, measureMetadata: Boolean): LongArray {
            authorize()
            check(BuildConfig.SCAN_BENCHMARK)
            require(rootPath.isNotEmpty() && rootPath.size <= 4096 &&
                rootPath[0] == '/'.code.toByte() && !rootPath.contains(0))
            return synchronized(lock) {
                check(!destroyed && active == null)
                val handle = NativeBridge.nativeCreateScan(rootPath)
                try { NativeBridge.nativeBenchmarkTraversal(handle, pipeline, measureMetadata) }
                finally { NativeBridge.nativeReleaseScan(handle) }
            }
        }
    }

    private fun stop(job: Job) {
        job.cancelled = true
        if (job.handle != 0L) NativeBridge.nativeCancelScan(job.handle)
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() {
        synchronized(lock) { destroyed = true; active?.let { stop(it) } }
        worker.shutdown()
        super.onDestroy()
    }
    private fun unescape(path: String): String = Regex("\\\\([0-7]{3})").replace(path) {
        it.groupValues[1].toInt(8).toChar().toString()
    }
}
