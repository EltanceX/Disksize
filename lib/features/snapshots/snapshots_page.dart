import 'package:flutter/material.dart';

import '../../platform/index_client.dart';
import '../../platform/snapshot_client.dart';
import '../explorer/explorer_format.dart';
import '../explorer/explorer_page.dart';
import 'snapshot_comparison_page.dart';

class SnapshotsPage extends StatefulWidget {
  const SnapshotsPage({
    super.key,
    required this.client,
    required this.indexClient,
    this.initialBase,
  });
  final SnapshotClient client;
  final IndexClient indexClient;
  final String? initialBase;
  @override
  State<SnapshotsPage> createState() => _SnapshotsPageState();
}

class _SnapshotsPageState extends State<SnapshotsPage> {
  SnapshotList? _page;
  DiskSnapshot? _base;
  bool _busy = true;
  String? _error;
  int _generation = 0;
  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load({String? cursor}) async {
    if (!mounted) return;
    final generation = ++_generation;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final page = await widget.client.list(cursor: cursor);
      if (!mounted || generation != _generation) return;
      setState(() {
        _page = page;
        for (final item in page.snapshots) {
          if (item.id == (_base?.id ?? widget.initialBase)) _base = item;
        }
      });
    } catch (error) {
      if (mounted && generation == _generation) {
        setState(() => _error = '$error');
      }
    } finally {
      if (mounted && generation == _generation) setState(() => _busy = false);
    }
  }

  Future<void> _change(DiskSnapshot snapshot, {bool delete = false}) async {
    if (delete) {
      final approved = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          scrollable: true,
          title: const Text('删除快照与索引？'),
          content: Text(
            '${snapshot.rootPath}\n${formatTime(snapshot.started)}\n\n仅删除 DiskSize 保存的快照和扫描索引，原文件不受影响。',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('删除'),
            ),
          ],
        ),
      );
      if (approved != true || !mounted) return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      if (delete) {
        await widget.client.delete(snapshot.id);
        if (_base?.id == snapshot.id) _base = null;
      } else {
        await widget.client.retain(snapshot.id, !snapshot.retained);
      }
      await _load();
    } catch (error) {
      if (mounted) {
        setState(() {
          _error = '$error';
          _busy = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('快照与增长对比'),
      actions: [
        IconButton(
          tooltip: '刷新快照',
          onPressed: _busy ? null : _load,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 900),
        child: Column(
          children: [
            if (_busy) const LinearProgressIndicator(),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  const Text(
                    '完成及部分完成的扫描会自动保存为快照。保留标记可防止误删；取消保留后才能删除。扫描记录反映一段时间内的观察，并非文件系统原子快照。',
                  ),
                  if (_base != null)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      child: Text(
                        '基准：${_base!.rootPath}\n${formatTime(_base!.started)}',
                      ),
                    ),
                  if (_error != null)
                    Text(_error!, key: const Key('snapshot-error')),
                  if (!_busy && _page?.snapshots.isEmpty == true)
                    const Padding(
                      padding: EdgeInsets.all(24),
                      child: Text('暂无快照。完成一次扫描后即可在此对比。'),
                    ),
                  for (final item in _page?.snapshots ?? <DiskSnapshot>[])
                    Card.outlined(
                      child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              item.rootPath,
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                            Text(
                              '${item.backend == 'root' ? 'Root' : '普通'} · ${item.complete ? '完整快照' : '部分快照 / 含未知范围'}${item.retained ? ' · 已保留' : ''}',
                            ),
                            Text(
                              '${formatTime(item.started)} → ${formatTime(item.ended)}',
                            ),
                            Text(
                              '逻辑 ${formatBytes(item.logical)} · 报告占用 ${formatBytes(item.allocated)}${item.complete ? '' : '（已知量）'}',
                            ),
                            if (!item.available) const Text('索引不可用，请重新扫描。'),
                            Wrap(
                              spacing: 8,
                              children: [
                                TextButton(
                                  onPressed: _busy || !item.available
                                      ? null
                                      : () => setState(() => _base = item),
                                  child: Text(
                                    _base?.id == item.id ? '当前基准' : '设为基准',
                                  ),
                                ),
                                FilledButton.tonal(
                                  onPressed:
                                      _busy ||
                                          _base == null ||
                                          _base?.id == item.id ||
                                          !item.available
                                      ? null
                                      : () => Navigator.push(
                                          context,
                                          MaterialPageRoute<void>(
                                            builder: (_) =>
                                                SnapshotComparisonPage(
                                                  client: widget.client,
                                                  baseId: _base!.id,
                                                  targetId: item.id,
                                                ),
                                          ),
                                        ),
                                  child: const Text('作为新快照对比'),
                                ),
                                TextButton(
                                  onPressed: _busy
                                      ? null
                                      : () => Navigator.push(
                                          context,
                                          MaterialPageRoute<void>(
                                            builder: (_) => ExplorerPage(
                                              client: widget.indexClient,
                                              sessionId: item.id,
                                            ),
                                          ),
                                        ),
                                  child: const Text('浏览索引'),
                                ),
                                TextButton(
                                  onPressed: _busy ? null : () => _change(item),
                                  child: Text(item.retained ? '取消保留' : '保留快照'),
                                ),
                                TextButton(
                                  onPressed: _busy || item.retained
                                      ? null
                                      : () => _change(item, delete: true),
                                  child: const Text('删除快照'),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
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
                    child: const Text('回到最新'),
                  ),
                  TextButton(
                    onPressed: _busy || _page?.nextCursor == null
                        ? null
                        : () => _load(cursor: _page!.nextCursor),
                    child: const Text('更早快照'),
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
