package com.example.disksize.scan.ipc;

parcelable NativeDiagnosticsParcel {
    String errorCode = "";
    String errorMessage = "";
    String coreVersion;
    int protocolVersion;
    String nativeAbi;
    long processId;
    long processUid;
    long effectiveUid;
    String processName;
    long pageSizeBytes;
    long pointerBits;
    boolean selfTestPassed;
    String appDataDirectory;
}
