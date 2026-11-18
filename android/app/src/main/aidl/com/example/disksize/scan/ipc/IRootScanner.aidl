package com.example.disksize.scan.ipc;

import android.os.ParcelFileDescriptor;

// Commands and descriptors only. No scan tree travels in a Binder transaction.
interface IRootScanner {
    String inspect();
    void start(String sessionId, in byte[] rootPath, in ParcelFileDescriptor output, IBinder client);
    void cancel(String sessionId);
    long[] benchmarkTraversal(in byte[] rootPath);
}
