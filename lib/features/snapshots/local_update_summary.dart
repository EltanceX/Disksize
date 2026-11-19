import 'package:flutter/material.dart';

import '../../platform/scan_client.dart';
import '../../platform/snapshot_client.dart';
import '../explorer/explorer_format.dart';

class LocalUpdateSummary extends StatelessWidget {
  const LocalUpdateSummary({super.key, required this.update, this.revision});
  final SnapshotLocalUpdate update;
  final int? revision;

  @override
  Widget build(BuildContext context) {
    if (!update.updated) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '已局部更新 · ${formatTime(update.at)} · ${update.count} 次'
          '${revision == null ? '' : ' · 内容版本 $revision'}',
          style: Theme.of(context).textTheme.labelLarge,
        ),
        const Text('各范围采集时间不同；其余目录沿用原记录，更新时间不代表全盘状态。'),
        ExpansionTile(
          tilePadding: EdgeInsets.zero,
          title: const Text('最近局部更新详情'),
          childrenPadding: const EdgeInsets.only(bottom: 12),
          expandedCrossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SelectableText(
              '相对扫描根的范围：${update.pathHex.isEmpty ? '整个快照根目录' : displayScanPath(update.pathHex)}'
              '${update.pathTruncated ? '…（路径过长，仅显示前段）' : ''}',
            ),
            Text(
              '本次扫描 ${formatTime(update.started)} → ${formatTime(update.scanned)}',
            ),
            Text('提交完成 ${formatTime(update.ended)}'),
          ],
        ),
      ],
    );
  }
}
