package com.example.disksize.index

import android.os.ParcelFileDescriptor
import android.os.Process
import com.example.disksize.scan.NativeBridge
import java.util.concurrent.atomic.AtomicReference

internal object LocalScanPipe {
    fun run(handle: Long, id: String, writer: IndexConsumer, progress: (Long, Long, Long) -> Unit): StreamSummary {
        val pipe = ParcelFileDescriptor.createPipe()
        val failure = AtomicReference<Throwable?>(null)
        val producer = Thread({
            try { NativeBridge.nativeStreamScan(handle, pipe[1].fd, uuidBytes(id)) }
            catch (error: Throwable) { failure.set(error) }
            finally { pipe[1].close() }
        }, "metadata-producer")
        producer.start()
        try {
            return ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use {
                IndexProtocol.read(it, id, Process.myUid(), Process.myPid(), writer, progress)
            }.also { failure.get()?.let { error -> throw error } }
        } finally {
            NativeBridge.nativeCancelScan(handle)
            pipe[0].close()
            // Closing the consumer breaks writes; native also has a 10-second stall deadline.
            producer.join()
        }
    }
}
