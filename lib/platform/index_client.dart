import 'dart:convert';

import 'generated/scanner_api.g.dart';

enum IndexQueryKind { session, children, largeFiles, detail, issues }

enum IndexSort { logical, allocated, name }

final class IndexRequest {
  const IndexRequest({
    required this.sessionId,
    required this.kind,
    this.parentId = 0,
    this.nodeId = 0,
    this.sort = IndexSort.logical,
    this.limit = 100,
    this.cursor,
  });
  final String sessionId;
  final IndexQueryKind kind;
  final int parentId, nodeId, limit;
  final IndexSort sort;
  final String? cursor;

  Map<String, Object?> toJson() => {
    'sessionId': sessionId,
    'kind': kind == IndexQueryKind.largeFiles ? 'large-files' : kind.name,
    'parentId': parentId,
    'nodeId': nodeId,
    'sort': sort.name,
    'limit': limit,
    'cursor': cursor ?? '',
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
      ownerId = value['ownerId'] as int;
  final int id, parentId, logicalBytes, allocatedBytes, ownerId;
  final String nameHex, kind;
  final bool complete;
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
          : IndexDetail.fromJson(value['detail'] as Map<String, dynamic>);
  final String status, state, phase;
  final int revision, nodeCount, issueCount;
  final bool provisional;
  final String? nextCursor;
  final List<IndexedEntry> entries;
  final List<IndexedIssue> issues;
  final IndexDetail? detail;
  bool get needsRefresh => status == 'stale-cursor';
}

/// Returns one bounded page; callers replace the page when navigating.
/// A stale cursor requires a new request without its cursor.
final class AndroidIndexClient {
  AndroidIndexClient({ScannerHostApi? api}) : _api = api ?? ScannerHostApi();
  final ScannerHostApi _api;
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
}
