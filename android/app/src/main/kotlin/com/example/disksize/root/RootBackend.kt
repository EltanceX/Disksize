package com.example.disksize.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.disksize.scan.ipc.IRootScanner
import com.example.disksize.index.IndexProtocol
import com.example.disksize.index.IndexConsumer
import com.example.disksize.index.StreamSummary
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

internal class RootBackend(private val context: Context) : AutoCloseable {
    companion object {
        init {
            // Configure once per coordinator process, before libsu creates its main shell.
            // A plain su can retain Android's app-data tmpfs masks even with UID 0.
            // This only configures the builder; su still requires an explicit request().
            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var remote: IRootScanner? = null
    private var pending: CompletableFuture<IRootScanner>? = null
    private var bound = false
    private var closed = false
    private var currentRead: ParcelFileDescriptor? = null
    private val client = Binder()
    @Volatile var status = JSONObject().put("state", "not-requested").toString()
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(lock) {
                if (closed || pending == null) { main.post { synchronized(lock) { release() } }; return }
                val service = IRootScanner.Stub.asInterface(binder)
                remote = service
                pending?.complete(service)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { disconnected() }
        override fun onBindingDied(name: ComponentName) { disconnected() }
        override fun onNullBinding(name: ComponentName) { disconnected() }
    }

    // Called only by the explicit foreground UI request. Reading status never invokes su.
    fun request() {
        synchronized(lock) {
            check(!closed)
            if (JSONObject(status).optString("state") == "requesting") return
            status = JSONObject().put("state", "requesting").toString()
        }
        executor.execute {
            try {
                val shell = Shell.getShell()
                if (!shell.isRoot) throw RootFailure("root-denied")
                val future = CompletableFuture<IRootScanner>()
                synchronized(lock) { check(!closed); pending = future }
                main.post {
                    synchronized(lock) {
                        if (closed) { future.completeExceptionally(RootFailure("root-closed")); return@post }
                        if (remote?.asBinder()?.isBinderAlive == true) future.complete(remote!!)
                        else {
                            try { bound = true; RootService.bind(Intent(context, RootScannerService::class.java), connection) }
                            catch (error: Exception) { future.completeExceptionally(error) }
                        }
                    }
                }
                val service = future.get(25, TimeUnit.SECONDS)
                val inspection = JSONObject(service.inspect())
                check(inspection.getInt("protocolVersion") == IndexProtocol.VERSION &&
                    inspection.getInt("uid") == 0 && inspection.getInt("effectiveUid") == 0)
                synchronized(lock) { check(!closed); status = inspection.toString() }
            } catch (error: Exception) {
                Log.w("DiskSize.Root", "Root authorization/binding failed", error)
                synchronized(lock) {
                    status = JSONObject().put("state", "unavailable")
                        .put("reason", if (error is RootFailure) error.reason else "root-bind-failed-or-timeout").toString()
                }
                main.post { synchronized(lock) { release() } }
                // A denied non-root main shell must not poison the next explicit retry.
                try { Shell.getCachedShell()?.close() } catch (_: Exception) { }
            } finally { synchronized(lock) { pending = null } }
        }
    }

    fun ready(): Boolean = synchronized(lock) {
        JSONObject(status).optString("state") == "ready" && remote?.asBinder()?.isBinderAlive == true
    }

    fun benchmarkTraversal(root: String): LongArray {
        val service = synchronized(lock) { check(!closed && ready()); remote!! }
        return service.benchmarkTraversal(root.toByteArray(Charsets.UTF_8))
    }

    fun run(id: String, root: String, writer: IndexConsumer, progress: (Long, Long, Long) -> Unit): StreamSummary {
        val service: IRootScanner
        val pid: Int
        val pipe = ParcelFileDescriptor.createPipe()
        synchronized(lock) {
            if (!ready() || closed) { pipe.forEach { it.close() }; throw RootFailure("root-unavailable") }
            service = remote!!; pid = JSONObject(status).getInt("pid"); currentRead = pipe[0]
        }
        try {
            service.start(id, root.toByteArray(Charsets.UTF_8), pipe[1], client)
            pipe[1].close() // Binder owns an independent writer descriptor.
            return ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use {
                IndexProtocol.read(it, id, 0, pid, writer, progress)
            }
        } catch (error: Exception) {
            try { service.cancel(id) } catch (_: Exception) { }
            if (!service.asBinder().isBinderAlive) throw RootFailure("root-process-died", error)
            if (error is RootFailure) throw error
            throw RootFailure("root-pipe-or-protocol-failure", error)
        } finally {
            pipe.forEach { try { it.close() } catch (_: Exception) { } }
            synchronized(lock) { currentRead = null }
        }
    }

    fun cancel(id: String) {
        val service = synchronized(lock) { if (closed) return; remote } ?: return
        try { executor.execute { try { service.cancel(id) } catch (_: Exception) { } } }
        catch (_: RejectedExecutionException) { /* Service destruction already closed the pipe. */ }
    }

    private fun disconnected() {
        synchronized(lock) {
            status = JSONObject().put("state", "unavailable").put("reason", "root-process-died").toString()
            pending?.completeExceptionally(RootFailure("root-process-died"))
            try { currentRead?.close() } catch (_: Exception) { }
            // libsu removes its connection record after invoking this callback.
            // Calling unbind inline mutates that live ArrayMap iterator (6.0.0).
            remote = null
            bound = false
        }
    }
    private fun release() {
        remote = null
        if (bound) { bound = false; try { RootService.unbind(connection) } catch (_: Exception) { } }
    }
    override fun close() {
        synchronized(lock) {
            closed = true
            pending?.completeExceptionally(RootFailure("root-closed"))
            try { currentRead?.close() } catch (_: Exception) { }
        }
        main.post { synchronized(lock) { release() } }
        executor.shutdown()
    }
}

internal class RootFailure(val reason: String, cause: Throwable? = null) : Exception(reason, cause)
