import 'dart:convert';
import 'dart:io';

import 'package:disksize/features/explorer/explorer_page.dart';
import 'package:disksize/platform/index_client.dart';
import 'package:disksize/platform/scan_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'real index browsing, scope, pagination, details and volume capture',
    (tester) async {
      final scan = AndroidScanClient();
      final index = AndroidIndexClient();
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: Text('Preparing explorer fixture')),
        ),
      );
      final access = await scan.accessStatus();
      final root = await Directory(access.ownPath)
          .createTemp('disksize-phase6-');
      final nested = await Directory('${root.path}/00-nested').create();
      try {
        for (var i = 0; i < 55; i++) {
          await File('${root.path}/file-${i.toString().padLeft(2, '0')}')
              .writeAsString('fixture');
        }
        final childPath = '${nested.path}/child\nname';
        await File(childPath).writeAsString('nested');
        final id = await scan.start(root.path, 'own');
        final deadline = DateTime.now().add(const Duration(seconds: 30));
        while ((await scan.snapshot())!.active) {
          if (DateTime.now().isAfter(deadline)) {
            throw StateError('scan timed out');
          }
          await Future<void>.delayed(const Duration(milliseconds: 50));
        }
        final session = await index.query(
          IndexRequest(sessionId: id, kind: IndexQueryKind.session),
        );
        expect(session.state, 'completed');
        expect(session.session.volume.valid, true);
        expect((await index.recent()).any((s) => s.id == id), true);
        expect(
          (await index.volumes()).any((v) => v.path == access.sharedPath),
          true,
        );
        final children = await index.query(
          IndexRequest(
            sessionId: id,
            kind: IndexQueryKind.children,
            parentId: 1,
            sort: IndexSort.name,
          ),
        );
        final folder = children.entries.first;
        expect(folder.kind, 'directory');
        final scoped = await index.query(
          IndexRequest(
            sessionId: id,
            kind: IndexQueryKind.largeFiles,
            parentId: folder.id,
          ),
        );
        expect(scoped.entries.length, 1);
        expect(scoped.entries.single.logicalBytes, 6);
        final child = scoped.entries.single;
        final chain = await index.query(
          IndexRequest(
            sessionId: id,
            kind: IndexQueryKind.ancestors,
            nodeId: child.id,
          ),
        );
        expect(chain.ancestors.map((a) => a.id), [1, folder.id, child.id]);

        await tester.pumpWidget(
          MaterialApp(
            debugShowCheckedModeBanner: false,
            theme: ThemeData(
              colorScheme: ColorScheme.fromSeed(
                seedColor: const Color(0xFF087F8C),
              ),
              useMaterial3: true,
            ),
            home: ExplorerPage(client: index, sessionId: id),
          ),
        );
        await tester.pumpAndSettle();
        Future<void> reveal(Finder target) async {
          await tester.scrollUntilVisible(
            target,
            200,
            scrollable: find
                .descendant(
                  of: find.byType(CustomScrollView),
                  matching: find.byType(Scrollable),
                )
                .first,
          );
          await tester.pumpAndSettle();
        }

        await reveal(find.byKey(const Key('next-page')));
        await tester.tap(find.byKey(const Key('next-page')));
        await tester.pumpAndSettle();
        expect(find.text('第 2 页 · 每页最多 50 项'), findsOneWidget);
        await tester.tap(find.byKey(const Key('previous-page')));
        await tester.pumpAndSettle();
        expect(find.text('第 1 页 · 每页最多 50 项'), findsOneWidget);
        await tester.drag(find.byType(CustomScrollView), const Offset(0, 2000));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('explorer-sort')));
        await tester.pumpAndSettle();
        await tester.tap(find.text('名称 ↑').last);
        await tester.pumpAndSettle();
        await reveal(find.byKey(ValueKey('entry-${folder.id}')));
        await tester.tap(find.byKey(ValueKey('entry-${folder.id}')));
        await tester.pumpAndSettle();
        await tester.tap(find.text('大文件'));
        await tester.pumpAndSettle();
        await reveal(find.byKey(ValueKey('entry-${child.id}')));
        await tester.tap(find.byKey(ValueKey('entry-${child.id}')));
        await tester.pumpAndSettle();
        expect(find.text(r'child\nname'), findsWidgets);
        await tester.tap(find.byKey(const Key('copy-indexed-path')));
        await tester.pumpAndSettle();
        expect(
          (await Clipboard.getData(Clipboard.kTextPlain))!.text,
          '${session.session.rootPath}/00-nested/child\nname',
        );
        if (find.byTooltip('关闭详情').evaluate().isNotEmpty) {
          await tester.tap(find.byTooltip('关闭详情'));
          await tester.pumpAndSettle();
        }
        if (const bool.fromEnvironment('PHASE6_CAPTURE')) {
          ScaffoldMessenger.of(tester.element(find.byType(ExplorerPage)))
              .hideCurrentSnackBar();
          await tester.pumpAndSettle();
          await binding.convertFlutterSurfaceToImage();
          await tester.pumpAndSettle();
          await File('${access.ownPath}/phase6-explorer.png')
              .writeAsBytes(await binding.takeScreenshot('phase6-explorer'));
          debugPrint('PHASE6_CAPTURE_READY');
          // The host captures this before Flutter removes its test installation.
          await Future<void>.delayed(const Duration(seconds: 10));
        }
        expect(tester.takeException(), isNull);
        debugPrint(
          'PHASE6_EXPLORER_OK session=$id child=${child.id} stats=${jsonEncode({'logical': child.logicalBytes, 'allocated': child.allocatedBytes})}',
        );
      } finally {
        await tester.pumpWidget(const SizedBox());
        await root.delete(recursive: true);
      }
    },
  );
}
