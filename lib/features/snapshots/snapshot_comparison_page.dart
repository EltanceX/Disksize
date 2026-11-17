import 'package:flutter/material.dart';

import '../../platform/scan_client.dart';
import '../../platform/snapshot_client.dart';
import '../explorer/explorer_format.dart';

const changeLabels = {
  'all': '全部变化',
  'added': '新增',
  'deleted': '删除',
  'changed': '容量 / 类型变化',
  'unknown': '未知',
  'growth': '增长最多的目录',
};
String deltaText(int? value) =>
    value == null ? '未知' : '${value > 0 ? '+' : ''}${formatBytes(value)}';
String snapshotKind(String kind) => switch (kind) {
  'regular' => '文件',
  'directory' => '目录',
  'symlink' => '符号链接',
  'special' => '特殊文件',
  _ => '未发现 / 未知',
};

class SnapshotComparisonPage extends StatefulWidget {
  const SnapshotComparisonPage({
    super.key,
    required this.client,
    required this.baseId,
    required this.targetId,
  });
  final SnapshotClient client;
  final String baseId, targetId;
  @override
  State<SnapshotComparisonPage> createState() => _SnapshotComparisonPageState();
}

class _SnapshotComparisonPageState extends State<SnapshotComparisonPage> {
  SnapshotComparison? _page;
  String _sort = 'logical', _filter = 'all';
  bool _busy = true;
  String? _error;
  int _generation = 0;
  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load({String? cursor}) async {
    final generation = ++_generation;
    setState(() {
      _busy = true;
      _error = null;
      _page = null;
    });
    try {
      while (mounted && generation == _generation) {
        final page = await widget.client.compare(
          widget.baseId,
          widget.targetId,
          sort: _sort,
          filter: _filter,
          cursor: cursor,
        );
        if (!mounted || generation != _generation) return;
        setState(() => _page = page);
        if (page.status != 'building') break;
        await Future<void>.delayed(const Duration(milliseconds: 80));
      }
    } catch (error) {
      if (mounted && generation == _generation) {
        setState(() => _error = '$error');
      }
    } finally {
      if (mounted && generation == _generation) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final page = _page;
    final allocated = _sort == 'allocated';
    String amount(int? value) => value == null ? '未知' : formatBytes(value);
    return Scaffold(
      appBar: AppBar(
        title: const Text('快照增长对比'),
        actions: [
          IconButton(
            tooltip: '重新对比',
            onPressed: _busy ? null : _load,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 1000),
          child: Column(
            children: [
              if (_busy) const LinearProgressIndicator(),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.all(16),
                  children: [
                    const Text('按相对路径比较；重命名按删除和新增展示。目录增长含子目录，父子目录的差额不能相加。'),
                    if (page?.base != null && page?.target != null)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 12),
                        child: Text(
                          '${page!.base!.rootPath}\n基准 ${formatTime(page.base!.started)} → ${formatTime(page.base!.ended)}\n新快照 ${formatTime(page.target!.started)} → ${formatTime(page.target!.ended)}',
                        ),
                      ),
                    Wrap(
                      spacing: 12,
                      runSpacing: 12,
                      children: [
                        DropdownButton<String>(
                          value: _sort,
                          items: const [
                            DropdownMenuItem(
                              value: 'logical',
                              child: Text('逻辑大小'),
                            ),
                            DropdownMenuItem(
                              value: 'allocated',
                              child: Text('报告占用'),
                            ),
                          ],
                          onChanged: _busy
                              ? null
                              : (value) {
                                  setState(() => _sort = value!);
                                  _load();
                                },
                        ),
                        DropdownButton<String>(
                          value: _filter,
                          items: [
                            for (final entry in changeLabels.entries)
                              DropdownMenuItem(
                                value: entry.key,
                                child: Text(entry.value),
                              ),
                          ],
                          onChanged: _busy
                              ? null
                              : (value) {
                                  setState(() => _filter = value!);
                                  _load();
                                },
                        ),
                      ],
                    ),
                    if (_error != null) Text('对比失败：$_error'),
                    if (page?.status == 'building')
                      Text('正在计算，已比较 ${page!.processed} 个路径…'),
                    if (page?.status == 'incompatible') ...[
                      const Text('快照不兼容'),
                      for (final reason in page!.reasons) Text('• $reason'),
                    ],
                    if (page != null &&
                        ![
                          'ok',
                          'building',
                          'incompatible',
                        ].contains(page.status))
                      Text(
                        page.status == 'not-found'
                            ? '快照已删除，请返回刷新。'
                            : '对比数据已失效，请重新对比（${page.status}）。',
                      ),
                    if (page?.status == 'ok') ...[
                      Text(
                        '总差额：${deltaText(allocated ? page!.allocatedDelta : page!.logicalDelta)}',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      if (!page.complete)
                        const Text(
                          '含未知范围，无法确认总差额。已知量的差值不代表真实增长或释放量；以下仅确认可比较路径的变化。',
                        ),
                      Text(
                        [
                          for (final kind in [
                            'added',
                            'deleted',
                            'changed',
                            'unknown',
                          ])
                            '${changeLabels[kind]} ${page.counts[kind] ?? 0}',
                        ].join(' · '),
                      ),
                      if (page.entries.isEmpty)
                        const Padding(
                          padding: EdgeInsets.all(24),
                          child: Text('当前筛选下没有变化。'),
                        ),
                      for (final change in page.entries)
                        Card.outlined(
                          child: ListTile(
                            title: Text(displayScanPath(change.pathHex)),
                            subtitle: Text(
                              '${changeLabels[change.status]} · ${snapshotKind(change.oldKind)} → ${snapshotKind(change.newKind)}\n${amount(allocated ? change.oldAllocated : change.oldLogical)} → ${amount(allocated ? change.newAllocated : change.newLogical)}\n差额 ${deltaText(allocated ? change.allocatedDelta : change.logicalDelta)}${change.status == 'unknown' ? ' · 不可访问、主动跳过或未完成' : ''}',
                            ),
                          ),
                        ),
                    ],
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.all(8),
                child: Wrap(
                  spacing: 16,
                  children: [
                    TextButton(
                      onPressed: _busy ? null : _load,
                      child: const Text('第一页'),
                    ),
                    TextButton(
                      onPressed: _busy || page?.nextCursor == null
                          ? null
                          : () => _load(cursor: page!.nextCursor),
                      child: const Text('下一页'),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
