import 'package:flutter/foundation.dart';

import '../../platform/index_client.dart';

/// Holds one result page and a bounded ancestor chain, never a directory tree.
class ExplorerController extends ChangeNotifier {
  ExplorerController(this.client, this.sessionId);
  final IndexClient client;
  final String sessionId;
  int directoryId = 1;
  bool largeFiles = false;
  bool treemap = false;
  int treemapDepth = 3;
  bool allocated = false;
  IndexSort sort = IndexSort.logical;
  IndexPage? page;
  IndexDetail? directory, root;
  List<IndexAncestor> ancestors = const [];
  bool loading = false;
  bool retryAutomatically = false;
  String? message;
  int pageNumber = 1;
  int contentEpoch = 0;
  int? _knownRevision;
  String _directoryPathHex = '';
  int _pathDirectoryId = 1;
  String? _relocationNotice;
  int _generation = 0;
  bool _disposed = false;
  String? _cursor;

  Future<void> refresh() => load(cursor: _cursor, number: pageNumber);

  /// Revalidate after backgrounding without
  /// discarding selection, scroll position or pagination when nothing changed.
  Future<void> resume() async {
    if (loading || _disposed) return;
    final current = page;
    if (current == null || current.session.active || retryAutomatically) {
      await refresh();
      return;
    }
    final generation = _generation;
    try {
      final metadata = await client.query(request(IndexQueryKind.session));
      if (_disposed || generation != _generation) return;
      if (metadata.status == 'ok' && metadata.revision == current.revision) {
        if (metadata.session.latestRescanTaskId !=
                current.session.latestRescanTaskId ||
            metadata.session.retained != current.session.retained) {
          await refresh();
        }
        return;
      }
    } catch (_) {
      if (_disposed || generation != _generation) return;
    }
    await reloadAfterUpdate();
  }

  Future<bool> _relocate(int revision, int generation) async {
    final path = _pathDirectoryId == directoryId ? _directoryPathHex : '';
    final location = await locateIndexedPath(client, sessionId, path, revision);
    if (_disposed || generation != _generation) return false;
    if (location.status != 'ok' ||
        location.revision != revision ||
        location.detail == null) {
      throw StateError('Index changed while restoring location');
    }
    final entry = location.detail!.entry;
    _relocationNotice = location.pathMatched && entry.kind == 'directory'
        ? '快照内容已更新，已重新加载并返回第一页。'
        : '原目录已消失或类型已变化，已回到最近仍存在的上级目录。';
    directoryId = entry.kind == 'directory' ? entry.id : entry.parentId;
    _knownRevision = revision;
    contentEpoch++;
    page = null;
    directory = null;
    root = null;
    ancestors = const [];
    _cursor = null;
    pageNumber = 1;
    return true;
  }

  Future<void> reloadAfterUpdate() async {
    final generation = ++_generation;
    loading = true;
    notifyListeners();
    try {
      final metadata = await client.query(request(IndexQueryKind.session));
      if (_disposed || generation != _generation) return;
      if (metadata.status == 'ok' &&
          _knownRevision != null &&
          metadata.revision != _knownRevision) {
        if (!await _relocate(metadata.revision, generation)) return;
      }
      await load();
    } catch (_) {
      if (_disposed || generation != _generation) return;
      page = null;
      directory = null;
      root = null;
      ancestors = const [];
      loading = false;
      retryAutomatically = true;
      message = '更新已提交或索引正在变化，暂时无法重新定位，请重新加载。';
      contentEpoch++;
      notifyListeners();
    }
  }

  IndexRequest request(IndexQueryKind kind, {int nodeId = 0, String? cursor}) =>
      IndexRequest(
        sessionId: sessionId,
        kind: kind,
        parentId: directoryId,
        nodeId: nodeId,
        sort: treemap
            ? (allocated ? IndexSort.allocated : IndexSort.logical)
            : sort,
        limit: treemap ? 100 : 50,
        depth: treemapDepth,
        cursor: cursor,
      );

  Future<void> load({String? cursor, int number = 1}) async {
    final generation = ++_generation;
    if (cursor == null) {
      _cursor = null;
      pageNumber = 1;
    }
    loading = true;
    retryAutomatically = false;
    message = null;
    notifyListeners();
    try {
      final responses = await Future.wait([
        client.query(
          request(
            treemap
                ? IndexQueryKind.treemap
                : largeFiles
                ? IndexQueryKind.largeFiles
                : IndexQueryKind.children,
            cursor: cursor,
          ),
        ),
        client.query(request(IndexQueryKind.detail, nodeId: directoryId)),
        client.query(request(IndexQueryKind.detail, nodeId: 1)),
        client.query(request(IndexQueryKind.ancestors, nodeId: directoryId)),
      ]);
      if (_disposed || generation != _generation) return;
      final stableRevision =
          responses.map((p) => p.revision).toSet().length == 1;
      if (stableRevision &&
          responses[0].session.id == sessionId &&
          const [
            'ok',
            'not-found',
            'stale-cursor',
          ].contains(responses[0].status) &&
          !responses[0].session.active &&
          _knownRevision != null &&
          responses[0].revision != _knownRevision) {
        if (!await _relocate(responses[0].revision, generation)) return;
        await load();
        return;
      }
      if (responses.any((p) => p.needsRefresh) ||
          responses.map((p) => p.revision).toSet().length > 1) {
        // A single retry allows an import batch to settle without a busy loop.
        if (cursor != null) {
          await load();
          if (!_disposed && generation + 1 == _generation) {
            message = '扫描数据已更新，已返回第一页。';
            notifyListeners();
          }
          return;
        }
        page = null;
        directory = null;
        root = null;
        ancestors = const [];
        retryAutomatically = true;
        message = '扫描数据正在更新，请稍后刷新。';
      } else if (responses.any((p) => p.status != 'ok')) {
        page = null;
        directory = null;
        root = null;
        ancestors = const [];
        retryAutomatically = responses.any(
          (p) => p.status == 'index-finalizing',
        );
        message = responses.any((p) => p.status == 'not-found')
            ? '该会话或目录已不可用，请返回最近会话重新选择。'
            : responses.any((p) => p.status == 'index-finalizing')
            ? '正在完成索引，请稍后刷新。'
            : '该索引或目录暂不可用，请刷新重试。';
      } else {
        page = responses[0];
        directory = responses[1].detail;
        root = responses[2].detail;
        ancestors = responses[3].ancestors;
        _knownRevision = page!.revision;
        _directoryPathHex = directory?.pathHex ?? '';
        _pathDirectoryId = directoryId;
        message = _relocationNotice;
        _relocationNotice = null;
        pageNumber = number;
        _cursor = cursor;
      }
    } catch (_) {
      if (!_disposed && generation == _generation) {
        page = null;
        directory = null;
        root = null;
        ancestors = const [];
        retryAutomatically = true;
        message = '无法读取索引，请检查服务连接后重试。';
      }
    } finally {
      if (!_disposed && generation == _generation) {
        loading = false;
        notifyListeners();
      }
    }
  }

  Future<void> navigate(int id) async {
    // Remember the destination's raw path before clearing the old page. This
    // also covers a commit racing a directory tap whose old ID is now gone.
    final candidates = [
      ...?page?.entries,
      for (final group in page?.treemap?.groups ?? <TreemapGroup>[])
        ...group.entries,
    ];
    final child = candidates
        .where((e) => e.id == id && e.parentId == directoryId)
        .firstOrNull;
    if (child != null && _pathDirectoryId == directoryId) {
      _directoryPathHex = _directoryPathHex.isEmpty
          ? child.nameHex
          : '${_directoryPathHex}2f${child.nameHex}';
      _pathDirectoryId = id;
    } else {
      final ancestor = ancestors.indexWhere((e) => e.id == id);
      if (ancestor >= 0) {
        _directoryPathHex = ancestors
            .take(ancestor + 1)
            .skip(1)
            .map((e) => e.nameHex)
            .join('2f');
        _pathDirectoryId = id;
      }
    }
    directoryId = id;
    page = null;
    directory = null;
    ancestors = const [];
    _cursor = null;
    await load();
  }

  Future<void> setLargeFiles(bool value) async {
    treemap = false;
    largeFiles = value;
    page = null;
    if (value && sort == IndexSort.name) {
      sort = allocated ? IndexSort.allocated : IndexSort.logical;
    }
    await load();
  }

  Future<void> setTreemap() async {
    treemap = true;
    largeFiles = false;
    page = null;
    await load();
  }

  Future<void> setMetric(bool value) async {
    allocated = value;
    if (sort != IndexSort.name) {
      sort = value ? IndexSort.allocated : IndexSort.logical;
    }
    await load();
  }

  Future<void> setTreemapDepth(int value) async {
    if (value < 0 || value > 6 || value == treemapDepth) return;
    treemapDepth = value;
    page = null;
    await load();
  }

  Future<void> setSort(IndexSort value) async {
    sort = value;
    await load();
  }

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    super.dispose();
  }
}
