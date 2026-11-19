import 'dart:convert';

import 'generated/scanner_api.g.dart';

final class SnapshotLocalUpdate {
  SnapshotLocalUpdate.fromMetadata(Map<String, dynamic> json)
    : at = json['lastLocalUpdateAtMillis'] as int? ?? 0,
      count = json['localUpdateCount'] as int? ?? 0,
      pathHex =
          (json['lastLocalUpdate'] as Map?)?['targetPathHex'] as String? ?? '',
      pathTruncated =
          (json['lastLocalUpdate'] as Map?)?['pathTruncated'] as bool? ?? false,
      started =
          (json['lastLocalUpdate'] as Map?)?['startedAtMillis'] as int? ?? 0,
      scanned =
          (json['lastLocalUpdate'] as Map?)?['scannedAtMillis'] as int? ?? 0,
      ended = (json['lastLocalUpdate'] as Map?)?['endedAtMillis'] as int? ?? 0;
  final int at, count, started, scanned, ended;
  final String pathHex;
  final bool pathTruncated;
  bool get updated => count > 0;
}

final class SnapshotOperationException implements Exception {
  const SnapshotOperationException(this.code);
  final String code;
  @override
  String toString() => switch (code) {
    'busy' => '正在扫描或应用快照更新，请等待任务结束后重试。',
    'retained' => '请先取消保留，再修改或删除快照。',
    'stale-cursor' => '快照内容已更新，请重新加载。',
    'not-found' => '快照已不可用，请重新加载列表。',
    _ => '快照操作失败（$code），请重新加载后重试。',
  };
}

final class DiskSnapshot {
  DiskSnapshot.fromJson(Map<String, dynamic> json)
    : id = json['id'] as String,
      rootPath = json['rootPath'] as String,
      backend = json['backend'] as String,
      state = json['state'] as String,
      started = json['startedAtMillis'] as int,
      ended = json['endedAtMillis'] as int,
      logical = json['logicalBytes'] as int,
      allocated = json['allocatedBytes'] as int,
      estimatedStorageBytes = json['estimatedStorageBytes'] as int?,
      complete = json['complete'] as bool,
      retained = json['retained'] as bool,
      available = json['available'] as bool,
      revision = json['revision'] as int? ?? 0,
      localUpdate = SnapshotLocalUpdate.fromMetadata(json);
  final int revision;
  final SnapshotLocalUpdate localUpdate;
  final String id, rootPath, backend, state;
  final int started, ended, logical, allocated;
  final int? estimatedStorageBytes;
  final bool complete, retained, available;
}

final class SnapshotList {
  SnapshotList.fromJson(Map<String, dynamic> json)
    : snapshots = List.unmodifiable(
        (json['snapshots'] as List).map(
          (e) => DiskSnapshot.fromJson(e as Map<String, dynamic>),
        ),
      ),
      nextCursor = json['nextCursor'] as String?;
  final List<DiskSnapshot> snapshots;
  final String? nextCursor;
}

final class SnapshotChange {
  SnapshotChange.fromJson(Map<String, dynamic> json)
    : pathHex = json['pathHex'] as String,
      status = json['status'] as String,
      oldKind = json['oldKind'] as String,
      newKind = json['newKind'] as String,
      oldLogical = json['oldLogical'] as int?,
      newLogical = json['newLogical'] as int?,
      oldAllocated = json['oldAllocated'] as int?,
      newAllocated = json['newAllocated'] as int?,
      logicalDelta = json['logicalDelta'] as int?,
      allocatedDelta = json['allocatedDelta'] as int?;
  final String pathHex, status, oldKind, newKind;
  final int? oldLogical, newLogical, oldAllocated, newAllocated;
  final int? logicalDelta, allocatedDelta;
}

final class SnapshotComparison {
  SnapshotComparison.fromJson(Map<String, dynamic> json)
    : status = json['status'] as String,
      base = json['base'] == null
          ? null
          : DiskSnapshot.fromJson(json['base'] as Map<String, dynamic>),
      target = json['target'] == null
          ? null
          : DiskSnapshot.fromJson(json['target'] as Map<String, dynamic>),
      reasons = List<String>.from(json['reasons'] as List? ?? []),
      processed = json['processed'] as int? ?? 0,
      complete = json['complete'] as bool? ?? false,
      logicalDelta = json['logicalDelta'] as int?,
      allocatedDelta = json['allocatedDelta'] as int?,
      counts = Map<String, int>.from(json['counts'] as Map? ?? {}),
      nextCursor = json['nextCursor'] as String?,
      entries = List.unmodifiable(
        (json['entries'] as List? ?? []).map(
          (e) => SnapshotChange.fromJson(e as Map<String, dynamic>),
        ),
      );
  final String status;
  final DiskSnapshot? base, target;
  final List<String> reasons;
  final int processed;
  final bool complete;
  final int? logicalDelta, allocatedDelta;
  final Map<String, int> counts;
  final List<SnapshotChange> entries;
  final String? nextCursor;
}

abstract interface class SnapshotClient {
  Future<SnapshotList> list({String? cursor});
  Future<void> retain(String id, bool retained);
  Future<void> delete(String id);
  Future<SnapshotComparison> compare(
    String base,
    String target, {
    String sort = 'logical',
    String filter = 'all',
    String? cursor,
  });
}

final class AndroidSnapshotClient implements SnapshotClient {
  AndroidSnapshotClient({ScannerHostApi? api}) : _api = api ?? ScannerHostApi();
  final ScannerHostApi _api;
  Future<Map<String, dynamic>> _request(Map<String, Object?> request) async =>
      jsonDecode(await _api.queryIndex(jsonEncode(request)))
          as Map<String, dynamic>;
  @override
  Future<SnapshotList> list({String? cursor}) async {
    final json = await _request({
      'kind': 'snapshot-list',
      'cursor': cursor ?? '',
    });
    _check(json);
    return SnapshotList.fromJson(json);
  }

  void _check(Map<String, dynamic> json) {
    if (json['status'] != 'ok') {
      throw SnapshotOperationException(json['status'] as String);
    }
  }

  @override
  Future<void> retain(String id, bool retained) async => _check(
    await _request({
      'kind': 'snapshot-retain',
      'snapshotId': id,
      'retained': retained,
    }),
  );
  @override
  Future<void> delete(String id) async =>
      _check(await _request({'kind': 'snapshot-delete', 'snapshotId': id}));
  @override
  Future<SnapshotComparison> compare(
    String base,
    String target, {
    String sort = 'logical',
    String filter = 'all',
    String? cursor,
  }) async => SnapshotComparison.fromJson(
    await _request({
      'kind': 'snapshot-compare',
      'baseId': base,
      'targetId': target,
      'sort': sort,
      'filter': filter,
      'cursor': cursor ?? '',
    }),
  );
}
