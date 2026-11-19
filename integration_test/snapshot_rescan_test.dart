import 'dart:convert';
import 'dart:io';

import 'package:disksize/features/explorer/explorer_page.dart';
import 'package:disksize/platform/index_client.dart';
import 'package:disksize/platform/scan_client.dart';
import 'package:disksize/platform/snapshot_client.dart';
import 'package:disksize/platform/snapshot_rescan_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

// Run only through verify-step5.ps1. Its Android test checks RESCAN_TEST_HOST
// before launching this entry point, and restores the normal APK/data afterward.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'snapshot folder UI commits a real rescan into the same isolated snapshot',
    (tester) async {
      if (!const bool.fromEnvironment('RESCAN_UI_TEST_HOST')) {
        throw StateError('Requires the isolated step 5 Android test runner');
      }
      final scan = AndroidScanClient();
      final index = AndroidIndexClient();
      final snapshots = AndroidSnapshotClient();
      final rescans = AndroidSnapshotRescanClient();
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: Text('Preparing isolated rescan UI test')),
        ),
      );
      final access = await scan.accessStatus();
      final fixture = await Directory(access.ownPath).createTemp('rescan-ui-');
      String? snapshotId, taskId;
      String hex(String value) => utf8
          .encode(value)
          .map((v) => v.toRadixString(16).padLeft(2, '0'))
          .join();
      Future<IndexPage> query(
        IndexQueryKind kind, {
        int parent = 1,
        int node = 0,
        int? revision,
      }) => index.query(
        IndexRequest(
          sessionId: snapshotId!,
          kind: kind,
          parentId: parent,
          nodeId: node,
          expectedRevision: revision,
        ),
      );
      Future<void> waitFor(bool Function() done) async {
        final deadline = DateTime.now().add(const Duration(seconds: 60));
        while (!done()) {
          if (DateTime.now().isAfter(deadline)) {
            throw StateError('UI task timed out');
          }
          await tester.pump(const Duration(milliseconds: 100));
        }
      }

      Future<void> reveal(Finder finder) async {
        if (finder.evaluate().isEmpty) {
          await tester.scrollUntilVisible(
            finder,
            250,
            scrollable: find
                .descendant(
                  of: find.byType(CustomScrollView),
                  matching: find.byType(Scrollable),
                )
                .first,
          );
        }
        await tester.ensureVisible(finder);
        await tester.pump();
      }

      try {
        final folder = await Directory('${fixture.path}/folder').create();
        await File('${folder.path}/old').writeAsString('old');
        await File('${fixture.path}/outside').writeAsString('outside');
        snapshotId = await scan.start(fixture.path, 'own');
        final deadline = DateTime.now().add(const Duration(seconds: 45));
        while ((await scan.snapshot())?.active == true) {
          if (DateTime.now().isAfter(deadline)) {
            throw StateError('Initial fixture scan timed out');
          }
          await tester.pump(const Duration(milliseconds: 50));
        }
        expect((await scan.snapshot())!.state, 'completed');
        final initial = await query(IndexQueryKind.session);
        final folderId = (await query(IndexQueryKind.children)).entries
            .singleWhere((e) => e.nameHex == hex('folder'))
            .id;
        final oldFileId = (await query(
          IndexQueryKind.children,
          parent: folderId,
        )).entries.single.id;
        await File('${folder.path}/old').delete();
        await File('${folder.path}/new').writeAsString('updated-content');
        await tester.pumpWidget(
          MaterialApp(
            home: ExplorerPage(
              client: index,
              sessionId: snapshotId,
              rescanClient: rescans,
            ),
          ),
        );
        await waitFor(
          () => find.byKey(const Key('directory-rescan')).evaluate().isNotEmpty,
        );
        await tester.pumpAndSettle();
        final menu = find.byKey(ValueKey('entry-menu-$folderId'));
        await reveal(menu);
        await tester.tap(menu);
        await tester.pumpAndSettle();
        await tester.tap(find.text('重新扫描此文件夹').last);
        await waitFor(
          () => find
              .byKey(const Key('rescan-confirmation'))
              .evaluate()
              .isNotEmpty,
        );
        await tester.pumpAndSettle();
        expect(find.text('${initial.session.rootPath}/folder'), findsOneWidget);
        expect(
          (await query(IndexQueryKind.session)).revision,
          initial.revision,
        );
        await tester.tap(find.byKey(const Key('rescan-confirm')));
        await waitFor(() => find.text('更新已提交').evaluate().isNotEmpty);
        final metadata = await query(IndexQueryKind.session);
        taskId = metadata.session.latestRescanTaskId;
        expect(metadata.revision, initial.revision + 1);
        expect(metadata.session.localUpdate.count, 1);
        expect(metadata.session.localUpdate.pathHex, hex('folder'));
        expect(
          metadata.session.startedAtMillis,
          initial.session.startedAtMillis,
        );
        expect(metadata.session.endedAtMillis, initial.session.endedAtMillis);
        expect((await rescans.task(taskId!))!.state, 'completed');
        expect(
          (await query(
            IndexQueryKind.children,
            parent: folderId,
          )).entries.single.nameHex,
          hex('new'),
        );
        expect(
          (await query(IndexQueryKind.detail, node: oldFileId)).status,
          'not-found',
        );
        expect(
          (await query(
            IndexQueryKind.detail,
            node: folderId,
            revision: initial.revision,
          )).status,
          'stale-cursor',
        );
        final list = await snapshots.list();
        expect(
          list.snapshots
              .where((s) => s.rootPath == initial.session.rootPath)
              .map((s) => s.id),
          [snapshotId],
        );
        final updated = list.snapshots.singleWhere((s) => s.id == snapshotId);
        expect(updated.localUpdate.count, 1);
        expect(updated.revision, metadata.revision);
        // Wide layouts also show the selected directory's detail summary.
        expect(find.textContaining('已局部更新'), findsWidgets);
        await tester.pumpWidget(
          const MaterialApp(home: Scaffold(body: Text('Reopening snapshot'))),
        );
        await tester.pumpWidget(
          MaterialApp(
            home: ExplorerPage(
              client: index,
              sessionId: snapshotId,
              rescanClient: rescans,
            ),
          ),
        );
        await waitFor(() => find.text('更新已提交').evaluate().isNotEmpty);
        await tester.pumpAndSettle();
        expect(find.text('更新已提交'), findsOneWidget);
        expect(
          (await query(IndexQueryKind.session)).revision,
          metadata.revision,
        );
      } finally {
        await tester.pumpWidget(
          const MaterialApp(
            home: Scaffold(body: Text('Cleaning isolated fixtures')),
          ),
        );
        if (snapshotId != null) {
          taskId ??= (await query(IndexQueryKind.session))
              .session
              .latestRescanTaskId;
          if (taskId != null) {
            await rescans.cancel(taskId);
            final deadline = DateTime.now().add(const Duration(seconds: 30));
            while ((await rescans.task(taskId))?.active == true) {
              if (DateTime.now().isAfter(deadline)) {
                throw StateError('Fixture rescan did not stop');
              }
              await tester.pump(const Duration(milliseconds: 50));
            }
          }
          await snapshots.delete(snapshotId);
        }
        // The temporary fixture is this run's direct child of the app's own path.
        if (await fixture.exists()) await fixture.delete(recursive: true);
      }
    },
  );
}
