package com.example.disksize.scan

import android.content.Context
import com.example.disksize.BuildConfig
import com.example.disksize.index.ImportPolicy
import java.io.File

internal object BenchmarkStorage {
    const val INDEX = "scan-benchmark-v1.db"
    const val JOURNAL = "scan-benchmark-v1.json"
    const val IMPORT_VARIANT = "scan-benchmark-variant"

    fun context(context: Context): Context {
        check(BuildConfig.SCAN_BENCHMARK)
        // Exclude benchmark output from /data/data scans and from the user's regular index.
        return context.createDeviceProtectedStorageContext()
    }

    fun importPolicy(storage: Context): ImportPolicy {
        check(BuildConfig.SCAN_BENCHMARK)
        val file = File(storage.noBackupFilesDir, IMPORT_VARIANT)
        return if (file.isFile) ImportPolicy.benchmark(file.readText()) else ImportPolicy.DEFAULT
    }
}
