import 'dart:convert';

import 'generated/scanner_api.g.dart';

final class SnapshotRescanTask {
  SnapshotRescanTask.fromJson(Map<String, dynamic> json)
    : id = json['taskId'] as String,
      snapshotId = json['snapshotId'] as String,
      sessionId = json['sessionId'] as String,
      nodeId = json['targetNodeId'] as int,
      expectedRevision = json['expectedRevision'] as int,
      pathHex = json['targetPathHex'] as String,
      pathTruncated = json['pathTruncated'] as bool,
      state = json['state'] as String,
      error = json['error'] as String,
      active = json['active'] as bool,
      nodes = json['nodeCount'] as int,
      issues = json['issueCount'] as int,
      created = json['createdAtMillis'] as int,
      started = json['startedAtMillis'] as int,
      scanned = json['scannedAtMillis'] as int,
      ended = json['endedAtMillis'] as int,
      committedRevision = json['committedRevision'] as int?,
      cancellationRequested = json['cancellationRequested'] as bool,
      stagingCleaned = json['stagingCleaned'] as bool;

  final String id, snapshotId, sessionId, pathHex, state, error;
  final int nodeId,
      expectedRevision,
      nodes,
      issues,
      created,
      started,
      scanned,
      ended;
  final int? committedRevision;
  final bool active, pathTruncated, cancellationRequested, stagingCleaned;
}

final class SnapshotRescanException implements Exception {
  const SnapshotRescanException(this.code);
  final String code;
  @override
  String toString() => 'SnapshotRescanException($code)';
}

abstract interface class SnapshotRescanClient {
  Future<SnapshotRescanTask> start(
    String snapshotId,
    int nodeId,
    int expectedRevision,
  );
  Future<SnapshotRescanTask?> task(String taskId);
  Future<String> cancel(String taskId);
}

final class AndroidSnapshotRescanClient implements SnapshotRescanClient {
  AndroidSnapshotRescanClient({ScannerHostApi? api})
    : _api = api ?? ScannerHostApi();
  final ScannerHostApi _api;

  Map<String, dynamic> _decode(String value) =>
      jsonDecode(value) as Map<String, dynamic>;
  SnapshotRescanTask _task(Map<String, dynamic> value) {
    if (value['status'] != 'ok') {
      throw SnapshotRescanException(value['status'] as String);
    }
    return SnapshotRescanTask.fromJson(value);
  }

  @override
  Future<SnapshotRescanTask> start(
    String snapshotId,
    int nodeId,
    int expectedRevision,
  ) async => _task(
    _decode(
      await _api.startSnapshotRescan(snapshotId, nodeId, expectedRevision),
    ),
  );

  @override
  Future<SnapshotRescanTask?> task(String taskId) async {
    final value = _decode(await _api.getSnapshotRescan(taskId));
    return value['status'] == 'not-found' ? null : _task(value);
  }

  @override
  Future<String> cancel(String taskId) async {
    final status =
        _decode(await _api.cancelSnapshotRescan(taskId))['status'] as String;
    if (!const {
      'cancellation-requested',
      'already-completed',
      'already-terminal',
    }.contains(status)) {
      throw SnapshotRescanException(status);
    }
    return status;
  }
}
