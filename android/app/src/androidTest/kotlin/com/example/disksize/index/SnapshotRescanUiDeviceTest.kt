package com.example.disksize.index

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.disksize.BuildConfig
import dev.flutter.plugins.integration_test.IntegrationTestPlugin
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Dedicated target only: never launch the integration entry point against ordinary storage. */
@RunWith(AndroidJUnit4::class)
class SnapshotRescanUiDeviceTest {
    @Test fun realFlutterDirectoryRescanFlow() {
        assumeTrue("Requires the dedicated Flutter UI target", InstrumentationRegistry.getArguments().getString("snapshotRescanUi") == "true")
        check(BuildConfig.RESCAN_TEST_HOST && !BuildConfig.SCAN_BENCHMARK)
        InstrumentationRegistry.getInstrumentation().openBenchmarkActivity()
        val results = IntegrationTestPlugin.testResults.get(150, TimeUnit.SECONDS)
        assertTrue("No Flutter UI tests returned", results.isNotEmpty())
        results.forEach { (name, outcome) -> assertEquals(name, "success", outcome) }
    }
}
