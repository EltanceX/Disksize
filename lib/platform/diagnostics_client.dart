import 'generated/scanner_api.g.dart';

abstract interface class DiagnosticsClient {
  Future<NativeDiagnostics> readDiagnostics();
}

final class AndroidDiagnosticsClient implements DiagnosticsClient {
  AndroidDiagnosticsClient({ScannerHostApi? api})
    : _api = api ?? ScannerHostApi();

  final ScannerHostApi _api;

  @override
  Future<NativeDiagnostics> readDiagnostics() => _api.getNativeDiagnostics();
}
