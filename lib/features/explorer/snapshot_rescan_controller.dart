import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../../platform/snapshot_rescan_client.dart';

String rescanErrorMessage(String code) => switch (code) {
  'busy' => '已有扫描或快照更新任务，请等待结束后重试。',
  'retained' => '此快照已保留，请到快照列表取消保留后再重新扫描。',
  'stale-revision' || 'stale-cursor' => '快照版本已变化，请重新加载并确认范围后再扫描。',
  'snapshot-not-found' ||
  'snapshot-unavailable' => '快照已不可用或尚未完成索引，请返回快照列表重新选择。',
  'node-not-found' || 'not-directory' => '所选条目已变化，请重新加载目录并选择文件夹。',
  'target-missing' ||
  'target-type-changed' => '目标目录已消失或类型已变化。旧快照未改动，请重新扫描它的父目录。',
  'incompatible-identity' => '扫描身份、启动或挂载已变化，无法安全更新；请新建完整快照。',
  'root-unavailable' => 'Root 扫描连接不可用，请返回扫描首页显式连接 Root 后重试。',
  'incomplete-scan' => '本次子树扫描不完整（Partial）或存在问题，未合并，旧快照未改动。',
  'storage-error' => '暂存或保存失败，请检查可用空间。提交结果以任务最终状态为准。',
  'process-interrupted' => '任务已中断，未提交更新，旧快照未改动。',
  'cancelled' => '已取消，旧快照未改动。',
  'foreground-required' || 'foreground-start-denied' => '请回到应用前台后重新扫描。',
  'scan-failed' ||
  'protocol-error' ||
  'apply-failed' => '扫描或应用更新失败，旧快照未改动，请重试。',
  _ => '无法完成操作（$code），请重新查询任务状态。',
};

String rescanStage(String state) => switch (state) {
  'preparing' => '正在准备',
  'scanning' => '正在扫描',
  'validating' => '正在校验',
  'applying' => '正在应用更新',
  'completed' => '更新已提交',
  'cancelled' => '已取消',
  'interrupted' => '任务已中断',
  'stale' => '版本冲突，未更新',
  _ => '更新失败',
};

/// One persistent task reference. Polls are serial, stop in the background, and
/// never infer a terminal result from a cancellation request or transport error.
class SnapshotRescanController extends ChangeNotifier {
  SnapshotRescanController(this.client);
  final SnapshotRescanClient client;
  SnapshotRescanTask? task;
  String? error;
  bool starting = false, cancelling = false;
  bool _reading = false, _disposed = false, _resumed = true;
  String? _taskId;
  Timer? _timer;
  bool get blocked =>
      starting ||
      cancelling ||
      task?.active == true ||
      (_taskId != null && task == null);

  Future<void> start(String snapshot, int node, int revision) async {
    if (blocked || _disposed) return;
    starting = true;
    error = null;
    task = null;
    _taskId = null;
    _timer?.cancel();
    notifyListeners();
    try {
      final result = await client.start(snapshot, node, revision);
      if (_disposed) return;
      _taskId = result.id;
      task = result;
    } catch (e) {
      if (!_disposed) error = _message(e);
    } finally {
      if (!_disposed) {
        starting = false;
        notifyListeners();
        _schedule();
      }
    }
  }

  Future<void> attach(String id) async {
    if (_disposed || starting || _taskId == id) return;
    _taskId = id;
    task = null;
    await refresh();
  }

  Future<void> refresh() async {
    final id = _taskId;
    if (_disposed || _reading || id == null) return;
    _timer?.cancel();
    _reading = true;
    try {
      final result = await client.task(id);
      if (_disposed || _taskId != id) return;
      if (result == null) {
        // Missing is not evidence of cancellation or successful rollback.
        error = '任务记录暂不可用，请重新加载快照核对内容版本。';
      } else {
        task = result;
        error = null;
      }
    } catch (e) {
      if (!_disposed && _taskId == id) {
        error = '暂时无法确认任务结果；任务可能仍在运行。${_message(e)}';
      }
    } finally {
      _reading = false;
      if (!_disposed) {
        notifyListeners();
        _schedule();
      }
    }
  }

  Future<void> cancel() async {
    if (_disposed || cancelling || task?.active != true) return;
    cancelling = true;
    error = null;
    notifyListeners();
    try {
      await client.cancel(task!.id);
      await refresh();
    } catch (e) {
      if (!_disposed) error = '取消结果尚未确认。${_message(e)}';
    } finally {
      if (!_disposed) {
        cancelling = false;
        notifyListeners();
        _schedule();
      }
    }
  }

  void setResumed(bool value) {
    _resumed = value;
    _timer?.cancel();
    if (value) unawaited(refresh());
  }

  String _message(Object e) => rescanErrorMessage(switch (e) {
    SnapshotRescanException(:final code) => code,
    PlatformException(:final code) => code,
    _ => 'service-unavailable',
  });

  void _schedule() {
    _timer?.cancel();
    if (_resumed &&
        !_disposed &&
        _taskId != null &&
        (task == null || task!.active)) {
      _timer = Timer(const Duration(seconds: 1), () => unawaited(refresh()));
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _timer?.cancel();
    super.dispose();
  }
}
