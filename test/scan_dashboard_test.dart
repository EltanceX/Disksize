import 'dart:async';

import 'package:disksize/features/scan/presentation/scan_dashboard_page.dart';
import 'package:disksize/platform/diagnostics_client.dart';
import 'package:disksize/platform/generated/scanner_api.g.dart';
import 'package:disksize/platform/scan_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class _UnusedDiagnostics implements DiagnosticsClient {
  @override
  Future<NativeDiagnostics> readDiagnostics() => throw UnimplementedError();
}

ScanSnapshot view(String state, {bool active = false}) => ScanSnapshot(
  sessionId: 'session',
  rootPath: '/own/fixture',
  state: state,
  reason: '',
  startedAtMillis: 1,
  updatedAtMillis: 2,
  endedAtMillis: active ? 0 : 3,
  nodeCount: 8,
  logicalBytes: 42,
  allocatedBytes: 4096,
  provisional: active,
  complete: state == 'completed',
  issueCount: state == 'partial' ? 1 : 0,
  previewTruncated: false,
  serviceUid: 10001,
  servicePid: 222,
  active: active,
  peakOpenFds: 4,
  openFdsAtFinish: active ? -1 : 0,
  entries: [
    ScanEntryPreview(
      pathHex: '646972',
      kind: 'directory',
      logicalBytes: 0,
      allocatedBytes: 4096,
      complete: false,
      skipped: 'none',
    ),
  ],
  issues: state == 'partial'
      ? [
          ScanIssuePreview(
            pathHex: '646972',
            code: 'permission-denied',
            operation: 'open-directory',
            systemError: 13,
          ),
        ]
      : [],
);

class _Client implements ScanClient {
  ScanSnapshot? value;
  int reads = 0;
  bool fail = false;
  String? startedPath;
  String? startedSource;
  int rootRequests = 0;
  RootAccessStatus root = RootAccessStatus(
    state: 'not-requested',
    reason: '',
    users: [],
    roots: [],
  );
  Completer<ScanSnapshot?>? pending;
  @override
  Future<ScanAccessStatus> accessStatus() async => ScanAccessStatus(
    apiLevel: 36,
    ownPath: '/own',
    sharedPath: '/shared',
    sharedAccessGranted: false,
    legacyStorage: false,
    notificationGranted: false,
  );
  @override
  Future<ScanSnapshot?> snapshot() async {
    reads++;
    if (fail) {
      throw PlatformException(code: 'service-unavailable', message: '扫描服务连接中断');
    }
    return pending == null ? value : pending!.future;
  }

  @override
  Future<String> start(String rootPath, String source) async {
    startedPath = rootPath;
    startedSource = source;
    value = view('completed');
    return 'session';
  }

  @override
  Future<void> cancel(String sessionId) async {
    value = view('cancelled');
  }

  @override
  Future<void> requestNotificationAccess() async {}
  @override
  Future<void> requestStorageAccess() async {}
  @override
  Future<void> requestRootAccess() async {
    rootRequests++;
  }

  @override
  Future<RootAccessStatus> rootStatus() async => root;
}

Widget _page(_Client client) => MaterialApp(
  home: ScanDashboardPage(
    client: client,
    diagnosticsClient: _UnusedDiagnostics(),
  ),
);

void main() {
  testWidgets(
    'root access is explicit and denial leaves ordinary scanning available',
    (tester) async {
      final client = _Client();
      await tester.pumpWidget(_page(client));
      await tester.pumpAndSettle();
      expect(client.rootRequests, 0);
      await tester.tap(find.byKey(const Key('request-root')));
      await tester.pumpAndSettle();
      expect(client.rootRequests, 1);
      client.root = RootAccessStatus(
        state: 'unavailable',
        reason: 'root-denied',
        users: [],
        roots: [],
      );
      await tester.tap(find.byTooltip('刷新'));
      await tester.pumpAndSettle();
      expect(find.text('Root 不可用，可继续使用普通模式。'), findsOneWidget);
      expect(
        tester
            .widget<SwitchListTile>(find.byKey(const Key('root-mode')))
            .onChanged,
        isNull,
      );
      await tester.scrollUntilVisible(
        find.byKey(const Key('start-scan')),
        150,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.byKey(const Key('start-scan')));
      await tester.pumpAndSettle();
      expect(client.startedSource, 'own');
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('authorized root mode sends raw path without trimming', (
    tester,
  ) async {
    final client = _Client()
      ..root = RootAccessStatus(
        state: 'ready',
        reason: '',
        uid: 0,
        pid: 123,
        mountNamespace: 'mnt:[42]',
        userId: 0,
        userUnlocked: true,
        users: [0],
        roots: [
          RootPath(
            path: '/data',
            requestedPath: '/data',
            readable: true,
            mountId: '42',
            filesystem: 'f2fs',
            aliasGroup: '',
          ),
        ],
      );
    await tester.pumpWidget(_page(client));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('root-mode')));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.byKey(const Key('scan-root')),
      180,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.enterText(find.byKey(const Key('scan-root')), '/data/space ');
    await tester.scrollUntilVisible(
      find.byKey(const Key('start-scan')),
      150,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.byKey(const Key('start-scan')));
    await tester.pumpAndSettle();
    expect(client.startedSource, 'root');
    expect(client.startedPath, '/data/space ');
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets(
    'notification denial permits own scan and exposes permission guidance',
    (tester) async {
      final client = _Client();
      await tester.pumpWidget(_page(client));
      await tester.pumpAndSettle();
      expect(find.text('共享存储权限：未授权'), findsOneWidget);
      expect(
        tester
            .widget<FilledButton>(find.byKey(const Key('start-scan')))
            .onPressed,
        isNotNull,
      );
      await tester.tap(find.byKey(const Key('start-scan')));
      await tester.pumpAndSettle();
      expect(client.startedPath, '/own');
      await tester.scrollUntilVisible(
        find.byKey(const Key('scan-state')),
        250,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('扫描完成'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('partial results retain unknown scope and error paths', (
    tester,
  ) async {
    final client = _Client()..value = view('partial');
    await tester.pumpWidget(_page(client));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.byKey(const Key('scan-state')),
      250,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('部分完成'), findsOneWidget);
    expect(find.text('结果未覆盖全部范围；未读取的目录容量仍未知。'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('问题范围'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.textContaining('permission-denied'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('reconnect shows interrupted and stops polling while paused', (
    tester,
  ) async {
    final client = _Client()..fail = true;
    await tester.pumpWidget(_page(client));
    await tester.pump();
    await tester.pump();
    expect(find.text('扫描服务连接中断'), findsOneWidget);
    client.fail = false;
    client.value = view('running', active: true);
    await tester.pump(const Duration(milliseconds: 1300));
    await tester.pump();
    final reads = client.reads;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump(const Duration(seconds: 2));
    expect(client.reads, reads);
    client.value = view('interrupted');
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    await tester.pump();
    await tester.scrollUntilVisible(
      find.byKey(const Key('scan-state')),
      250,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('扫描已中断'), findsOneWidget);
    expect(find.text('扫描完成'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('disposed page ignores an outstanding snapshot', (tester) async {
    final client = _Client()..pending = Completer<ScanSnapshot?>();
    await tester.pumpWidget(_page(client));
    await tester.pump();
    await tester.pumpWidget(const SizedBox());
    client.pending!.complete(view('completed'));
    await tester.pump();
    expect(tester.takeException(), isNull);
  });

  test('raw filename display preserves transport and escapes newlines', () {
    expect(displayScanPath('61ff0a62'), 'a�\\nb');
    expect(displayScanPath(''), '.');
  });
}
