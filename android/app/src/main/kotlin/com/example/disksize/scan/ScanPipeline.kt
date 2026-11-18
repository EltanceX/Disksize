package com.example.disksize.scan

import com.example.disksize.BuildConfig

/** Alternate strategies are reachable only in dedicated benchmark APKs. */
internal enum class ScanPipeline(val wireName: String, val nativeCode: Int, val reuseBuffers: Boolean) {
    OPTIMIZED("optimized", 0, true),
    BASELINE("baseline", 1, false),
    METADATA("metadata", 2, false),
    TRANSPORT("transport", 3, true);

    companion object {
        fun benchmark(name: String): ScanPipeline {
            check(BuildConfig.SCAN_BENCHMARK)
            return entries.single { it.wireName == name }
        }

        fun benchmark(code: Int): ScanPipeline {
            check(BuildConfig.SCAN_BENCHMARK)
            return entries.single { it.nativeCode == code }
        }
    }
}
