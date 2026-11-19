package com.example.disksize.scan.ipc;

import android.os.ParcelFileDescriptor;

// Commands and descriptors only. No scan tree travels in a Binder transaction.
interface IRootScanner {
    String inspect();
    void start(String sessionId, in byte[] rootPath, in ParcelFileDescriptor output, IBinder client);
    void cancel(String sessionId);
    long[] benchmarkTraversal(in byte[] rootPath, int pipeline, boolean measureMetadata);
    void startBenchmark(String sessionId, in byte[] rootPath, in ParcelFileDescriptor output, IBinder client, int pipeline);
    void startSubtree(String taskId, in byte[] rootPath, in byte[] relativePath, in long[] identities,
        long filesystem, in ParcelFileDescriptor output, IBinder client);
    boolean awaitIdle(String taskId, long timeoutMillis);
}
