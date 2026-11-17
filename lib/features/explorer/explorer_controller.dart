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
  int _generation = 0;
  bool _disposed = false;
  String? _cursor;

  Future<void> refresh() => load(cursor: _cursor, number: pageNumber);

  /// Completed indexes are immutable. Revalidate after backgrounding without
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
        return;
      }
    } catch (_) {
      if (_disposed || generation != _generation) return;
    }
    await refresh();
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
