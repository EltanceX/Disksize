import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../platform/index_client.dart';
import '../../platform/entry_opener.dart';
import '../../platform/scan_client.dart';
import 'explorer_format.dart';
import 'external_open_button.dart';
import '../snapshots/local_update_summary.dart';

class ExplorerDetail extends StatefulWidget {
  const ExplorerDetail({
    super.key,
    required this.client,
    required this.sessionId,
    required this.nodeId,
    required this.onLocate,
    this.opener,
    this.expectedRevision,
    this.onRescan,
    this.rescanEnabled = true,
  });
  final IndexClient client;
  final String sessionId;
  final int nodeId;
  final ValueChanged<int> onLocate;
  final EntryOpener? opener;
  final int? expectedRevision;
  final void Function(int nodeId, int revision)? onRescan;
  final bool rescanEnabled;
  @override
  State<ExplorerDetail> createState() => _ExplorerDetailState();
}

class _ExplorerDetailState extends State<ExplorerDetail> {
  IndexPage? _page, _issues;
  String? _error;
  bool _loading = false;
  int _generation = 0;
  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant ExplorerDetail oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.nodeId != widget.nodeId ||
        oldWidget.sessionId != widget.sessionId ||
        oldWidget.expectedRevision != widget.expectedRevision) {
      _load();
    }
  }

  Future<void> _load({String? cursor}) async {
    final generation = ++_generation;
    setState(() {
      _loading = true;
      _error = null;
      _page = null;
      _issues = null;
    });
    try {
      final values = await Future.wait([
        widget.client.query(
          IndexRequest(
            sessionId: widget.sessionId,
            kind: IndexQueryKind.detail,
            nodeId: widget.nodeId,
            expectedRevision: widget.expectedRevision,
          ),
        ),
        widget.client.query(
          IndexRequest(
            sessionId: widget.sessionId,
            kind: IndexQueryKind.issues,
            nodeId: widget.nodeId,
            limit: 20,
            cursor: cursor,
            expectedRevision: widget.expectedRevision,
          ),
        ),
      ]);
      if (!mounted || generation != _generation) return;
      setState(() {
        if (values.any((p) => p.status != 'ok') ||
            values[0].revision != values[1].revision ||
            (widget.expectedRevision != null &&
                values[0].revision != widget.expectedRevision)) {
          _error = '条目已变化或索引正在更新，请刷新详情。';
        } else {
          _page = values[0];
          _issues = values[1];
        }
      });
    } catch (_) {
      if (mounted && generation == _generation) {
        setState(() => _error = '无法读取详情，请重试。');
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _open(IndexDetail detail) async {
    final expected = _page!.revision;
    final current = await widget.client.query(
      IndexRequest(
        sessionId: widget.sessionId,
        kind: IndexQueryKind.detail,
        nodeId: detail.entry.id,
        expectedRevision: expected,
      ),
    );
    if (!mounted || current.status != 'ok' || current.revision != expected) {
      throw PlatformException(
        code: 'entry-changed',
        message: '索引已更新，请重新加载详情后再打开。',
      );
    }
    await (widget.opener ?? AndroidEntryOpener()).open(
      widget.sessionId,
      detail.entry.id,
    );
  }

  Future<void> _copyPath(IndexDetail detail) async {
    final root = _page!.session.rootPath.replaceFirst(RegExp(r'/+$'), '');
    final relative = [
      for (var i = 0; i < detail.pathHex.length; i += 2)
        int.parse(detail.pathHex.substring(i, i + 2), radix: 16),
    ];
    final bytes = [
      ...utf8.encode(root),
      if (relative.isNotEmpty || root.isEmpty) 47,
      ...relative,
    ];
    String value;
    var raw = false;
    try {
      value = utf8.decode(bytes);
    } on FormatException {
      raw = true;
      value = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    }
    await Clipboard.setData(ClipboardData(text: value));
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(raw ? '路径包含非 UTF-8 字节，已复制完整路径的十六进制字节。' : '已复制完整路径。'),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final detail = _page?.detail;
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        Row(
          children: [
            const Expanded(
              child: Text(
                '条目详情',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600),
              ),
            ),
            IconButton(
              tooltip: '重新加载详情',
              onPressed: _loading ? null : () => _load(),
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        if (_loading) const LinearProgressIndicator(),
        if (_error != null) Text(_error!),
        if (detail != null) ...[
          SelectableText(
            detail.entry.id == 1
                ? _page!.session.rootPath
                : displayScanPath(detail.entry.nameHex),
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 12),
          SelectableText(
            '${_page!.session.rootPath.replaceFirst(RegExp(r'/+$'), '')}/${detail.pathHex.isEmpty ? '' : displayScanPath(detail.pathHex)}',
          ),
          TextButton.icon(
            key: const Key('copy-indexed-path'),
            onPressed: () => _copyPath(detail),
            icon: const Icon(Icons.copy, size: 18),
            label: const Text('复制完整路径'),
          ),
          if (detail.entry.kind == 'regular' ||
              detail.entry.kind == 'directory') ...[
            Align(
              alignment: Alignment.centerLeft,
              child: ExternalOpenButton(
                key: const Key('open-indexed-entry'),
                onOpen: () => _open(detail),
              ),
            ),
            const Text(
              '打开设备上的当前位置；历史快照中的原文件可能已变化。',
              style: TextStyle(fontSize: 12),
            ),
          ],
          if (detail.entry.kind == 'directory' && widget.onRescan != null) ...[
            TextButton.icon(
              key: const Key('detail-rescan'),
              onPressed: !widget.rescanEnabled || _page!.session.retained
                  ? null
                  : () => widget.onRescan!(detail.entry.id, _page!.revision),
              icon: const Icon(Icons.manage_search),
              label: const Text('重新扫描此文件夹'),
            ),
            if (_page!.session.retained) const Text('已保留快照；取消保留后可重新扫描。'),
          ],
          LocalUpdateSummary(
            update: _page!.session.localUpdate,
            revision: _page!.revision,
          ),
          if (detail.entry.parentId > 0)
            TextButton.icon(
              key: const Key('locate-indexed-parent'),
              onPressed: () => widget.onLocate(detail.entry.parentId),
              icon: const Icon(Icons.drive_file_move_outline, size: 18),
              label: const Text('定位所在目录'),
            ),
          const Divider(),
          _field(
            '逻辑大小',
            detail.metadataValid
                ? '${formatBytes(detail.entry.logicalBytes)} · ${detail.entry.logicalBytes} 字节'
                : '未知',
          ),
          _field(
            '报告占用量',
            detail.metadataValid
                ? '${formatBytes(detail.entry.allocatedBytes)} · ${detail.entry.allocatedBytes} 字节'
                : '未知',
          ),
          const Text('报告占用来自 st_blocks × 512；目录含自身占用，硬链接只计入确定的归属项。'),
          for (final state in entryStates(detail.entry))
            Padding(padding: const EdgeInsets.only(top: 8), child: Text(state)),
          if (_page!.provisional) const Text('暂定统计：扫描或聚合尚未完成。'),
          const Divider(),
          _field('类型', switch (detail.entry.kind) {
            'directory' => '目录',
            'regular' => '普通文件',
            'symlink' => '符号链接',
            'special' => '特殊文件',
            _ => '未知',
          }),
          _field('节点', '#${detail.entry.id} · 父节点 #${detail.entry.parentId}'),
          _field(
            '权限 / UID / GID',
            '${detail.mode.toRadixString(8)} / ${detail.uid} / ${detail.gid}',
          ),
          _field('设备 / inode', '${detail.deviceHex} / ${detail.inodeHex}'),
          _field('挂载 / 链接数', '${detail.mountId} / ${detail.linkCount}'),
          _field(
            '原始大小 / 占用',
            '${detail.rawSize} / ${detail.rawAllocatedBytes} 字节',
          ),
          _field('修改时间', formatTime(detail.mtimeSeconds * 1000)),
          if (detail.symlinkTargetHex.isNotEmpty)
            _field('符号链接目标', displayScanPath(detail.symlinkTargetHex)),
          ExpansionTile(
            tilePadding: EdgeInsets.zero,
            title: const Text('原始名称与路径字节'),
            children: [
              _field('名称 hex', detail.entry.nameHex),
              _field('相对路径 hex', detail.pathHex),
              const Text(
                '显示时转义控制字符；无效 UTF-8 用替代字符显示。复制路径时保留可解码的原始内容；无法解码时复制完整路径十六进制字节。',
              ),
            ],
          ),
          const Divider(),
          Text('此条目的问题', style: Theme.of(context).textTheme.titleSmall),
          const Text('此处仅列出当前条目本身的问题；子目录的问题需进入对应条目查看。'),
          if (_issues!.issues.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 12),
              child: Text('没有记录到本条目的问题。'),
            ),
          for (final issue in _issues!.issues)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: SelectableText(
                '${issue.code} · ${issue.operation} · errno ${issue.systemError}',
              ),
            ),
          Wrap(
            spacing: 8,
            children: [
              if (_issues?.previousCursor != null)
                TextButton(
                  onPressed: () => _load(cursor: _issues!.previousCursor),
                  child: const Text('上一页问题'),
                ),
              if (_issues?.nextCursor != null)
                TextButton(
                  onPressed: () => _load(cursor: _issues!.nextCursor),
                  child: const Text('下一页问题'),
                ),
            ],
          ),
        ],
      ],
    );
  }

  Widget _field(String label, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 7),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: Theme.of(context).textTheme.labelMedium),
        SelectableText(value),
      ],
    ),
  );
}
