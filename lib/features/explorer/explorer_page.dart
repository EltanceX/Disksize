import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../platform/index_client.dart';
import '../../platform/entry_opener.dart';
import '../../platform/scan_client.dart';
import '../../platform/snapshot_rescan_client.dart';
import '../snapshots/local_update_summary.dart';
import 'explorer_controller.dart';
import 'explorer_detail.dart';
import 'explorer_format.dart';
import 'treemap_panel.dart';
import 'snapshot_rescan_controller.dart';
import 'snapshot_rescan_panel.dart';

class ExplorerPage extends StatefulWidget {
  const ExplorerPage({
    super.key,
    required this.client,
    required this.sessionId,
    this.opener,
    this.rescanClient,
  });
  final IndexClient client;
  final String sessionId;
  final EntryOpener? opener;
  final SnapshotRescanClient? rescanClient;
  @override
  State<ExplorerPage> createState() => _ExplorerPageState();
}

class _ExplorerPageState extends State<ExplorerPage>
    with WidgetsBindingObserver {
  late final ExplorerController c;
  late final EntryOpener _opener;
  late final SnapshotRescanController _rescan;
  int? _selected;
  String? _selectedPathHex, _selectionToRestore;
  int _contentEpoch = 0;
  String? _handledCommit;
  bool _confirmingRescan = false;
  ModalRoute<void>? _detailRoute;
  Timer? _poll;
  final _scroll = ScrollController();
  bool _resumed = true;
  bool _backgrounded = false;
  int _navigation = 0;
  double? _preservedScrollOffset;
  double _preservedContentExtent = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _opener = widget.opener ?? AndroidEntryOpener();
    _rescan = SnapshotRescanController(
      widget.rescanClient ?? AndroidSnapshotRescanClient(),
    )..addListener(_rescanChanged);
    c = ExplorerController(widget.client, widget.sessionId)
      ..addListener(_changed);
    unawaited(c.load());
  }

  void _changed() {
    if (!mounted) return;
    if (c.contentEpoch != _contentEpoch) {
      _contentEpoch = c.contentEpoch;
      _selectionToRestore = _selectedPathHex;
      _selected = null;
      _selectedPathHex = null;
      final route = _detailRoute;
      _detailRoute = null;
      if (route != null && route.isActive) {
        Navigator.of(context).removeRoute(route);
      }
    }
    setState(() {
      if (c.treemap && c.loading) _selected = null;
    });
    final taskId = c.page?.session.latestRescanTaskId;
    if (taskId != null && !c.loading && !_rescan.starting) {
      unawaited(_rescan.attach(taskId));
    }
    if (!c.loading && c.page != null && _selectionToRestore != null) {
      final path = _selectionToRestore!;
      _selectionToRestore = null;
      unawaited(_restoreSelection(path, c.page!.revision));
    }
    _schedulePoll();
  }

  void _rescanChanged() {
    if (!mounted) return;
    setState(() {});
    final task = _rescan.task;
    if (task?.committedRevision != null && _handledCommit != task!.id) {
      _handledCommit = task.id;
      if (c.page?.revision != task.committedRevision) {
        unawaited(c.reloadAfterUpdate());
      }
    }
  }

  Future<void> _restoreSelection(String path, int revision) async {
    final epoch = c.contentEpoch;
    final navigation = _navigation;
    final directory = c.directoryId;
    try {
      final result = await locateIndexedPath(
        widget.client,
        widget.sessionId,
        path,
        revision,
      );
      if (!mounted ||
          epoch != c.contentEpoch ||
          navigation != _navigation ||
          directory != c.directoryId ||
          c.page?.revision != revision ||
          result.status != 'ok' ||
          result.revision != revision ||
          !result.pathMatched) {
        return;
      }
      setState(() {
        _selected = result.detail?.entry.id;
        _selectedPathHex = path;
      });
    } catch (_) {
      /* Selection is optional; the directory remains usable. */
    }
  }

  void _select(int? id) {
    setState(() {
      _selected = id;
      _selectedPathHex = null;
    });
    final revision = c.page?.revision;
    if (id == null || revision == null) return;
    unawaited(() async {
      try {
        final result = await widget.client.query(
          IndexRequest(
            sessionId: widget.sessionId,
            kind: IndexQueryKind.detail,
            nodeId: id,
            expectedRevision: revision,
          ),
        );
        if (mounted &&
            _selected == id &&
            c.page?.revision == revision &&
            result.status == 'ok' &&
            result.revision == revision) {
          _selectedPathHex = result.detail?.pathHex;
        }
      } catch (_) {
        /* A stale selection will be cleared on the next revision. */
      }
    }());
  }

  Future<void> _openEntry(int id, int revision) async {
    final result = await widget.client.query(
      IndexRequest(
        sessionId: widget.sessionId,
        kind: IndexQueryKind.detail,
        nodeId: id,
        expectedRevision: revision,
      ),
    );
    if (!mounted ||
        result.status != 'ok' ||
        result.revision != revision ||
        c.page?.revision != revision) {
      if (mounted) unawaited(c.reloadAfterUpdate());
      throw PlatformException(
        code: 'entry-changed',
        message: '索引已更新，请重新选择条目后再打开。',
      );
    }
    await _opener.open(widget.sessionId, id);
  }

  bool get _canRescan =>
      !_rescan.blocked &&
      !_confirmingRescan &&
      !c.loading &&
      c.page?.session.snapshotId != null &&
      !c.page!.provisional &&
      !c.page!.session.retained &&
      !c.page!.session.active;

  Future<void> _startRescan(int id, int revision) async {
    if (!_canRescan) return;
    final snapshotId = c.page!.session.snapshotId!;
    setState(() => _confirmingRescan = true);
    try {
      final target = await widget.client.query(
        IndexRequest(
          sessionId: widget.sessionId,
          kind: IndexQueryKind.detail,
          nodeId: id,
          expectedRevision: revision,
        ),
      );
      if (!mounted) return;
      if (target.status != 'ok' ||
          target.revision != revision ||
          target.detail?.entry.kind != 'directory') {
        throw const SnapshotRescanException('stale-revision');
      }
      final root = target.session.rootPath.replaceFirst(RegExp(r'/+$'), '');
      final path = target.detail!.pathHex;
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          key: const Key('rescan-confirmation'),
          scrollable: true,
          title: const Text('重新扫描此文件夹？'),
          content: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SelectableText(
                '$root/${path.isEmpty ? '' : displayScanPath(path)}',
              ),
              const SizedBox(height: 12),
              Text(
                '使用原${target.session.backend == 'root' ? ' Root' : '普通'}扫描方式，包含全部后代。完整成功后更新当前快照，不新增快照；旧子树会被替换，无法撤销。',
              ),
              const SizedBox(height: 8),
              const Text('扫描只读取文件元数据。提交前失败或取消保留旧结果；其余目录沿用原采集时间。'),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('返回'),
            ),
            FilledButton(
              key: const Key('rescan-confirm'),
              onPressed: () => Navigator.pop(context, true),
              child: const Text('重新扫描并更新'),
            ),
          ],
        ),
      );
      if (!mounted || confirmed != true) return;
      await _rescan.start(snapshotId, id, revision);
      // Also discover a task accepted before a bridge timeout or activity loss.
      if (mounted) unawaited(c.resume());
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              e is SnapshotRescanException
                  ? rescanErrorMessage(e.code)
                  : '无法确认扫描范围，请重新加载后重试。',
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _confirmingRescan = false);
    }
  }

  void _schedulePoll() {
    _poll?.cancel();
    if (_resumed &&
        !c.loading &&
        (c.page?.session.active == true || c.retryAutomatically)) {
      _poll = Timer(const Duration(seconds: 2), () => unawaited(c.refresh()));
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _resumed = state == AppLifecycleState.resumed;
    _rescan.setResumed(_resumed);
    if (state == AppLifecycleState.hidden ||
        state == AppLifecycleState.paused) {
      _backgrounded = true;
    }
    _poll?.cancel();
    if (!_resumed) return;
    if (_backgrounded) {
      _backgrounded = false;
      unawaited(c.resume());
    } else {
      _schedulePoll();
    }
  }

  void _navigate(int id, {bool preserveScroll = false}) {
    final navigation = ++_navigation;
    _preservedScrollOffset = preserveScroll && _scroll.hasClients
        ? _scroll.offset
        : null;
    _preservedContentExtent = _preservedScrollOffset == null
        ? 0
        : _scroll.position.maxScrollExtent + _scroll.position.viewportDimension;
    _selected = null;
    _selectedPathHex = null;
    _selectionToRestore = null;
    unawaited(
      c.navigate(id).then((_) {
        if (!mounted ||
            navigation != _navigation ||
            _preservedScrollOffset == null) {
          return;
        }
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted || navigation != _navigation) return;
          final offset = _preservedScrollOffset!;
          _preservedScrollOffset = null;
          if (_scroll.hasClients && c.directoryId == id) {
            final position = _scroll.position;
            _scroll.jumpTo(
              offset.clamp(position.minScrollExtent, position.maxScrollExtent),
            );
          }
        });
      }),
    );
    if (!preserveScroll && _scroll.hasClients) _scroll.jumpTo(0);
  }

  void _locate(int id) {
    c.largeFiles = false;
    _navigate(id);
  }

  void _detail(int id, bool wide) {
    _select(id);
    if (wide) return;
    unawaited(
      showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        useSafeArea: true,
        builder: (sheetContext) {
          _detailRoute = ModalRoute.of<void>(sheetContext);
          return FractionallySizedBox(
            heightFactor: .92,
            child: Column(
              children: [
                Row(
                  children: [
                    const SizedBox(width: 20),
                    const Expanded(child: Text('条目详情')),
                    IconButton(
                      tooltip: '关闭详情',
                      onPressed: () => Navigator.pop(sheetContext),
                      icon: const Icon(Icons.close),
                    ),
                  ],
                ),
                Expanded(
                  child: ExplorerDetail(
                    opener: _opener,
                    client: widget.client,
                    sessionId: widget.sessionId,
                    nodeId: id,
                    expectedRevision: c.page?.revision,
                    rescanEnabled: _canRescan,
                    onRescan: c.page?.session.snapshotId == null
                        ? null
                        : (node, revision) {
                            Navigator.pop(sheetContext);
                            unawaited(_startRescan(node, revision));
                          },
                    onLocate: (parent) {
                      Navigator.pop(sheetContext);
                      _locate(parent);
                    },
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _poll?.cancel();
    _rescan.removeListener(_rescanChanged);
    _rescan.dispose();
    c.removeListener(_changed);
    c.dispose();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final session = c.page?.session;
    return PopScope(
      canPop: c.directoryId == 1,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) {
          _navigate(c.directory?.entry.parentId ?? 1);
        }
      },
      child: Scaffold(
        appBar: AppBar(
          title: const Text('空间浏览'),
          leading: BackButton(
            onPressed: () {
              if (c.directoryId > 1) {
                _navigate(c.directory?.entry.parentId ?? 1);
              } else {
                Navigator.of(context).pop();
              }
            },
          ),
          actions: [
            IconButton(
              key: const Key('explorer-refresh'),
              tooltip: '重新加载索引',
              onPressed: c.loading ? null : () => c.load(),
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        body: SafeArea(
          child: LayoutBuilder(
            builder: (context, constraints) {
              final wide =
                  constraints.maxWidth >= 1050 &&
                  MediaQuery.textScalerOf(context).scale(14) < 22;
              final list = Column(
                children: [
                  if (c.loading) const LinearProgressIndicator(minHeight: 2),
                  Expanded(
                    child: CustomScrollView(
                      controller: _scroll,
                      slivers: [
                        SliverToBoxAdapter(
                          child: Padding(
                            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                if (session != null) ...[
                                  Text(
                                    '${session.backend == 'root' ? 'Root' : '普通'}模式 · ${sessionState(session.state)}',
                                    style: Theme.of(context)
                                        .textTheme
                                        .labelLarge,
                                  ),
                                  Text(
                                    '${session.nodeCount} 个条目 · ${session.issueCount} 个问题 · ${session.provisional ? '暂定统计，正在扫描或聚合' : '已知范围统计，硬链接已去重'}',
                                  ),
                                  if (session.reason.isNotEmpty)
                                    Text('结束原因：${session.reason}'),
                                  const SizedBox(height: 8),
                                  SelectableText(session.rootPath),
                                  LocalUpdateSummary(
                                    update: session.localUpdate,
                                    revision: c.page?.revision,
                                  ),
                                  if (session.retained)
                                    const Text('此快照已保留，取消保留后可重新扫描。'),
                                ],
                                if (session?.snapshotId != null)
                                  const Text('重新加载只读取已有索引；重新扫描会读取设备目录并更新当前快照。'),
                                SnapshotRescanPanel(controller: _rescan),
                                SingleChildScrollView(
                                  scrollDirection: Axis.horizontal,
                                  child: Row(
                                    children: [
                                      for (final ancestor in c.ancestors) ...[
                                        TextButton(
                                          key: ValueKey('crumb-${ancestor.id}'),
                                          onPressed:
                                              c.loading ||
                                                  ancestor.id == c.directoryId
                                              ? null
                                              : () => _navigate(ancestor.id),
                                          child: ConstrainedBox(
                                            constraints: const BoxConstraints(
                                              maxWidth: 200,
                                            ),
                                            child: Text(
                                              ancestor.id == 1
                                                  ? '扫描根目录'
                                                  : displayScanPath(
                                                      ancestor.nameHex,
                                                    ),
                                              overflow: TextOverflow.ellipsis,
                                            ),
                                          ),
                                        ),
                                        if (ancestor.id != c.directoryId)
                                          const Icon(
                                            Icons.chevron_right,
                                            size: 16,
                                          ),
                                      ],
                                    ],
                                  ),
                                ),
                                if (c.directory != null) ...[
                                  Wrap(
                                    spacing: 24,
                                    runSpacing: 6,
                                    children: [
                                      _stat(
                                        '当前目录 · 逻辑大小',
                                        c.directory!.entry.logicalBytes,
                                      ),
                                      _stat(
                                        '当前目录 · 报告占用',
                                        c.directory!.entry.allocatedBytes,
                                      ),
                                    ],
                                  ),
                                  for (final state in entryStates(
                                    c.directory!.entry,
                                  ))
                                    Text(state),
                                  const SizedBox(height: 8),
                                  if (session?.snapshotId != null)
                                    OutlinedButton.icon(
                                      key: const Key('directory-rescan'),
                                      onPressed: _canRescan
                                          ? () => _startRescan(
                                              c.directoryId,
                                              c.page!.revision,
                                            )
                                          : null,
                                      icon: const Icon(Icons.manage_search),
                                      label: const Text('重新扫描此文件夹'),
                                    ),
                                ],
                                Wrap(
                                  spacing: 10,
                                  runSpacing: 8,
                                  crossAxisAlignment: WrapCrossAlignment.center,
                                  children: [
                                    SegmentedButton<IndexQueryKind>(
                                      segments: const [
                                        ButtonSegment(
                                          value: IndexQueryKind.children,
                                          label: Text('目录'),
                                        ),
                                        ButtonSegment(
                                          value: IndexQueryKind.largeFiles,
                                          label: Text('大文件'),
                                        ),
                                        ButtonSegment(
                                          value: IndexQueryKind.treemap,
                                          label: Text('树图'),
                                        ),
                                      ],
                                      selected: {
                                        c.treemap
                                            ? IndexQueryKind.treemap
                                            : c.largeFiles
                                            ? IndexQueryKind.largeFiles
                                            : IndexQueryKind.children,
                                      },
                                      onSelectionChanged: c.loading
                                          ? null
                                          : (v) {
                                              _selected = null;
                                              _selectedPathHex = null;
                                              unawaited(
                                                v.single ==
                                                        IndexQueryKind.treemap
                                                    ? c.setTreemap()
                                                    : c.setLargeFiles(
                                                        v.single ==
                                                            IndexQueryKind
                                                                .largeFiles,
                                                      ),
                                              );
                                            },
                                    ),
                                    SegmentedButton<bool>(
                                      segments: const [
                                        ButtonSegment(
                                          value: false,
                                          label: Text('逻辑大小'),
                                        ),
                                        ButtonSegment(
                                          value: true,
                                          label: Text('报告占用'),
                                        ),
                                      ],
                                      selected: {c.allocated},
                                      onSelectionChanged: c.loading
                                          ? null
                                          : (v) => unawaited(
                                              c.setMetric(v.single),
                                            ),
                                    ),
                                    if (c.treemap)
                                      DropdownButton<int>(
                                        key: const Key('treemap-depth'),
                                        value: c.treemapDepth,
                                        items: [
                                          for (
                                            var level = 0;
                                            level <= 6;
                                            level++
                                          )
                                            DropdownMenuItem(
                                              value: level,
                                              child: Text('显示到级别 $level'),
                                            ),
                                        ],
                                        onChanged: c.loading
                                            ? null
                                            : (value) {
                                                if (value != null) {
                                                  unawaited(
                                                    c.setTreemapDepth(value),
                                                  );
                                                }
                                              },
                                      ),
                                    if (!c.treemap)
                                      DropdownButton<IndexSort>(
                                        key: const Key('explorer-sort'),
                                        value: c.sort,
                                        items: [
                                          const DropdownMenuItem(
                                            value: IndexSort.logical,
                                            child: Text('逻辑大小 ↓'),
                                          ),
                                          const DropdownMenuItem(
                                            value: IndexSort.allocated,
                                            child: Text('报告占用 ↓'),
                                          ),
                                          if (!c.largeFiles)
                                            const DropdownMenuItem(
                                              value: IndexSort.name,
                                              child: Text('名称 ↑'),
                                            ),
                                        ],
                                        onChanged: c.loading
                                            ? null
                                            : (v) {
                                                if (v != null) {
                                                  unawaited(c.setSort(v));
                                                }
                                              },
                                      ),
                                  ],
                                ),
                                const SizedBox(height: 8),
                                Text(
                                  c.treemap
                                      ? '当前目录的矩形树图 · 面积随容量变化'
                                      : c.largeFiles
                                      ? '当前目录及其子目录中的普通文件 · 容量从大到小'
                                      : '当前目录的直接子项 · 点击文件夹进入',
                                ),
                                Text(
                                  c.treemap
                                      ? '单击色块选中项目，双击目录进入；也可通过下方按钮进入，返回键逐级返回。'
                                      : '容量条表示所选口径占当前目录的比例；未知容量不视为零。',
                                  style: TextStyle(fontSize: 12),
                                ),
                                if (c.message != null)
                                  Padding(
                                    padding: const EdgeInsets.symmetric(
                                      vertical: 12,
                                    ),
                                    child: Text(
                                      c.message!,
                                      key: const Key('explorer-message'),
                                    ),
                                  ),
                              ],
                            ),
                          ),
                        ),
                        if (c.loading && _preservedScrollOffset != null)
                          SliverLayoutBuilder(
                            builder: (context, constraints) =>
                                SliverToBoxAdapter(
                                  // Keep the old extent while data is absent so
                                  // loading cannot clamp the scroll offset to zero.
                                  child: SizedBox(
                                    height:
                                        (_preservedContentExtent -
                                                constraints
                                                    .precedingScrollExtent)
                                            .clamp(0.0, double.infinity),
                                    child: const Center(child: Text('正在读取目录…')),
                                  ),
                                ),
                          ),
                        if (c.treemap &&
                            c.page != null &&
                            c.directory != null &&
                            !c.loading)
                          SliverToBoxAdapter(
                            child: TreemapPanel(
                              key: ValueKey('treemap-${c.directoryId}'),
                              page: c.page!,
                              directory: c.directory!,
                              allocated: c.allocated,
                              depth: c.treemapDepth,
                              selectedId: _selected,
                              onSelect: (entry) => _select(entry?.id),
                              onOpen: (entry) => _navigate(entry.id),
                              onDoubleTapOpen: (entry) =>
                                  _navigate(entry.id, preserveScroll: true),
                              onDetail: (entry) => _detail(entry.id, wide),
                              onOpenExternal: (entry) =>
                                  _openEntry(entry.id, c.page!.revision),
                              onRescan: _canRescan
                                  ? (entry) => unawaited(
                                      _startRescan(entry.id, c.page!.revision),
                                    )
                                  : null,
                              onShowList: (id) {
                                _selected = null;
                                _selectedPathHex = null;
                                c.directoryId = id;
                                unawaited(c.setLargeFiles(false));
                              },
                            ),
                          ),
                        if (!c.treemap &&
                            c.page != null &&
                            c.page!.entries.isEmpty &&
                            !c.loading)
                          SliverToBoxAdapter(
                            child: Padding(
                              padding: const EdgeInsets.all(24),
                              child: Text(
                                c.largeFiles
                                    ? '此范围没有已索引的普通文件。'
                                    : '此目录没有已索引的子项。',
                              ),
                            ),
                          ),
                        if (!c.treemap)
                          SliverList.builder(
                            itemCount: c.page?.entries.length ?? 0,
                            itemBuilder: (context, i) {
                              final entry = c.page!.entries[i];
                              final total = c.allocated
                                  ? c.directory?.entry.allocatedBytes
                                  : c.directory?.entry.logicalBytes;
                              final amount = c.allocated
                                  ? entry.allocatedBytes
                                  : entry.logicalBytes;
                              final fraction =
                                  total != null &&
                                      total > 0 &&
                                      !c.page!.provisional &&
                                      entry.metadataValid
                                  ? amount / total
                                  : null;
                              return _EntryRow(
                                entry: entry,
                                allocated: c.allocated,
                                fraction: fraction,
                                index: i,
                                selected: entry.id == _selected,
                                onTap: () => entry.kind == 'directory'
                                    ? _navigate(entry.id, preserveScroll: true)
                                    : _detail(entry.id, wide),
                                onDetail: () => _detail(entry.id, wide),
                                onRescan:
                                    _canRescan && entry.kind == 'directory'
                                    ? () => unawaited(
                                        _startRescan(
                                          entry.id,
                                          c.page!.revision,
                                        ),
                                      )
                                    : null,
                              );
                            },
                          ),
                        if (session != null && c.root != null)
                          SliverToBoxAdapter(
                            child: _VolumeComparison(
                              session: session,
                              root: c.root!,
                            ),
                          ),
                      ],
                    ),
                  ),
                  if (!c.treemap) ...[const Divider(height: 1), _pagination()],
                ],
              );
              if (!wide) return list;
              return Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Expanded(child: list),
                  const VerticalDivider(width: 1),
                  SizedBox(
                    width: 360,
                    child: c.page == null || c.loading
                        ? const Center(child: Text('重新加载后显示详情'))
                        : ExplorerDetail(
                            opener: _opener,
                            key: ValueKey(
                              '${_selected ?? c.directoryId}-${c.page?.revision}',
                            ),
                            client: widget.client,
                            sessionId: widget.sessionId,
                            nodeId: _selected ?? c.directoryId,
                            expectedRevision: c.page?.revision,
                            rescanEnabled: _canRescan,
                            onRescan: c.page?.session.snapshotId == null
                                ? null
                                : (node, revision) =>
                                      unawaited(_startRescan(node, revision)),
                            onLocate: _locate,
                          ),
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }

  Future<void> _loadPage(String cursor, int number) async {
    _selected = null;
    _selectedPathHex = null;
    await c.load(cursor: cursor, number: number);
    if (mounted && _scroll.hasClients) _scroll.jumpTo(0);
  }

  Widget _pagination() => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
    child: Wrap(
      spacing: 12,
      runSpacing: 4,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        OutlinedButton.icon(
          key: const Key('previous-page'),
          onPressed: c.loading || c.page?.previousCursor == null
              ? null
              : () => _loadPage(c.page!.previousCursor!, c.pageNumber - 1),
          icon: const Icon(Icons.chevron_left),
          label: const Text('上一页'),
        ),
        Text('第 ${c.pageNumber} 页 · 每页最多 50 项'),
        OutlinedButton.icon(
          key: const Key('next-page'),
          onPressed: c.loading || c.page?.nextCursor == null
              ? null
              : () => _loadPage(c.page!.nextCursor!, c.pageNumber + 1),
          icon: const Icon(Icons.chevron_right),
          label: const Text('下一页'),
        ),
      ],
    ),
  );

  Widget _stat(String label, int value) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(label, style: Theme.of(context).textTheme.labelMedium),
      Text(formatBytes(value), style: Theme.of(context).textTheme.titleLarge),
    ],
  );
}

class _EntryRow extends StatelessWidget {
  const _EntryRow({
    required this.entry,
    required this.allocated,
    required this.fraction,
    required this.index,
    required this.selected,
    required this.onTap,
    required this.onDetail,
    this.onRescan,
  });
  final IndexedEntry entry;
  final bool allocated, selected;
  final double? fraction;
  final int index;
  final VoidCallback onTap, onDetail;
  final VoidCallback? onRescan;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final compact =
          constraints.maxWidth < 650 ||
          MediaQuery.textScalerOf(context).scale(14) > 20;
      final states = entryStates(entry);
      final name = Text(
        displayScanPath(entry.nameHex),
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontWeight: FontWeight.w600),
      );
      final amount = entry.metadataValid
          ? formatBytes(allocated ? entry.allocatedBytes : entry.logicalBytes)
          : '未知';
      return Material(
        color: selected
            ? const Color(0xFFFFEDB5)
            : index.isEven
            ? Theme.of(context).colorScheme.surfaceContainerLow
            : Theme.of(context).colorScheme.surface,
        child: InkWell(
          key: ValueKey('entry-${entry.id}'),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 10, 4, 10),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.only(top: 3, right: 12),
                  child: Icon(
                    entry.kind == 'directory'
                        ? Icons.folder
                        : entry.kind == 'symlink'
                        ? Icons.link
                        : Icons.insert_drive_file_outlined,
                    color: entry.kind == 'directory'
                        ? const Color(0xFFD49B19)
                        : Theme.of(context).colorScheme.primary,
                  ),
                ),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (compact)
                        name
                      else
                        Row(
                          children: [
                            Expanded(child: name),
                            const SizedBox(width: 12),
                            Text(amount),
                          ],
                        ),
                      if (compact) Text(amount),
                      Text(
                        !entry.metadataValid
                            ? '容量未知'
                            : allocated
                            ? '逻辑 ${formatBytes(entry.logicalBytes)}'
                            : '占用 ${formatBytes(entry.allocatedBytes)}',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      if (states.isNotEmpty)
                        Text(
                          states.join(' · '),
                          style: TextStyle(
                            fontSize: 12,
                            color: Theme.of(context).colorScheme.error,
                          ),
                        ),
                      const SizedBox(height: 5),
                      if (fraction != null)
                        Row(
                          children: [
                            Expanded(
                              child: LinearProgressIndicator(
                                value: fraction!.clamp(0, 1),
                                minHeight: 5,
                                color: const Color(0xFFD9AA32),
                                backgroundColor: Theme.of(context)
                                    .colorScheme
                                    .surfaceContainerHighest,
                              ),
                            ),
                            const SizedBox(width: 8),
                            Text(
                              '${(fraction! * 100).toStringAsFixed(1)}%',
                              style: Theme.of(context).textTheme.labelSmall,
                            ),
                          ],
                        )
                      else
                        Text(
                          '占比待定',
                          style: Theme.of(context).textTheme.labelSmall,
                        ),
                    ],
                  ),
                ),
                IconButton(
                  key: ValueKey('detail-${entry.id}'),
                  tooltip: '查看详情',
                  onPressed: onDetail,
                  icon: const Icon(Icons.info_outline, size: 20),
                ),
                if (onRescan != null)
                  PopupMenuButton<String>(
                    key: ValueKey('entry-menu-${entry.id}'),
                    tooltip: '目录操作',
                    onSelected: (_) => onRescan!(),
                    itemBuilder: (_) => const [
                      PopupMenuItem(value: 'rescan', child: Text('重新扫描此文件夹')),
                    ],
                  ),
                if (entry.kind == 'directory')
                  const Padding(
                    padding: EdgeInsets.only(top: 12, right: 4),
                    child: Icon(Icons.chevron_right, size: 18),
                  ),
              ],
            ),
          ),
        ),
      );
    },
  );
}

class _VolumeComparison extends StatelessWidget {
  const _VolumeComparison({required this.session, required this.root});
  final IndexSession session;
  final IndexDetail root;
  @override
  Widget build(BuildContext context) {
    final volume = session.volume;
    return Card.outlined(
      margin: const EdgeInsets.all(16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('卷与扫描范围', style: Theme.of(context).textTheme.titleMedium),
            Text('扫描开始 ${formatTime(session.startedAtMillis)}'),
            Text('扫描结束 ${formatTime(session.endedAtMillis)}'),
            Text('扫描根目录的已知报告占用：${formatBytes(root.entry.allocatedBytes)}'),
            if (volume.valid) ...[
              const SizedBox(height: 8),
              Text(
                '所在卷总量 ${formatBytes(volume.totalBytes)} · 已用 ${formatBytes(volume.usedBytes)}',
              ),
              Text(
                '空闲 ${formatBytes(volume.freeBytes)} · 当前身份可用 ${formatBytes(volume.availableBytes)}',
              ),
              Text('卷采样时间 ${formatTime(volume.capturedAtMillis)} · 扫描进程挂载视图'),
              Text(
                session.provisional
                    ? '扫描尚未完成，差额暂不可用。'
                    : '卷已用 − 扫描根目录报告占用：${formatBytes(volume.usedBytes - root.entry.allocatedBytes)}',
              ),
            ] else
              const Text('本次会话未记录卷统计，无法计算差额。'),
            const SizedBox(height: 8),
            const Text(
              '差额涵盖扫描范围外的数据、不可访问或跳过的项、文件系统元数据，以及扫描期间的变化。子目录和共享存储入口不代表整卷；差额不能直接作为可清理空间。',
            ),
          ],
        ),
      ),
    );
  }
}
