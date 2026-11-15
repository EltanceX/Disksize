import 'dart:convert';

import 'generated/scanner_api.g.dart';

abstract interface class ScanClient {
  Future<ScanAccessStatus> accessStatus();
  Future<void> requestStorageAccess();
  Future<void> requestNotificationAccess();
  Future<void> requestRootAccess();
  Future<RootAccessStatus> rootStatus();
  Future<String> start(String rootPath, String source);
  Future<ScanSnapshot?> snapshot();
  Future<void> cancel(String sessionId);
}

final class AndroidScanClient implements ScanClient {
  AndroidScanClient({ScannerHostApi? api}) : _api = api ?? ScannerHostApi();
  final ScannerHostApi _api;

  @override
  Future<ScanAccessStatus> accessStatus() => _api.getScanAccessStatus();
  @override
  Future<void> requestStorageAccess() => _api.requestStorageAccess();
  @override
  Future<void> requestNotificationAccess() => _api.requestNotificationAccess();
  @override
  Future<void> requestRootAccess() => _api.requestRootAccess();
  @override
  Future<RootAccessStatus> rootStatus() => _api.getRootStatus();
  @override
  Future<String> start(String rootPath, String source) =>
      _api.startScan(rootPath, source);
  @override
  Future<ScanSnapshot?> snapshot() => _api.getScanSnapshot();
  @override
  Future<void> cancel(String sessionId) => _api.cancelScan(sessionId);
}

// Transport retains original bytes. Display substitutes invalid UTF-8 and
// escapes control characters so a filename cannot impersonate a new row.
String displayScanPath(String hex) {
  if (hex.isEmpty) return '.';
  final bytes = <int>[
    for (var offset = 0; offset < hex.length; offset += 2)
      int.parse(hex.substring(offset, offset + 2), radix: 16),
  ];
  return utf8
      .decode(bytes, allowMalformed: true)
      .replaceAll('\\', '\\\\')
      .replaceAll('\n', r'\n')
      .replaceAll('\r', r'\r')
      .replaceAll('\t', r'\t')
      .replaceAllMapped(
        RegExp(
          r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f\u202a-\u202e\u2066-\u2069]',
        ),
        (match) =>
            '\\u${match[0]!.codeUnitAt(0).toRadixString(16).padLeft(4, '0')}',
      );
}
