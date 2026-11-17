import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../platform/diagnostics_client.dart';
import '../../../platform/generated/scanner_api.g.dart';
import '../../../platform/scan_client.dart';
import '../../../platform/index_client.dart';
import '../../../platform/snapshot_client.dart';
import '../../snapshots/snapshots_page.dart';
import '../../explorer/explorer_page.dart';
import '../../explorer/explorer_format.dart';
import 'scan_home_page.dart';

class ScanDashboardPage extends StatefulWidget {
  const ScanDashboardPage({
    super.key,
    required this.client,
    required this.diagnosticsClient,
    this.indexClient,
  });
  final ScanClient client;
  final DiagnosticsClient diagnosticsClient;
  final IndexClient? indexClient;

  @override
  State<ScanDashboardPage> createState() => _ScanDashboardPageState();
}

class _ScanDashboardPageState extends State<ScanDashboardPage>
    with WidgetsBindingObserver {
  final _path = TextEditingController();
  ScanAccessStatus? _access;
  ScanSnapshot? _snapshot;
  RootAccessStatus? _root;
  bool _rootMode = false;
  String _source = 'own';
  String? _error;
  String? _pendingId;
  bool _busy = false;
  bool _refreshing = false;
  bool _resumed = true;
  bool _refreshAfterResume = false;
  Timer? _poll;
  late final IndexClient _index;
  List<IndexSession> _recent = const [];
  List<StorageEntry> _volumes = const [];
  bool _libraryBusy = false;
  bool _allRecent = false;
  String? _libraryError;
  DateTime? _libraryRead;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _index = widget.indexClient ?? AndroidIndexClient();
    unawaited(_refresh());
    unawaited(_refreshLibrary());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _resumed = state == AppLifecycleState.resumed;
    if (state == AppLifecycleState.hidden ||
        state == AppLifecycleState.paused) {
      _refreshAfterResume = true;
    }
    _poll?.cancel();
    if (!_resumed) return;
    if (_refreshAfterResume) {
      _refreshAfterResume = false;
      unawaited(_refresh());
      unawaited(_refreshLibrary());
    } else {
      _schedulePoll();
    }
  }

  Future<void> _refreshLibrary() async {
    if (_libraryBusy) return;
    _libraryBusy = true;
    _libraryRead = DateTime.now();
    try {
      final values = await Future.wait<Object>([
        _index.recent(),
        _index.volumes(),
      ]);
      if (mounted) {
        setState(() {
          _recent = values[0] as List<IndexSession>;
          _volumes = values[1] as List<StorageEntry>;
          _libraryError = null;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _libraryError = '暂时无法读取卷和最近会话，点击刷新重试。');
    } finally {
      _libraryBusy = false;
    }
  }

  Future<void> _openIndex(String id) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ExplorerPage(client: _index, sessionId: id),
      ),
    );
    if (mounted) {
      unawaited(_refresh());
      unawaited(_refreshLibrary());
    }
  }

  Future<void> _refresh() async {
    if (_refreshing || !mounted) return;
    if (!_resumed) {
      _refreshAfterResume = true;
      return;
    }
    _refreshing = true;
    try {
      final values = await Future.wait<Object?>([
        widget.client.accessStatus(),
        widget.client.snapshot(),
        widget.client.rootStatus(),
      ]);
      if (!mounted) return;
      final access = values[0]! as ScanAccessStatus;
      final snapshot = values[1] as ScanSnapshot?;
      setState(() {
        if (_access == null) _path.text = access.ownPath;
        _access = access;
        _snapshot = snapshot;
        _root = values[2]! as RootAccessStatus;
        if (_rootMode && _root!.state != 'ready') {
          _rootMode = false;
          _source = 'own';
          _path.text = access.ownPath;
        }
        if (snapshot?.sessionId == _pendingId) _pendingId = null;
        _error = null;
      });
      if (_libraryRead == null ||
          DateTime.now().difference(_libraryRead!).inSeconds >= 3) {
        unawaited(_refreshLibrary());
      }
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
    } finally {
      _refreshing = false;
      _schedulePoll();
    }
  }

  void _schedulePoll() {
    _poll?.cancel();
    if (mounted &&
        _resumed &&
        (_snapshot?.active == true ||
            _pendingId != null ||
            _error != null ||
            _root?.state == 'requesting')) {
      _poll = Timer(
        Duration(milliseconds: _error == null ? 400 : 1200),
        () => unawaited(_refresh()),
      );
    }
  }

  Future<void> _perform(Future<void> Function() operation) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await operation();
      await _refresh();
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
    } finally {
      if (mounted) setState(() => _busy = false);
      _schedulePoll();
    }
  }

  String _message(Object error) => error is PlatformException
      ? error.message ?? '扫描服务连接中断，正在重试'
      : '暂时无法读取扫描状态，请刷新重试';

  void _selectSource(String? value) {
    if (value == null || _access == null) return;
    setState(() {
      _source = value;
      _path.text = switch (value) {
        'own' => _access!.ownPath,
        'external-own' => _access!.externalOwnPath!,
        'shared' => _access!.sharedPath,
        _ => _path.text,
      };
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _poll?.cancel();
    _path.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final access = _access;
    final snapshot = _snapshot;
    final active = snapshot?.active == true || _pendingId != null;
    final text = Theme.of(context).textTheme;
    return Scaffold(
      appBar: AppBar(
        title: const Text('DiskSize'),
        actions: [
          IconButton(
            key: const Key('open-snapshots'),
            tooltip: '快照与增长对比',
            onPressed: () async {
              await Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => SnapshotsPage(
                    client: AndroidSnapshotClient(),
                    indexClient: _index,
                  ),
                ),
              );
              if (mounted) unawaited(_refreshLibrary());
            },
            icon: const Icon(Icons.compare_arrows),
          ),
          IconButton(
            key: const Key('open-diagnostics'),
            tooltip: '运行环境',
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute<void>(
                builder: (_) =>
                    ScanHomePage(diagnosticsClient: widget.diagnosticsClient),
              ),
            ),
            icon: const Icon(Icons.info_outline),
          ),
          IconButton(
            tooltip: '刷新',
            onPressed: () {
              unawaited(_refresh());
              unawaited(_refreshLibrary());
            },
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 760),
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
            children: [
              Text('扫描设备空间', style: text.headlineMedium),
              const SizedBox(height: 8),
              const Text('选择可访问的目录，只读取文件元数据。'),
              const SizedBox(height: 16),
              if (_volumes.isNotEmpty) ...[
                Text('存储卷入口', style: text.titleMedium),
                for (final volume in _volumes)
                  Card.outlined(
                    child: ListTile(
                      leading: Icon(
                        volume.removable
                            ? Icons.sd_storage_outlined
                            : Icons.storage,
                      ),
                      title: Text(volume.label),
                      subtitle: Text(
                        '${volume.path.isEmpty ? '系统未提供可扫描路径' : volume.path}\n${volume.stats.valid ? '可用 ${formatBytes(volume.stats.availableBytes)} / ${formatBytes(volume.stats.totalBytes)} · ${formatTime(volume.stats.capturedAtMillis)}' : '统计不可用 · ${volume.state}'}',
                      ),
                      isThreeLine: true,
                      onTap:
                          active || _busy || !volume.mounted || access == null
                          ? null
                          : () => setState(() {
                              _source = _rootMode
                                  ? 'root'
                                  : volume.path == access.sharedPath
                                  ? 'shared'
                                  : 'path';
                              _path.text = volume.path;
                            }),
                    ),
                  ),
                const Text('入口来自普通应用的挂载视图；root 挂载入口见下方扫描设置。别名可能指向同一数据，不应相加。'),
                const SizedBox(height: 12),
              ],
              if (_recent.isNotEmpty) ...[
                Text('最近会话', style: text.titleMedium),
                for (final session in (_allRecent ? _recent : _recent.take(3)))
                  Card.filled(
                    child: ListTile(
                      key: ValueKey('recent-${session.id}'),
                      leading: const Icon(Icons.history),
                      title: Text(
                        session.rootPath,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                      subtitle: Text(
                        '${session.backend == 'root' ? 'Root' : '普通'} · ${sessionState(session.state)} · ${session.nodeCount} 项\n${formatTime(session.startedAtMillis)}',
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => _openIndex(session.id),
                    ),
                  ),
                if (_recent.length > 3)
                  TextButton(
                    onPressed: () => setState(() => _allRecent = !_allRecent),
                    child: Text(
                      _allRecent ? '收起会话' : '查看最近 ${_recent.length} 次会话',
                    ),
                  ),
                const SizedBox(height: 12),
              ],
              if (_error != null)
                Card.filled(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(_error!, key: const Key('scan-error')),
                  ),
                ),
              if (access == null)
                const LinearProgressIndicator()
              else ...[
                Card.outlined(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _rootMode ? 'Root 模式' : '普通模式',
                          style: text.titleMedium,
                        ),
                        SwitchListTile(
                          key: const Key('root-mode'),
                          contentPadding: EdgeInsets.zero,
                          title: const Text('使用 root 后端'),
                          value: _rootMode,
                          onChanged: active || _busy || _root?.state != 'ready'
                              ? null
                              : (value) {
                                  setState(() {
                                    _rootMode = value;
                                    _source = value ? 'root' : 'own';
                                    _path.text = access.ownPath;
                                  });
                                },
                        ),
                        if (_root?.state != 'ready') ...[
                          Text(
                            _root?.state == 'requesting'
                                ? '等待 root 授权与连接…'
                                : _root?.state == 'unavailable'
                                ? 'Root 不可用，可继续使用普通模式。'
                                : 'Root 访问尚未请求。',
                          ),
                          TextButton(
                            key: const Key('request-root'),
                            onPressed:
                                active || _busy || _root?.state == 'requesting'
                                ? null
                                : () =>
                                      _perform(widget.client.requestRootAccess),
                            child: const Text('请求 root 访问'),
                          ),
                          if (_root?.state == 'unavailable')
                            Text(
                              _root?.reason == 'root-denied'
                                  ? '请在 root 管理器中允许 DiskSize，再点击重试。'
                                  : 'Root 服务连接失败或超时，请检查 root 管理器后重试。',
                            ),
                        ],
                        if (_rootMode) ...[
                          Text('扫描进程 UID ${_root!.uid} · PID ${_root!.pid}'),
                          Text(
                            '挂载视图 ${_root!.mountNamespace} · 用户 ${_root!.userId}',
                          ),
                          Text('发现用户目录：${_root!.users.join(', ')}；默认沿用当前用户入口。'),
                          Text(
                            '当前用户解锁状态：${_root!.userUnlocked == null
                                ? '未知'
                                : _root!.userUnlocked!
                                ? '已解锁'
                                : '未解锁'}',
                          ),
                          const Text(
                            '共享存储的两种入口可能指向同一数据。每次只扫描所选挂载；选择 /data 整卷会包含可访问的其他用户目录。',
                          ),
                        ],
                        const SizedBox(height: 12),
                        if (_rootMode)
                          DropdownButtonFormField<String>(
                            key: const Key('root-entry'),
                            decoration: const InputDecoration(
                              labelText: 'Root 挂载入口（可选）',
                              border: OutlineInputBorder(),
                            ),
                            isExpanded: true,
                            items: [
                              for (final root in _root!.roots)
                                DropdownMenuItem(
                                  value: root.requestedPath,
                                  enabled: root.readable,
                                  child: Text(
                                    '${root.requestedPath} · ${root.filesystem} #${root.mountId}${root.readable ? '' : '（不可读）'}',
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ),
                            ],
                            onChanged: active || _busy
                                ? null
                                : (value) {
                                    if (value != null) {
                                      setState(() => _path.text = value);
                                    }
                                  },
                          )
                        else
                          DropdownButtonFormField<String>(
                            key: ValueKey('source-$_source'),
                            initialValue: _source,
                            isExpanded: true,
                            decoration: const InputDecoration(
                              labelText: '扫描入口',
                              border: OutlineInputBorder(),
                            ),
                            items: [
                              const DropdownMenuItem(
                                value: 'own',
                                child: Text('应用自身目录'),
                              ),
                              if (access.externalOwnPath != null)
                                const DropdownMenuItem(
                                  value: 'external-own',
                                  child: Text('应用外部目录'),
                                ),
                              const DropdownMenuItem(
                                value: 'shared',
                                child: Text('共享存储'),
                              ),
                              const DropdownMenuItem(
                                value: 'path',
                                child: Text('自定义真实路径'),
                              ),
                            ],
                            onChanged: active || _busy ? null : _selectSource,
                          ),
                        const SizedBox(height: 12),
                        TextField(
                          key: const Key('scan-root'),
                          controller: _path,
                          readOnly:
                              (!_rootMode && _source != 'path') ||
                              active ||
                              _busy,
                          decoration: const InputDecoration(
                            labelText: '目录路径',
                            border: OutlineInputBorder(),
                          ),
                        ),
                        const SizedBox(height: 12),
                        Text(
                          _rootMode
                              ? '只读取元数据；root 访问仍受 SELinux 和加密状态限制。'
                              : '自身目录无需存储授权。其他目录的访问范围由 Android 权限决定。',
                        ),
                        const SizedBox(height: 12),
                        FilledButton.icon(
                          key: const Key('start-scan'),
                          onPressed:
                              active ||
                                  _busy ||
                                  (_source == 'shared' &&
                                      !access.sharedAccessGranted)
                              ? null
                              : () => _perform(() async {
                                  _pendingId = await widget.client.start(
                                    _path.text,
                                    _source,
                                  );
                                }),
                          icon: const Icon(Icons.search),
                          label: const Text('开始扫描'),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                Card.outlined(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '共享存储权限：${access.sharedAccessGranted ? '已授权' : '未授权'}',
                        ),
                        Text(
                          access.apiLevel == 29
                              ? 'Android 10：读取权限与 legacy 存储模式${access.legacyStorage ? '已启用' : '未启用'}'
                              : '所有文件访问仍受其他应用私有目录等系统限制。',
                        ),
                        if (!access.sharedAccessGranted)
                          TextButton(
                            key: const Key('storage-permission'),
                            onPressed: _busy
                                ? null
                                : () => _perform(
                                    widget.client.requestStorageAccess,
                                  ),
                            child: Text(
                              access.apiLevel == 29 ? '请求读取权限' : '前往授予所有文件访问',
                            ),
                          ),
                        const Divider(),
                        Text(
                          '通知权限：${access.notificationGranted ? '已允许' : '未允许'}',
                        ),
                        if (!access.notificationGranted) ...[
                          const Text('扫描仍可运行；允许通知后可在通知栏查看和取消。'),
                          TextButton(
                            key: const Key('notification-permission'),
                            onPressed: _busy
                                ? null
                                : () => _perform(
                                    widget.client.requestNotificationAccess,
                                  ),
                            child: const Text('允许扫描通知'),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ],
              if (snapshot != null) ...[
                const SizedBox(height: 16),
                Card.filled(
                  child: Padding(
                    padding: const EdgeInsets.all(20),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _stateTitle(snapshot.state),
                          style: text.titleLarge,
                          key: const Key('scan-state'),
                        ),
                        const SizedBox(height: 8),
                        SelectableText(snapshot.rootPath),
                        Text(
                          '${snapshot.backend == 'root' ? 'Root' : '普通'}扫描 · UID ${snapshot.scannerUid ?? snapshot.serviceUid} · PID ${snapshot.scannerPid ?? snapshot.servicePid}',
                        ),
                        if (snapshot.active) ...[
                          const SizedBox(height: 12),
                          const LinearProgressIndicator(),
                          TextButton.icon(
                            key: const Key('cancel-scan'),
                            onPressed: _busy || snapshot.state == 'cancelling'
                                ? null
                                : () => _perform(
                                    () => widget.client.cancel(
                                      snapshot.sessionId,
                                    ),
                                  ),
                            icon: const Icon(Icons.stop),
                            label: const Text('取消扫描'),
                          ),
                        ],
                        const SizedBox(height: 12),
                        Text(
                          '已发现 ${snapshot.nodeCount} 项 · 问题 ${snapshot.issueCount} 项',
                        ),
                        TextButton.icon(
                          key: const Key('browse-current-index'),
                          onPressed: () => _openIndex(snapshot.sessionId),
                          icon: const Icon(Icons.folder_open),
                          label: const Text('浏览目录与大文件'),
                        ),
                        Text(
                          '${snapshot.provisional ? '暂定逻辑大小（未去重）' : '逻辑大小（已知范围）'}：${snapshot.logicalBytes} 字节',
                        ),
                        if (!snapshot.provisional)
                          Text('报告占用量（已知范围）：${snapshot.allocatedBytes} 字节'),
                        if (!snapshot.complete)
                          const Text('结果未覆盖全部范围；未读取的目录容量仍未知。'),
                        if (snapshot.reason.isNotEmpty)
                          Text(_reason(snapshot.reason)),
                        if (snapshot.endedAtMillis > 0)
                          Text(
                            '结束于 ${DateTime.fromMillisecondsSinceEpoch(snapshot.endedAtMillis).toLocal()}',
                          ),
                      ],
                    ),
                  ),
                ),
                if (snapshot.issues.isNotEmpty)
                  Card.outlined(
                    child: ExpansionTile(
                      initiallyExpanded: true,
                      title: const Text('问题范围'),
                      children: [
                        for (final issue in snapshot.issues)
                          ListTile(
                            leading: const Icon(Icons.warning_amber),
                            title: SelectableText(
                              displayScanPath(issue.pathHex),
                            ),
                            subtitle: Text(
                              '${issue.code} · ${issue.operation} · errno ${issue.systemError}',
                            ),
                          ),
                      ],
                    ),
                  ),
                if (snapshot.entries.isNotEmpty)
                  Card.outlined(
                    child: ExpansionTile(
                      title: Text('已知结果预览（${snapshot.entries.length} 项）'),
                      children: [
                        for (final entry in snapshot.entries)
                          ListTile(
                            leading: Icon(
                              entry.kind == 'directory'
                                  ? Icons.folder_outlined
                                  : Icons.insert_drive_file_outlined,
                            ),
                            title: SelectableText(
                              displayScanPath(entry.pathHex),
                            ),
                            subtitle: Text(
                              '${entry.logicalBytes} 字节逻辑大小 · ${entry.allocatedBytes} 字节报告占用'
                              '${entry.complete ? '' : ' · 未完成/未知范围'}${entry.skipped == 'none' ? '' : ' · ${entry.skipped}'}',
                            ),
                          ),
                      ],
                    ),
                  ),
                if (snapshot.previewTruncated)
                  const Padding(
                    padding: EdgeInsets.all(8),
                    child: Text('这里只展示有限预览；点击“浏览目录与大文件”查看完整分页索引。'),
                  ),
              ],
              if (_libraryError != null)
                TextButton(
                  onPressed: _refreshLibrary,
                  child: Text(_libraryError!),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

String _stateTitle(String state) => switch (state) {
  'queued' => '等待扫描',
  'running' => '正在扫描',
  'cancelling' => '正在停止',
  'completed' => '扫描完成',
  'partial' => '部分完成',
  'cancelled' => '已取消',
  'interrupted' => '扫描已中断',
  'failed' => '扫描失败',
  _ => '等待状态更新',
};

String _reason(String reason) => switch (reason) {
  'process-interrupted' => '上次工作进程已停止，记录已恢复；请重新开始扫描。',
  'foreground-timeout' => '扫描达到系统前台服务时限，已请求停止。',
  'permission-revoked' => '扫描所需的存储权限被撤销。',
  'foreground-start-denied' ||
  'foreground-start-not-received' => '前台服务未能启动，请在应用前台重试。',
  'journal-write-failed' || 'journal-unreadable' => '会话记录无法读写，请检查应用可用空间。',
  'service-destroyed' => '工作服务已停止，扫描未完成。',
  'root-process-died' => 'Root 工作进程已停止，请重新请求 root 访问。',
  'root-pipe-or-protocol-failure' => 'Root 数据传输中断或校验失败，本次扫描未完成。',
  'root-unavailable' => 'Root 连接已失效，请重新请求 root 访问。',
  _ => '扫描未能正常结束，请重试。',
};
