package com.example.disksize.scan

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.example.disksize.scan.ipc.IScanCoordinator
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class CoordinatorConnection(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val waiting = mutableSetOf<CancellableContinuation<IScanCoordinator>>()
    private var remote: IScanCoordinator? = null
    private var bound = false
    private var closed = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) {
                releaseBinding()
                return
            }
            val service = IScanCoordinator.Stub.asInterface(binder)
            remote = service
            val ready = waiting.toList()
            waiting.clear()
            ready.filter { it.isActive }.forEach { it.resume(service) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            failWaiting(IllegalStateException("Scanner service disconnected"))
            releaseBinding()
        }

        override fun onBindingDied(name: ComponentName) {
            failWaiting(IllegalStateException("Scanner service binding died"))
            releaseBinding()
        }

        override fun onNullBinding(name: ComponentName) {
            failWaiting(IllegalStateException("Scanner service returned no Binder"))
            releaseBinding()
        }
    }

    suspend fun getService(): IScanCoordinator = withContext(Dispatchers.Main.immediate) {
        check(!closed) { "Scanner connection is closed" }
        remote?.takeIf { it.asBinder().isBinderAlive }?.let { return@withContext it }
        if (remote != null) {
            releaseBinding()
        }
        suspendCancellableCoroutine { continuation ->
            waiting.add(continuation)
            continuation.invokeOnCancellation {
                mainHandler.post {
                    waiting.remove(continuation)
                    if (waiting.isEmpty() && remote == null) {
                        releaseBinding()
                    }
                }
            }
            if (!bound) {
                try {
                    bound = context.bindService(
                        Intent(context, CoordinatorService::class.java),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                    if (!bound) {
                        failWaiting(IllegalStateException("Unable to bind scanner service"))
                    }
                } catch (error: Exception) {
                    failWaiting(error)
                }
            }
        }
    }

    private fun failWaiting(error: Exception) {
        val failed = waiting.toList()
        waiting.clear()
        failed.filter { it.isActive }.forEach { it.resumeWithException(error) }
    }

    private fun releaseBinding() {
        remote = null
        if (bound) {
            bound = false
            try {
                context.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // The system may already have removed a dead binding.
            }
        }
    }

    override fun close() {
        mainHandler.post {
            closed = true
            failWaiting(IllegalStateException("Scanner connection closed"))
            releaseBinding()
        }
    }
}
