package com.example.disksize.scan.ipc;

import com.example.disksize.scan.ipc.NativeDiagnosticsParcel;

interface IScanCoordinator {
    NativeDiagnosticsParcel getNativeDiagnostics();
    void requestRootAccess();
    String getRootStatus();
    String prepareScan(String rootPath, String source);
    String getScanSnapshot();
    void cancelScan(String sessionId);
    void rejectPreparedScan(String sessionId, String reason);
}
