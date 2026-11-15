import 'dart:async';

import 'package:disksize/features/scan/presentation/scan_home_page.dart';
import 'package:disksize/platform/diagnostics_client.dart';
import 'package:disksize/platform/generated/scanner_api.g.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

final class _DiagnosticsClient implements DiagnosticsClient {
  _DiagnosticsClient(this.read);

  final Future<NativeDiagnostics> Function() read;

  @override
  Future<NativeDiagnostics> readDiagnostics() => read();
}

NativeDiagnostics _diagnostics() => NativeDiagnostics(
  coreVersion: '0.1.0',
  protocolVersion: 1,
  nativeAbi: 'arm64-v8a',
  hostProcessId: 100,
  hostUid: 10357,
  serviceProcessId: 200,
  serviceUid: 10357,
  effectiveUid: 10357,
  serviceProcessName: 'com.example.disksize:scanner',
  pageSizeBytes: 4096,
  pointerBits: 64,
  selfTestPassed: true,
  appDataDirectory: '/data/user/0/com.example.disksize/files',
);

void main() {
  testWidgets('shows diagnostics from the independent scanner process', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: ScanHomePage(
          diagnosticsClient: _DiagnosticsClient(() async => _diagnostics()),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('引擎已就绪'), findsOneWidget);
    expect(find.text('com.example.disksize:scanner'), findsOneWidget);
    expect(find.text('v0.1.0'), findsOneWidget);
    expect(find.text('已独立运行'), findsOneWidget);
  });

  testWidgets('allows retry after a service connection failure', (
    tester,
  ) async {
    var attempts = 0;
    final client = _DiagnosticsClient(() async {
      attempts++;
      if (attempts == 1) {
        throw PlatformException(
          code: 'service-unavailable',
          message: '扫描服务连接中断，请重试',
        );
      }
      return _diagnostics();
    });
    await tester.pumpWidget(
      MaterialApp(home: ScanHomePage(diagnosticsClient: client)),
    );
    await tester.pumpAndSettle();

    expect(find.text('检查失败'), findsOneWidget);
    expect(find.text('扫描服务连接中断，请重试'), findsOneWidget);
    await tester.tap(find.byKey(const Key('retry-diagnostics')));
    await tester.pumpAndSettle();

    expect(attempts, 2);
    expect(find.text('引擎已就绪'), findsOneWidget);
  });

  testWidgets('ignores a diagnostics response after the page is disposed', (
    tester,
  ) async {
    final pending = Completer<NativeDiagnostics>();
    await tester.pumpWidget(
      MaterialApp(
        home: ScanHomePage(
          diagnosticsClient: _DiagnosticsClient(() => pending.future),
        ),
      ),
    );
    expect(find.text('正在检查运行环境'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    pending.complete(_diagnostics());
    await tester.pump();

    expect(tester.takeException(), isNull);
  });
}
