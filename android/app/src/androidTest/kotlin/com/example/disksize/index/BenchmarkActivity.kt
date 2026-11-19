package com.example.disksize.index

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.disksize.MainActivity

internal fun Instrumentation.openBenchmarkActivity() {
    val component = "${targetContext.packageName}/${MainActivity::class.java.name}"
    val result = ParcelFileDescriptor.AutoCloseInputStream(
        uiAutomation.executeShellCommand("am start -W --user ${Process.myUid() / 100000} -n $component"),
    ).bufferedReader().use { it.readText() }
    check(result.lineSequence().any { it.trim() == "Status: ok" }) { "Benchmark activity did not launch: $result" }
    val deadline = SystemClock.elapsedRealtime() + 5_000
    do {
        var focused = false
        runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull { it.hasWindowFocus() }
            if (activity != null) {
                // Window-scoped; no change to device power or foreground-service settings.
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                focused = true
            }
        }
        if (focused) return
        SystemClock.sleep(25)
    } while (SystemClock.elapsedRealtime() < deadline)
    error("Benchmark activity has no foreground focus; keep the test device unlocked")
}

internal fun Instrumentation.returnToBenchmarkActivity() {
    runOnMainSync {
        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>().single()
        check(activity.moveTaskToBack(true)) { "Could not background the benchmark activity" }
    }
    val deadline = SystemClock.elapsedRealtime() + 5_000
    do {
        var resumed = false
        runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .any { it is MainActivity }
        }
        if (!resumed) {
            openBenchmarkActivity()
            return
        }
        SystemClock.sleep(25)
    } while (SystemClock.elapsedRealtime() < deadline)
    error("Benchmark activity did not move to the background")
}
