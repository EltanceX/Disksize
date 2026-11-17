package com.example.disksize.bridge

import android.content.Context
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.example.disksize.scan.CoordinatorConnection
import com.example.disksize.scan.CoordinatorService
import com.example.disksize.scan.ScanAccess
import com.example.disksize.scan.ipc.IScanCoordinator
import com.example.disksize.MainActivity
import com.example.disksize.open.ExternalOpener
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class ScannerPlatformApi(private val activity: MainActivity) : ScannerHostApi, AutoCloseable {
    private val context: Context = activity.applicationContext
    private val connection = CoordinatorConnection(context)
    private val processName = "${context.packageName}:scanner"
    @Volatile private var closed = false

    override suspend fun getScanAccessStatus(): ScanAccessStatus = withContext(Dispatchers.IO) { ScanAccess.read(context) }

    override suspend fun requestRootAccess() {
        withContext(Dispatchers.Main.immediate) { requireForeground() }
        callCoordinator { it.requestRootAccess() }
    }

    override suspend fun getRootStatus(): RootAccessStatus = callCoordinator { service ->
        val value = JSONObject(service.getRootStatus())
        val roots = value.optJSONArray("roots")
        val users = value.optJSONArray("users")
        RootAccessStatus(
            state = value.getString("state"), reason = value.optString("reason"),
            uid = if (value.has("uid")) value.getLong("uid") else null,
            effectiveUid = if (value.has("effectiveUid")) value.getLong("effectiveUid") else null,
            pid = if (value.has("pid")) value.getLong("pid") else null,
            mountNamespace = value.optString("mountNamespace").takeIf { it.isNotEmpty() },
            userId = if (value.has("userId")) value.getLong("userId") else null,
            userUnlocked = if (value.isNull("userUnlocked")) null else value.getBoolean("userUnlocked"),
            users = (0 until (users?.length() ?: 0)).map { users!!.getLong(it) },
            roots = (0 until (roots?.length() ?: 0)).map { roots!!.getJSONObject(it).let { root ->
                RootPath(root.getString("path"), root.getString("requestedPath"), root.getBoolean("readable"),
                    root.getString("mountId"), root.getString("filesystem"), root.getString("aliasGroup"))
            } },
        )
    }

    override suspend fun requestStorageAccess() = withContext(Dispatchers.Main.immediate) {
        requireForeground()
        if (Build.VERSION.SDK_INT >= 30) {
            val appSettings = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            try { activity.startActivity(appSettings) }
            catch (_: android.content.ActivityNotFoundException) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            activity.askPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    override suspend fun requestNotificationAccess() = withContext(Dispatchers.Main.immediate) {
        requireForeground()
        if (Build.VERSION.SDK_INT >= 33 && !ScanAccess.read(context).notificationGranted) {
            activity.askPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!ScanAccess.read(context).notificationGranted) {
            activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }

    override suspend fun startScan(rootPath: String, source: String): String {
        withContext(Dispatchers.Main.immediate) { requireForeground() }
        if (source == "shared" && !getScanAccessStatus().sharedAccessGranted) {
            throw FlutterError("storage-permission-required", "请先授予共享存储访问权限", null)
        }
        return callCoordinator { service ->
            val id = service.prepareScan(rootPath, source)
            try {
                withContext(Dispatchers.Main.immediate) {
                    requireForeground()
                    context.startForegroundService(Intent(context, CoordinatorService::class.java)
                        .setAction(CoordinatorService.ACTION_START).putExtra(CoordinatorService.EXTRA_SESSION, id))
                }
                id
            } catch (error: Exception) {
                try { service.rejectPreparedScan(id, "foreground-start-denied") } catch (_: Exception) { }
                throw FlutterError("foreground-start-denied", "请回到应用前台再开始扫描", null)
            }
        }
    }

    override suspend fun getScanSnapshot(): ScanSnapshot? = callCoordinator { service ->
        service.getScanSnapshot().takeIf { it.isNotEmpty() }?.let { snapshot(JSONObject(it)) }
    }

    override suspend fun cancelScan(sessionId: String) { callCoordinator { it.cancelScan(sessionId) } }
    override suspend fun queryIndex(requestJson: String): String {
        require(requestJson.length <= 8192)
        if (try { JSONObject(requestJson).optString("kind") == "volumes" } catch (_: org.json.JSONException) { false }) {
            return withContext(Dispatchers.IO) { ScanAccess.volumes(context) }
        }
        return callCoordinator { it.queryIndex(requestJson) }
    }

    override suspend fun openIndexedEntry(sessionId: String, nodeId: Long) {
        withContext(Dispatchers.Main.immediate) { requireForeground() }
        require(nodeId > 0)
        val detail = JSONObject(queryIndex(JSONObject().put("kind", "detail").put("sessionId", sessionId)
            .put("nodeId", nodeId).toString()))
        if (detail.optString("status") != "ok" || !detail.has("detail")) {
            throw FlutterError("entry-unavailable", "索引或条目已不可用，请刷新后重试。", null)
        }
        val intent = withContext(Dispatchers.IO) {
            val entry = detail.getJSONObject("detail")
            ExternalOpener.prepare(context, ExternalOpener.path(detail.getString("rootPath"), entry.getString("pathHex")), entry.getString("kind"))
        }
        withContext(Dispatchers.Main.immediate) { requireForeground(); ExternalOpener.launch(activity, intent) }
    }

    private fun requireForeground() {
        if (closed || !activity.isResumedForScan) {
            throw FlutterError("foreground-required", "请在应用前台执行此操作", null)
        }
    }

    private suspend fun <T> callCoordinator(operation: suspend (IScanCoordinator) -> T): T {
        if (closed) throw FlutterError("service-unavailable", "扫描服务已关闭", null)
        try {
            return withTimeout(10_000) {
                val service = connection.getService()
                withContext(Dispatchers.IO) { operation(service) }
            }
        } catch (error: TimeoutCancellationException) {
            throw FlutterError("service-timeout", "扫描服务响应超时，正在等待重新连接", null)
        } catch (error: CancellationException) { throw error
        } catch (error: FlutterError) { throw error
        } catch (error: Exception) {
            Log.e("DiskSize.Scan", "Scan command/query failed", error)
            throw FlutterError("scan-service-failure", "扫描服务暂不可用或已有任务，请刷新后重试", null)
        }
    }

    private fun snapshot(value: JSONObject): ScanSnapshot {
        val entries = value.getJSONArray("entries")
        val issues = value.getJSONArray("issues")
        return ScanSnapshot(
            sessionId = value.getString("sessionId"), rootPath = value.getString("rootPath"),
            state = value.getString("state"), reason = value.getString("reason"),
            startedAtMillis = value.getLong("startedAtMillis"), updatedAtMillis = value.getLong("updatedAtMillis"),
            endedAtMillis = value.getLong("endedAtMillis"), nodeCount = value.getLong("nodeCount"),
            logicalBytes = value.getLong("logicalBytes"), allocatedBytes = value.getLong("allocatedBytes"),
            provisional = value.getBoolean("provisional"), complete = value.getBoolean("complete"),
            issueCount = value.getLong("issueCount"), previewTruncated = value.getBoolean("previewTruncated"),
            serviceUid = value.getLong("serviceUid"), servicePid = value.getLong("servicePid"),
            active = value.getBoolean("active"), peakOpenFds = value.getLong("peakOpenFds"),
            openFdsAtFinish = value.getLong("openFdsAtFinish"),
            backend = value.optString("backend", "ordinary"),
            scannerUid = value.optLong("scannerUid", value.getLong("serviceUid")),
            scannerPid = value.optLong("scannerPid", value.getLong("servicePid")),
            rootMountId = if (value.has("rootMountId")) value.getLong("rootMountId") else null,
            entries = (0 until entries.length()).map { index -> entries.getJSONObject(index).let {
                ScanEntryPreview(it.getString("pathHex"), it.getString("kind"), it.getLong("logicalBytes"),
                    it.getLong("allocatedBytes"), it.getBoolean("complete"), it.getString("skipped"))
            } },
            issues = (0 until issues.length()).map { index -> issues.getJSONObject(index).let {
                ScanIssuePreview(it.getString("pathHex"), it.getString("code"), it.getString("operation"), it.getLong("systemError"))
            } },
        )
    }

    override suspend fun getNativeDiagnostics(): NativeDiagnostics {
        if (closed) {
            throw FlutterError("service-unavailable", "扫描服务已关闭", null)
        }
        try {
            val parcel = withTimeout(10_000L) {
                val service = connection.getService()
                withContext(Dispatchers.IO) { service.getNativeDiagnostics() }
            }
            if (parcel.errorCode.isNotEmpty()) {
                throw FlutterError(parcel.errorCode, parcel.errorMessage, null)
            }
            check(parcel.protocolVersion == 1) { "Unsupported native protocol" }
            check(parcel.processName == processName) { "Unexpected scanner process" }
            check(parcel.processId != Process.myPid().toLong()) { "Scanner must run separately" }
            check(parcel.processUid == Process.myUid().toLong()) { "Scanner UID mismatch" }
            check(parcel.effectiveUid == Process.myUid().toLong()) { "Scanner effective UID mismatch" }
            check(parcel.processUid > 0) { "Normal scanner must not run as root" }
            if (closed) {
                throw FlutterError("service-unavailable", "扫描服务已关闭", null)
            }
            Log.i(
                "DiskSize.Diagnostics",
                "core=${parcel.coreVersion} abi=${parcel.nativeAbi} " +
                    "hostPid=${Process.myPid()} servicePid=${parcel.processId} " +
                    "uid=${parcel.processUid} selfTest=${parcel.selfTestPassed}",
            )
            return NativeDiagnostics(
                coreVersion = parcel.coreVersion,
                protocolVersion = parcel.protocolVersion.toLong(),
                nativeAbi = parcel.nativeAbi,
                hostProcessId = Process.myPid().toLong(),
                hostUid = Process.myUid().toLong(),
                serviceProcessId = parcel.processId,
                serviceUid = parcel.processUid,
                effectiveUid = parcel.effectiveUid,
                serviceProcessName = parcel.processName,
                pageSizeBytes = parcel.pageSizeBytes,
                pointerBits = parcel.pointerBits,
                selfTestPassed = parcel.selfTestPassed,
                appDataDirectory = parcel.appDataDirectory,
            )
        } catch (error: TimeoutCancellationException) {
            throw FlutterError("service-timeout", "扫描服务响应超时，请重试", null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: FlutterError) {
            throw error
        } catch (error: RemoteException) {
            throw FlutterError("service-unavailable", "扫描服务连接中断，请重试", null)
        } catch (error: Exception) {
            Log.e("DiskSize.Diagnostics", "Diagnostics request failed", error)
            throw FlutterError("diagnostics-failure", "运行环境检查失败，请重试", null)
        }
    }

    override fun close() {
        closed = true
        connection.close()
    }
}
