package com.example.disksize.scan

import com.example.disksize.scan.ipc.NativeDiagnosticsParcel

internal object NativeBridge {
    init {
        System.loadLibrary("disksize_jni")
    }

    fun readDiagnostics(processName: String, appDataDirectory: String): NativeDiagnosticsParcel {
        val values = nativeRuntimeInfo()
        check(values.size == 7) { "Unexpected native diagnostics payload" }
        return NativeDiagnosticsParcel().apply {
            coreVersion = nativeVersion()
            protocolVersion = values[0].toInt()
            nativeAbi = nativeAbi()
            processId = values[1]
            processUid = values[2]
            effectiveUid = values[3]
            this.processName = processName
            pageSizeBytes = values[4]
            pointerBits = values[5]
            selfTestPassed = values[6] == 1L
            this.appDataDirectory = appDataDirectory
        }
    }

    private external fun nativeVersion(): String
    private external fun nativeAbi(): String
    private external fun nativeRuntimeInfo(): LongArray
    external fun nativeCreateScan(rootPath: ByteArray): Long
    external fun nativeRunScan(handle: Long, observer: NativeScanObserver): String
    external fun nativeRunScanToPipe(handle: Long, outputFd: Int, sessionId: ByteArray)
    external fun nativeCancelScan(handle: Long)
    external fun nativeReleaseScan(handle: Long)
}

internal fun interface NativeScanObserver {
    fun onProgress(nodes: Long, logicalBytes: Long, issues: Long, elapsedMicros: Long)
}
