package com.example.disksize.scan

import android.content.Context
import com.example.disksize.BuildConfig

internal object BenchmarkStorage {
    const val INDEX = "scan-benchmark-v1.db"
    const val JOURNAL = "scan-benchmark-v1.json"

    fun context(context: Context): Context {
        check(BuildConfig.SCAN_BENCHMARK)
        // Exclude benchmark output from /data/data scans and from the user's regular index.
        return context.createDeviceProtectedStorageContext()
    }
}
