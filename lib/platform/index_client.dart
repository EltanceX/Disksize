import 'dart:convert';

import 'generated/scanner_api.g.dart';
import 'snapshot_client.dart';

enum IndexQueryKind {
  session,
  children,
  largeFiles,
  detail,
  issues,
  ancestors,
  treemap,
  locate,
}

enum IndexSort { logical, allocated, name }

final class IndexRequest {
  const IndexRequest({
    required this.sessionId,
    required this.kind,
    this.parentId = 0,
    this.nodeId = 0,
    this.sort = IndexSort.logical,
    this.limit = 100,
    this.depth = 3,
    this.cursor,
    this.expectedRevision,
    this.pathHex,
  });
  final String sessionId;
  final IndexQueryKind kind;
  final int parentId, nodeId, limit, depth;
  final IndexSort sort;
  final String? cursor;
  final int? expectedRevision;
  final String? pathHex;

  Map<String, Object?> toJson() => {
    'sessionId': sessionId,
    'kind': kind == IndexQueryKind.largeFiles ? 'large-files' : kind.name,
    'parentId': parentId,
    'nodeId': nodeId,
    'sort': sort.name,
    'limit': limit,
    if (kind == IndexQueryKind.treemap) 'depth': depth,
    'cursor': cursor ?? '',
    if (expectedRevision != null) 'expectedRevision': expectedRevision,
    if (pathHex != null) 'pathHex': pathHex,
  };
}

final class IndexedEntry {
  IndexedEntry.fromJson(Map<String, dynamic> value)
    : id = value['id'] as int,
      parentId = value['parentId'] as int,
      nameHex = value['nameHex'] as String,
      kind = value['kind'] as String,
      logicalBytes = value['logicalBytes'] as int,
      allocatedBytes = value['allocatedBytes'] as int,
      complete = value['complete'] as bool,
      ownerId = value['ownerId'] as int,
      metadataValid = value['metadataValid'] as bool? ?? true,
      inaccessible = value['inaccessible'] as bool? ?? false,
      skipped = value['skipped'] as String? ?? 'none';
  final int id, parentId, logicalBytes, allocatedBytes, ownerId;
  final String nameHex, kind, skipped;
  final bool complete, metadataValid, inaccessible;
  bool get sharedLink => ownerId != 0 && ownerId != id;
}

final class IndexedIssue {
  IndexedIssue.fromJson(Map<String, dynamic> value)
    : id = value['id'] as int,
      nodeId = value['nodeId'] as int,
      code = value['code'] as String,
      operation = value['operation'] as String,
      systemError = value['systemError'] as int;
  final int id, nodeId, systemError;
  final String code, operation;
}

final class IndexDetail {
  IndexDetail.fromJson(Map<String, dynamic> value)
    : entry = IndexedEntry.fromJson(value),
      pathHex = value['pathHex'] as String,
      symlinkTargetHex = value['symlinkTargetHex'] as String,
      mode = value['mode'] as int,
      uid = value['uid'] as int,
      gid = value['gid'] as int,
      deviceHex = value['deviceHex'] as String,
      inodeHex = value['inodeHex'] as String,
      mountId = value['mountId'] as int,
      linkCount = value['linkCount'] as int,
      rawSize = value['rawSize'] as int,
      rawAllocatedBytes = value['rawAllocatedBytes'] as int,
      mtimeSeconds = value['mtimeSeconds'] as int,
      mtimeNanos = value['mtimeNanos'] as int,
      metadataValid = value['metadataValid'] as bool,
      skipped = value['skipped'] as String;
  final IndexedEntry entry;
  final String pathHex, symlinkTargetHex, deviceHex, inodeHex, skipped;
  final int mode,
      uid,
      gid,
      mountId,
      linkCount,
      rawSize,
      rawAllocatedBytes,
      mtimeSeconds,
      mtimeNanos;
  final bool metadataValid;
}

final class IndexPage {
  IndexPage.fromJson(Map<String, dynamic> value)
    : status = value['status'] as String,
      revision = value['revision'] as int? ?? 0,
      provisional = value['provisional'] as bool? ?? true,
      state = value['state'] as String? ?? '',
      phase = value['phase'] as String? ?? '',
      nodeCount = value['nodeCount'] as int? ?? 0,
      issueCount = value['issueCount'] as int? ?? 0,
      nextCursor = value['nextCursor'] as String?,
      previousCursor = value['previousCursor'] as String?,
      pathMatched = value['pathMatched'] as bool? ?? false,
      session = IndexSession.fromJson(value),
      ancestors = List.unmodifiable(
        (value['ancestors'] as List? ?? []).map(
          (e) => IndexAncestor.fromJson(e as Map<String, dynamic>),
        ),
      ),
      entries = List.unmodifiable(
        (value['entries'] as List? ?? []).map(
          (e) => IndexedEntry.fromJson(e as Map<String, dynamic>),
        ),
      ),
      issues = List.unmodifiable(
        (value['issues'] as List? ?? []).map(
          (e) => IndexedIssue.fromJson(e as Map<String, dynamic>),
        ),
      ),
      detail = value['detail'] == null
          ? null
          : IndexDetail.fromJson(value['detail'] as Map<String, dynamic>),
      treemap = value['treemap'] == null
          ? null
          : TreemapSummary.fromJson(value['treemap'] as Map<String, dynamic>);
  final String status, state, phase;
  final int revision, nodeCount, issueCount;
  final bool provisional;
  final bool pathMatched;
  final String? nextCursor, previousCursor;
  final IndexSession session;
  final List<IndexAncestor> ancestors;
  final List<IndexedEntry> entries;
  final List<IndexedIssue> issues;
  final IndexDetail? detail;
  final TreemapSummary? treemap;
  bool get needsRefresh => status == 'stale-cursor';
}

final class TreemapSummary {
  TreemapSummary.fromJson(Map<String, dynamic> value)
    : totalBytes = value['totalBytes'] as int,
      otherBytes = value['otherBytes'] as int,
      childCount = value['childCount'] as int,
      zeroCount = value['zeroCount'] as int,
      unknownCount = value['unknownCount'] as int,
      depth = value['depth'] as int? ?? 1,
      truncated = value['truncated'] as bool? ?? false,
      groups = List.unmodifiable(
        (value['groups'] as List? ?? []).map(
          (e) => TreemapGroup.fromJson(e as Map<String, dynamic>),
        ),
      );
  final int totalBytes, otherBytes, childCount, zeroCount, unknownCount;
  final int depth;
  final bool truncated;
  final List<TreemapGroup> groups;
}

final class TreemapGroup {
  TreemapGroup.fromJson(Map<String, dynamic> value)
    : parentId = value['parentId'] as int,
      totalBytes = value['totalBytes'] as int,
      childCount = value['childCount'] as int,
      entries = List.unmodifiable(
        (value['entries'] as List).map(
          (e) => IndexedEntry.fromJson(e as Map<String, dynamic>),
        ),
      );
  final int parentId, totalBytes, childCount;
  final List<IndexedEntry> entries;
}

final class IndexAncestor {
  IndexAncestor.fromJson(Map<String, dynamic> value)
    : id = value['id'] as int,
      parentId = value['parentId'] as int,
      nameHex = value['nameHex'] as String;
  final int id, parentId;
  final String nameHex;
}

final class VolumeStats {
  VolumeStats.fromJson(Map<String, dynamic> value)
    : totalBytes = value['totalBytes'] as int? ?? 0,
      freeBytes = value['freeBytes'] as int? ?? 0,
      availableBytes = value['availableBytes'] as int? ?? 0,
      capturedAtMillis = value['capturedAtMillis'] as int? ?? 0;
  final int totalBytes, freeBytes, availableBytes, capturedAtMillis;
  bool get valid => capturedAtMillis > 0;
  int get usedBytes => totalBytes - freeBytes;
}

final class StorageEntry {
  StorageEntry.fromJson(Map<String, dynamic> value)
    : label = value['label'] as String,
      path = value['path'] as String,
      state = value['state'] as String,
      removable = value['removable'] as bool,
      stats = VolumeStats.fromJson(value);
  final String label, path, state;
  final bool removable;
  final VolumeStats stats;
  bool get mounted =>
      path.isNotEmpty && (state == 'mounted' || state == 'mounted_ro');
}

final class IndexSession {
  IndexSession.fromJson(Map<String, dynamic> value)
    : id = value['sessionId'] as String? ?? '',
      rootPath = value['rootPath'] as String? ?? '',
      backend = value['backend'] as String? ?? 'ordinary',
      state = value['state'] as String? ?? '',
      phase = value['phase'] as String? ?? '',
      reason = value['reason'] as String? ?? '',
      startedAtMillis = value['startedAtMillis'] as int? ?? 0,
      endedAtMillis = value['endedAtMillis'] as int? ?? 0,
      nodeCount = value['nodeCount'] as int? ?? 0,
      issueCount = value['issueCount'] as int? ?? 0,
      provisional = value['provisional'] as bool? ?? true,
      snapshotId = value['snapshotId'] as String?,
      retained = value['retained'] as bool? ?? false,
      localUpdate = SnapshotLocalUpdate.fromMetadata(value),
      latestRescanTaskId = value['latestRescanTaskId'] as String?,
      volume = VolumeStats.fromJson(
        value['volume'] as Map<String, dynamic>? ?? {},
      );
  final String id, rootPath, backend, state, phase, reason;
  final int startedAtMillis, endedAtMillis, nodeCount, issueCount;
  final bool provisional;
  final String? snapshotId, latestRescanTaskId;
  final bool retained;
  final SnapshotLocalUpdate localUpdate;
  final VolumeStats volume;
  bool get active => state == 'running' || state == 'finalizing';
}

/// Resolve raw names in bounded chunks. A missing suffix returns the nearest
/// surviving ancestor; no decoded display name is ever used as query input.
Future<IndexPage> locateIndexedPath(
  IndexClient client,
  String sessionId,
  String pathHex,
  int revision,
) async {
  final components = <String>[];
  var start = 0;
  for (var i = 0; i < pathHex.length; i += 2) {
    if (pathHex.substring(i, i + 2) == '2f') {
      components.add(pathHex.substring(start, i));
      start = i + 2;
    }
  }
  if (pathHex.isNotEmpty) components.add(pathHex.substring(start));
  var parent = 1;
  var offset = 0;
  while (true) {
    final chunk = <String>[];
    var size = 0;
    while (offset < components.length &&
        size + components[offset].length + (chunk.isEmpty ? 0 : 2) <= 5400) {
      final component = components[offset++];
      size += component.length + (chunk.isEmpty ? 0 : 2);
      chunk.add(component);
    }
    if (chunk.isEmpty && offset < components.length) {
      throw const FormatException('Invalid indexed path component');
    }
    final page = await client.query(
      IndexRequest(
        sessionId: sessionId,
        kind: IndexQueryKind.locate,
        parentId: parent,
        pathHex: chunk.join('2f'),
        expectedRevision: revision,
      ),
    );
    if (page.status != 'ok' ||
        !page.pathMatched ||
        offset == components.length) {
      return page;
    }
    if (page.detail?.entry.kind != 'directory') {
      // Including the next component proves the old directory is now a file;
      // the server then returns its surviving directory ancestor.
      return client.query(
        IndexRequest(
          sessionId: sessionId,
          kind: IndexQueryKind.locate,
          parentId: parent,
          pathHex: [...chunk, components[offset]].join('2f'),
          expectedRevision: revision,
        ),
      );
    }
    parent = page.detail!.entry.id;
  }
}

abstract interface class IndexClient {
  Future<IndexPage> query(IndexRequest request);
  Future<List<IndexSession>> recent();
  Future<List<StorageEntry>> volumes();
}

/// Returns one bounded page; callers replace the page when navigating.
/// A stale cursor requires a new request without its cursor.
final class AndroidIndexClient implements IndexClient {
  AndroidIndexClient({ScannerHostApi? api}) : _api = api ?? ScannerHostApi();
  final ScannerHostApi _api;
  @override
  Future<IndexPage> query(IndexRequest request) async {
    if (request.limit < 1 || request.limit > 100) {
      throw ArgumentError.value(
        request.limit,
        'limit',
        'Must be between 1 and 100',
      );
    }
    final payload = await _api.queryIndex(jsonEncode(request.toJson()));
    return IndexPage.fromJson(jsonDecode(payload) as Map<String, dynamic>);
  }

  @override
  Future<List<IndexSession>> recent() async {
    final value = jsonDecode(
      await _api.queryIndex('{"kind":"recent"}'),
    ) as Map<String, dynamic>;
    return List.unmodifiable(
      (value['sessions'] as List).map(
        (e) => IndexSession.fromJson(e as Map<String, dynamic>),
      ),
    );
  }

  @override
  Future<List<StorageEntry>> volumes() async {
    final value = jsonDecode(
      await _api.queryIndex('{"kind":"volumes"}'),
    ) as Map<String, dynamic>;
    return List.unmodifiable(
      (value['volumes'] as List).map(
        (e) => StorageEntry.fromJson(e as Map<String, dynamic>),
      ),
    );
  }
}
