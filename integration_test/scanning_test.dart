import 'dart:convert';
import 'dart:io';

import 'package:disksize/platform/diagnostics_client.dart';
import 'package:disksize/platform/generated/scanner_api.g.dart';
import 'package:disksize/platform/scan_client.dart';
import 'package:disksize/platform/index_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

final client = AndroidScanClient();

String pathHex(String value) =>
    utf8.encode(value).map((b) => b.toRadixString(16).padLeft(2, '0')).join();

Future<ScanSnapshot> waitSnapshot(
  String id,
  bool Function(ScanSnapshot) predicate,
) async {
  final limit = DateTime.now().add(const Duration(seconds: 90));
  while (DateTime.now().isBefore(limit)) {
    try {
      final value = await client.snapshot();
      if (value != null && value.sessionId == id && predicate(value)) {
        return value;
      }
    } on PlatformException {
      /* Binder death is retried through a new binding. */
    }
    await Future<void>.delayed(const Duration(milliseconds: 30));
  }
  throw StateError('Timed out waiting for session $id');
}

Future<void> waitAccess(bool granted) async {
  final limit = DateTime.now().add(const Duration(seconds: 15));
  while (DateTime.now().isBefore(limit)) {
    if ((await client.accessStatus()).sharedAccessGranted == granted) return;
    await Future<void>.delayed(const Duration(milliseconds: 100));
  }
  throw StateError('Storage grant did not become $granted');
}

Future<void> chmod(Directory directory, String mode) async {
  final result = await Process.run('/system/bin/chmod', [mode, directory.path]);
  expect(result.exitCode, 0);
}

Future<Directory> deepFixture(
  String parent,
  int depth,
  int filesPerLevel,
) async {
  final root = await Directory(parent).createTemp('disksize-phase3-test-');
  var directory = root;
  for (var level = 0; level < depth; level++) {
    directory = await Directory('${directory.path}/level').create();
    for (var index = 0; index < filesPerLevel; index++) {
      await File('${directory.path}/f$index').writeAsString('fixture');
    }
  }
  return root;
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('ordinary UID scans metadata and preserves partial/error results', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(home: Scaffold(body: Text('DiskSize integration'))),
    );
    final access = await client.accessStatus();
    final diagnostics = await AndroidDiagnosticsClient().readDiagnostics();
    final root = await Directory(access.ownPath)
        .createTemp('disksize-phase3-small-');
    final protected = await Directory('${root.path}/protected').create();
    try {
      await File('${root.path}/hello').writeAsString('hello');
      await File('${root.path}/中文 space\nname').writeAsString('ok');
      final sparse = await File('${root.path}/sparse')
          .open(mode: FileMode.write);
      await sparse.truncate(5 * 1024 * 1024 * 1024);
      await sparse.close();
      await File('${protected.path}/secret').writeAsString('12345678');
      await chmod(protected, '000');
      await Link('${root.path}/cycle').create('.');
      final id = await client.start(root.path, 'own');
      final result = await waitSnapshot(id, (value) => !value.active);
      expect(result.state, 'partial');
      expect(result.complete, false);
      expect(result.provisional, false);
      expect(result.logicalBytes, 5 * 1024 * 1024 * 1024 + 7);
      expect(result.issueCount, 1);
      expect(result.serviceUid, diagnostics.hostUid);
      expect(result.serviceUid, greaterThan(0));
      expect(result.servicePid, isNot(diagnostics.hostProcessId));
      expect(result.openFdsAtFinish, 0);
      expect(result.issues.single.code, 'permission-denied');
      expect(result.issues.single.pathHex, pathHex('protected'));
      expect(
        result.entries
            .singleWhere((e) => e.pathHex == pathHex('protected'))
            .complete,
        false,
      );
      expect(
        result.entries.any((e) => e.pathHex == pathHex('中文 space\nname')),
        true,
      );
      expect((await client.snapshot())!.sessionId, id);
      final index = AndroidIndexClient();
      final names = <String>{};
      String? cursor;
      IndexDetail? sparseDetail;
      do {
        final page = await index.query(
          IndexRequest(
            sessionId: id,
            kind: IndexQueryKind.children,
            parentId: 1,
            sort: IndexSort.name,
            limit: 2,
            cursor: cursor,
          ),
        );
        expect(page.status, 'ok');
        expect(page.provisional, false);
        for (final entry in page.entries) {
          expect(names.add(entry.nameHex), true);
          if (entry.nameHex == pathHex('sparse')) {
            sparseDetail = (await index.query(
              IndexRequest(
                sessionId: id,
                kind: IndexQueryKind.detail,
                nodeId: entry.id,
              ),
            )).detail;
          }
        }
        cursor = page.nextCursor;
      } while (cursor != null);
      expect(names, {
        pathHex('hello'),
        pathHex('中文 space\nname'),
        pathHex('sparse'),
        pathHex('protected'),
        pathHex('cycle'),
      });
      expect(sparseDetail!.rawSize, 5 * 1024 * 1024 * 1024);
      expect(sparseDetail.rawAllocatedBytes, 0);
      final indexedIssues = await index.query(
        IndexRequest(sessionId: id, kind: IndexQueryKind.issues),
      );
      expect(indexedIssues.issues.single.code, 'permission-denied');
      await client.cancel('stale-session-id');
      expect((await client.snapshot())!.state, 'partial');
      debugPrint(
        'PHASE3_PARTIAL_OK uid=${result.serviceUid} notifications=${access.notificationGranted}',
      );

      final missing = await client.start('${root.path}/missing', 'path');
      final failed = await waitSnapshot(missing, (value) => !value.active);
      expect(failed.state, 'failed');
      expect(failed.issues.single.code, 'not-found');

      if (!access.sharedAccessGranted) {
        await expectLater(
          client.start(access.sharedPath, 'shared'),
          throwsA(isA<PlatformException>()),
        );
      }
    } finally {
      await chmod(protected, '755');
      await root.delete(recursive: true);
    }
  });

  testWidgets('cancellation, real service death and page reconnection', (
    tester,
  ) async {
    final access = await client.accessStatus();
    final root = await deepFixture(access.ownPath, 450, 48);
    try {
      var id = await client.start(root.path, 'own');
      await waitSnapshot(id, (value) => value.state == 'running');
      await client.cancel(id);
      final cancelled = await waitSnapshot(id, (value) => !value.active);
      expect(cancelled.state, 'cancelled');
      expect(cancelled.complete, false);
      expect(cancelled.openFdsAtFinish, 0);

      id = await client.start(root.path, 'own');
      final running = await waitSnapshot(
        id,
        (value) => value.state == 'running',
      );
      expect(Process.killPid(running.servicePid, ProcessSignal.sigkill), true);
      final recovered = await waitSnapshot(id, (value) => !value.active);
      expect(recovered.state, 'interrupted');
      expect(recovered.reason, 'process-interrupted');
      expect(recovered.complete, false);
      expect(
        (await AndroidDiagnosticsClient().readDiagnostics()).serviceProcessId,
        isNot(running.servicePid),
      );
      debugPrint('PHASE3_SERVICE_DEATH_OK');

      id = await client.start(root.path, 'own');
      await waitSnapshot(id, (value) => value.state == 'running');
      await tester.pumpWidget(const SizedBox());
      final completed = await waitSnapshot(id, (value) => !value.active);
      expect(completed.state, 'completed');
      expect(completed.complete, true);
      expect(completed.previewTruncated, true);
      expect(completed.entries.length, lessThanOrEqualTo(64));
      expect(completed.logicalBytes, 450 * 48 * 7);
      expect(completed.openFdsAtFinish, 0);
      // Recovery of a terminal result must preserve Completed, too.
      expect(
        Process.killPid(completed.servicePid, ProcessSignal.sigkill),
        true,
      );
      final restored = await waitSnapshot(id, (value) => !value.active);
      expect(restored.state, 'completed');
      expect(restored.logicalBytes, completed.logicalBytes);
      expect(restored.entries.length, completed.entries.length);
      debugPrint('PHASE3_TERMINAL_RECOVERY_OK');
    } finally {
      await root.delete(recursive: true);
    }
  }, timeout: const Timeout(Duration(minutes: 6)));

  if (const bool.fromEnvironment('PHASE3_DEVICE_DRIVER')) {
    testWidgets('real background, shared storage and dataSync timeout', (
      tester,
    ) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: Text('DiskSize lifecycle verification')),
        ),
      );
      debugPrint('PHASE3_GRANT_STORAGE');
      await waitAccess(true);
      final access = await client.accessStatus();
      final root = await deepFixture('${access.sharedPath}/Download', 80, 16);
      try {
        var id = await client.start(root.path, 'shared');
        await waitSnapshot(id, (value) => value.state == 'running');
        debugPrint('PHASE3_BACKGROUND_READY');
        await Future<void>.delayed(const Duration(milliseconds: 200));
        await expectLater(
          client.start(root.path, 'shared'),
          throwsA(isA<PlatformException>()),
        );
        await Future<void>.delayed(const Duration(seconds: 2));
        final background = await waitSnapshot(id, (value) => !value.active);
        expect(background.state, 'completed');
        expect(background.logicalBytes, 80 * 16 * 7);
        debugPrint('PHASE3_BACKGROUND_OK');

        debugPrint('PHASE3_PREPARE_TIMEOUT');
        await Future<void>.delayed(const Duration(seconds: 1));
        id = await client.start(root.path, 'shared');
        final started = await waitSnapshot(
          id,
          (value) => value.state == 'running' || !value.active,
        );
        if (started.active) debugPrint('PHASE3_TIMEOUT_READY');
        final timedOut = await waitSnapshot(id, (value) => !value.active);
        expect(timedOut.state, 'interrupted');
        expect(timedOut.reason, 'foreground-timeout');
        expect(timedOut.complete, false);
        debugPrint('PHASE3_TIMEOUT_OK');
      } finally {
        debugPrint('PHASE3_GRANT_STORAGE');
        await waitAccess(true);
        final remaining = await client.snapshot();
        if (remaining?.active == true && remaining!.rootPath == root.path) {
          await client.cancel(remaining.sessionId);
          await waitSnapshot(remaining.sessionId, (value) => !value.active);
        }
        await root.delete(recursive: true);
      }
    }, timeout: const Timeout(Duration(minutes: 8)));
  }
}
