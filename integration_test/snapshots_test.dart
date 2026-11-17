import 'dart:io';

import 'package:disksize/features/snapshots/snapshot_comparison_page.dart';
import 'package:disksize/features/snapshots/snapshots_page.dart';
import 'package:disksize/platform/index_client.dart';
import 'package:disksize/platform/scan_client.dart';
import 'package:disksize/platform/snapshot_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('real scans, frozen snapshots, growth, retention and deletion', (
    tester,
  ) async {
    final scan = AndroidScanClient();
    final client = AndroidSnapshotClient();
    final index = AndroidIndexClient();
    await tester.pumpWidget(
      const MaterialApp(home: Scaffold(body: Text('Preparing snapshots'))),
    );
    final access = await scan.accessStatus();
    const cleanupRoot = String.fromEnvironment('PHASE8_CLEANUP_ROOT');
    if (cleanupRoot.isNotEmpty) {
      final canonicalOwn = await Directory(access.ownPath)
          .resolveSymbolicLinks();
      final canonicalCleanup = await Directory(cleanupRoot)
          .resolveSymbolicLinks();
      if (!canonicalCleanup.startsWith('$canonicalOwn/disksize-phase8-') ||
          canonicalCleanup.substring(canonicalOwn.length + 1).contains('/')) {
        throw StateError(
          'Cleanup must identify one exact phase8 fixture directory',
        );
      }
      String? cursor;
      do {
        final history = await client.list(cursor: cursor);
        for (final item in history.snapshots.where(
          (item) => item.rootPath == cleanupRoot,
        )) {
          await client.retain(item.id, false);
          await client.delete(item.id);
        }
        cursor = history.nextCursor;
      } while (cursor != null);
      await Directory(cleanupRoot).delete(recursive: true);
    }
    final fixture = await Directory(access.ownPath)
        .createTemp('disksize-phase8-');
    final ids = <String>[];
    Future<String> capture() async {
      final id = await scan.start(fixture.path, 'own');
      ids.add(id);
      final deadline = DateTime.now().add(const Duration(seconds: 45));
      while ((await scan.snapshot())!.active) {
        if (DateTime.now().isAfter(deadline)) {
          throw StateError('Scan timed out');
        }
        await Future<void>.delayed(const Duration(milliseconds: 50));
      }
      expect((await scan.snapshot())!.state, 'completed');
      return id;
    }

    Future<SnapshotComparison> compare(String a, String b) async {
      while (true) {
        final page = await client.compare(a, b);
        if (page.status != 'building') return page;
        await Future<void>.delayed(const Duration(milliseconds: 20));
      }
    }

    try {
      await File('${fixture.path}/removed').writeAsString('12345');
      final folder = await Directory('${fixture.path}/Cache').create();
      await File('${folder.path}/grow').writeAsString('12');
      final a = await capture();
      await File('${fixture.path}/removed').delete();
      await File('${fixture.path}/new\n文件').writeAsString('1234567');
      await File('${folder.path}/grow').writeAsString('12345678');
      final b = await capture();
      final page = await compare(a, b);
      expect(page.status, 'ok');
      expect(page.logicalDelta, 8);
      expect(page.counts['added'], 1);
      expect(page.counts['deleted'], 1);
      expect(
        page.entries.any(
          (e) => e.pathHex == '4361636865' && e.logicalDelta == 6,
        ),
        isTrue,
      );
      expect(
        (await index.query(
          IndexRequest(sessionId: a, kind: IndexQueryKind.detail, nodeId: 1),
        )).detail!.entry.logicalBytes,
        7,
      );
      await client.retain(a, true);
      await expectLater(client.delete(a), throwsStateError);
      await tester.pumpWidget(
        MaterialApp(
          home: SnapshotComparisonPage(client: client, baseId: a, targetId: b),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('总差额：+8 B'), findsOneWidget);
      expect(find.text(r'new\n文件'), findsOneWidget);
      await binding.convertFlutterSurfaceToImage();
      await tester.pumpAndSettle();
      final screenshot = await binding.takeScreenshot('phase8-comparison');
      final external = access.externalOwnPath;
      if (external != null) {
        await File('$external/phase8-comparison.png').writeAsBytes(screenshot);
      }
      await tester.pumpWidget(
        MaterialApp(
          home: SnapshotsPage(
            client: client,
            indexClient: index,
            initialBase: a,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('快照与增长对比'), findsOneWidget);
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: Text('Cleaning snapshot fixtures')),
        ),
      );
      await client.retain(a, false);
      await client.delete(a);
      ids.remove(a);
      expect(await File('${folder.path}/grow').readAsString(), '12345678');
      await client.delete(b);
      ids.remove(b);
      expect(await scan.snapshot(), isNull);
      binding.reportData = {
        'logicalDelta': page.logicalDelta,
        'counts': page.counts,
        'targetFilesPreserved': true,
      };
    } finally {
      for (final id in ids) {
        await client.retain(id, false);
        await client.delete(id);
      }
      await fixture.delete(recursive: true);
    }
  });
}
