enum ScanAccessMode { app, allFiles, root }

enum ScanSessionState {
  idle,
  queued,
  running,
  cancelling,
  cancelled,
  completed,
  partial,
  failed,
  interrupted,
}

enum ScanErrorCode {
  invalidConfiguration,
  permissionDenied,
  notFound,
  io,
  cancelled,
  unsupported,
  serviceUnavailable,
}

final class ScanConfiguration {
  const ScanConfiguration({
    required this.rootPath,
    this.accessMode = ScanAccessMode.app,
    this.followSymbolicLinks = false,
    this.crossMountPoints = false,
  });

  final String rootPath;
  final ScanAccessMode accessMode;
  final bool followSymbolicLinks;
  final bool crossMountPoints;
}

final class ScanFailure {
  const ScanFailure({
    required this.code,
    required this.message,
    this.path,
    this.systemError,
  });

  final ScanErrorCode code;
  final String message;
  final String? path;
  final int? systemError;
}

final class ScanSession {
  const ScanSession({
    required this.id,
    required this.configuration,
    this.state = ScanSessionState.idle,
    this.cancellationRequested = false,
    this.failure,
  });

  final String id;
  final ScanConfiguration configuration;
  final ScanSessionState state;
  final bool cancellationRequested;
  final ScanFailure? failure;
}
