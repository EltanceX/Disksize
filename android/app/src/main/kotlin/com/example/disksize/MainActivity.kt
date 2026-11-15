package com.example.disksize

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import com.example.disksize.bridge.ScannerHostApi
import com.example.disksize.bridge.ScannerPlatformApi
import kotlinx.coroutines.CompletableDeferred

class MainActivity : FlutterActivity() {
    private var scannerApi: ScannerPlatformApi? = null
    var isResumedForScan: Boolean = false
        private set
    private var permissionResult: CompletableDeferred<Unit>? = null

    override fun onResume() {
        super.onResume()
        isResumedForScan = true
    }

    override fun onPause() {
        isResumedForScan = false
        super.onPause()
    }

    suspend fun askPermission(permission: String) {
        check(isResumedForScan) { "Activity is not in the foreground" }
        check(permissionResult == null) { "Permission request already open" }
        val result = CompletableDeferred<Unit>()
        permissionResult = result
        try {
            requestPermissions(arrayOf(permission), 301)
            result.await()
        } finally {
            permissionResult = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 301) permissionResult?.complete(Unit)
    }

    override fun onDestroy() {
        permissionResult?.cancel()
        super.onDestroy()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        scannerApi?.close()
        scannerApi = ScannerPlatformApi(this).also {
            ScannerHostApi.setUp(flutterEngine.dartExecutor.binaryMessenger, it)
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        ScannerHostApi.setUp(flutterEngine.dartExecutor.binaryMessenger, null)
        scannerApi?.close()
        scannerApi = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
