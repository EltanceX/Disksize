import 'package:flutter/material.dart';

import '../../platform/scan_client.dart';
import 'snapshot_rescan_controller.dart';

class SnapshotRescanPanel extends StatelessWidget {
  const SnapshotRescanPanel({super.key, required this.controller});
  final SnapshotRescanController controller;

  @override
  Widget build(BuildContext context) {
    final task = controller.task;
    if (task == null && controller.error == null && !controller.starting) {
      return const SizedBox.shrink();
    }
    return Card.outlined(
      key: const Key('rescan-status'),
      margin: const EdgeInsets.symmetric(vertical: 12),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              task == null ? '正在确认重扫任务' : rescanStage(task.state),
              style: Theme.of(context).textTheme.titleMedium,
            ),
            if (controller.starting || task?.active == true)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 8),
                child: LinearProgressIndicator(),
              ),
            if (task != null) ...[
              Text(
                '范围：${task.pathHex.isEmpty ? '整个快照根目录' : displayScanPath(task.pathHex)}'
                '${task.pathTruncated ? '…（仅显示前段）' : ''}',
              ),
              Text('已发现 ${task.nodes} 个条目 · ${task.issues} 个问题'),
              if (task.active)
                Text(
                  task.state == 'applying'
                      ? '正在提交；当前显示旧版本，完成后统一重新加载。'
                      : '当前可浏览旧版本。扫描和校验完整通过后才更新快照。',
                ),
              if (task.state == 'completed')
                Text('已更新同一份快照 · 内容版本 ${task.committedRevision}。其余范围仍沿用原采集记录。'),
              if (!task.active && task.state != 'completed')
                Text(rescanErrorMessage(task.error)),
            ],
            if (controller.error != null)
              Text(controller.error!, key: const Key('rescan-error')),
            Wrap(
              spacing: 8,
              children: [
                if (task?.active == true)
                  TextButton(
                    key: const Key('rescan-cancel'),
                    onPressed:
                        controller.cancelling || task!.cancellationRequested
                        ? null
                        : controller.cancel,
                    child: Text(
                      controller.cancelling || task!.cancellationRequested
                          ? '正在确认取消结果…'
                          : '取消重扫',
                    ),
                  ),
                TextButton(
                  key: const Key('rescan-query'),
                  onPressed: controller.starting ? null : controller.refresh,
                  child: const Text('查询任务状态'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
