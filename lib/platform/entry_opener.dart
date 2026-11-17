import 'generated/scanner_api.g.dart';

abstract interface class EntryOpener {
  Future<void> open(String sessionId, int nodeId);
}

final class AndroidEntryOpener implements EntryOpener {
  AndroidEntryOpener({ScannerHostApi? api}) : _api = api ?? ScannerHostApi();
  final ScannerHostApi _api;

  @override
  Future<void> open(String sessionId, int nodeId) =>
      _api.openIndexedEntry(sessionId, nodeId);
}
