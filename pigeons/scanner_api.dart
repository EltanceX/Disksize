import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/platform/generated/scanner_api.g.dart',
    kotlinOut: 'android/app/src/main/kotlin/com/example/disksize/bridge/ScannerApi.g.kt',
    kotlinOptions: KotlinOptions(package: 'com.example.disksize.bridge'),
    dartPackageName: 'disksize',
  ),
)
class NativeDiagnostics {
  NativeDiagnostics({
    required this.coreVersion,
    required this.protocolVersion,
    required this.nativeAbi,
    required this.hostProcessId,
    required this.hostUid,
    required this.serviceProcessId,
    required this.serviceUid,
    required this.effectiveUid,
    required this.serviceProcessName,
    required this.pageSizeBytes,
    required this.pointerBits,
    required this.selfTestPassed,
    required this.appDataDirectory,
  });

  String coreVersion;
  int protocolVersion;
  String nativeAbi;
  int hostProcessId;
  int hostUid;
  int serviceProcessId;
  int serviceUid;
  int effectiveUid;
  String serviceProcessName;
  int pageSizeBytes;
  int pointerBits;
  bool selfTestPassed;
  String appDataDirectory;
}

@HostApi()
abstract class ScannerHostApi {
  @async
  NativeDiagnostics getNativeDiagnostics();

  @async
  ScanAccessStatus getScanAccessStatus();
  @async
  void requestStorageAccess();
  @async
  void requestNotificationAccess();
  @async
  void requestRootAccess();
  @async
  RootAccessStatus getRootStatus();
  @async
  String startScan(String rootPath, String source);
  @async
  ScanSnapshot? getScanSnapshot();
  @async
  void cancelScan(String sessionId);
  @async
  String queryIndex(String requestJson);
  @async
  void openIndexedEntry(String sessionId, int nodeId);
}

class RootPath {
  RootPath({
    required this.path,
    required this.requestedPath,
    required this.readable,
    required this.mountId,
    required this.filesystem,
    required this.aliasGroup,
  });
  String path;
  String requestedPath;
  bool readable;
  String mountId;
  String filesystem;
  String aliasGroup;
}

class RootAccessStatus {
  RootAccessStatus({
    required this.state,
    required this.reason,
    this.uid,
    this.effectiveUid,
    this.pid,
    this.mountNamespace,
    this.userId,
    this.userUnlocked,
    required this.users,
    required this.roots,
  });
  String state;
  String reason;
  int? uid;
  int? effectiveUid;
  int? pid;
  String? mountNamespace;
  int? userId;
  bool? userUnlocked;
  List<int> users;
  List<RootPath> roots;
}

class ScanAccessStatus {
  ScanAccessStatus({
    required this.apiLevel,
    required this.ownPath,
    required this.sharedPath,
    this.externalOwnPath,
    required this.sharedAccessGranted,
    required this.legacyStorage,
    required this.notificationGranted,
  });
  int apiLevel;
  String ownPath;
  String sharedPath;
  String? externalOwnPath;
  bool sharedAccessGranted;
  bool legacyStorage;
  bool notificationGranted;
}

class ScanEntryPreview {
  ScanEntryPreview({
    required this.pathHex,
    required this.kind,
    required this.logicalBytes,
    required this.allocatedBytes,
    required this.complete,
    required this.skipped,
  });
  String pathHex;
  String kind;
  int logicalBytes;
  int allocatedBytes;
  bool complete;
  String skipped;
}

class ScanIssuePreview {
  ScanIssuePreview({
    required this.pathHex,
    required this.code,
    required this.operation,
    required this.systemError,
  });
  String pathHex;
  String code;
  String operation;
  int systemError;
}

class ScanSnapshot {
  ScanSnapshot({
    required this.sessionId,
    required this.rootPath,
    required this.state,
    required this.reason,
    required this.startedAtMillis,
    required this.updatedAtMillis,
    required this.endedAtMillis,
    required this.nodeCount,
    required this.logicalBytes,
    required this.allocatedBytes,
    required this.provisional,
    required this.complete,
    required this.issueCount,
    required this.previewTruncated,
    required this.serviceUid,
    required this.servicePid,
    required this.active,
    required this.peakOpenFds,
    required this.openFdsAtFinish,
    required this.entries,
    required this.issues,
    this.backend,
    this.scannerUid,
    this.scannerPid,
    this.rootMountId,
  });
  String sessionId;
  String rootPath;
  String state;
  String reason;
  int startedAtMillis;
  int updatedAtMillis;
  int endedAtMillis;
  int nodeCount;
  int logicalBytes;
  int allocatedBytes;
  bool provisional;
  bool complete;
  int issueCount;
  bool previewTruncated;
  int serviceUid;
  int servicePid;
  bool active;
  int peakOpenFds;
  int openFdsAtFinish;
  List<ScanEntryPreview> entries;
  List<ScanIssuePreview> issues;
  String? backend;
  int? scannerUid;
  int? scannerPid;
  int? rootMountId;
}
