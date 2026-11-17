import 'dart:io';

import 'package:disksize/features/explorer/explorer_page.dart';
import 'package:disksize/features/explorer/treemap_panel.dart';
import 'package:disksize/platform/index_client.dart';
import 'package:disksize/platform/scan_client.dart';
import 'package:disksize/platform/snapshot_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'selected entries launch Android open-with and preserve browsing',
    (tester) async {
      final scan = AndroidScanClient();
      final index = AndroidIndexClient();
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: Text('Preparing external open test')),
        ),
      );
      await tester.pumpAndSettle();
      final access = await scan.accessStatus();
      final root = await Directory(access.ownPath).createTemp('disksize-open-');
      String? sessionId;
      try {
        final folder = await Directory('${root.path}/Folder').create();
        await File('${folder.path}/inside.txt').writeAsString('inside');
        await File('${root.path}/sample.txt').writeAsString('sample file');
        sessionId = await scan.start(root.path, 'own');
        final deadline = DateTime.now().add(const Duration(seconds: 30));
        while ((await scan.snapshot())!.active) {
          if (DateTime.now().isAfter(deadline)) {
            throw StateError('Scan timed out');
          }
          await Future<void>.delayed(const Duration(milliseconds: 50));
        }
        await tester.pumpWidget(
          MaterialApp(
            home: ExplorerPage(client: index, sessionId: sessionId),
          ),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.text('树图'));
        await tester.pumpAndSettle();
        final canvas = find.byKey(const Key('treemap-canvas'));
        TreemapPainter painter() =>
            tester
                    .widget<CustomPaint>(
                      find
                          .descendant(
                            of: canvas,
                            matching: find.byType(CustomPaint),
                          )
                          .first,
                    )
                    .painter!
                as TreemapPainter;
        for (final kind in ['directory', 'regular']) {
          await tester.ensureVisible(canvas);
          await tester.pumpAndSettle();
          final tile = painter().tiles.firstWhere(
            (tile) =>
                tile.level == 1 &&
                painter().entries[tile.item.id]?.kind == kind,
          );
          await tester.tapAt(tester.getTopLeft(canvas) + tile.hitRect.center);
          await tester.pumpAndSettle();
          final button = find.byKey(const Key('treemap-open-external'));
          await tester.ensureVisible(button);
          await tester.pumpAndSettle();
          final offset = tester
              .widget<CustomScrollView>(find.byType(CustomScrollView))
              .controller!
              .offset;
          final ack = File('${access.externalOwnPath!}/open-$kind.done');
          if (await ack.exists()) await ack.delete();
          await tester.tap(button);
          await tester.pump();
          // Host checks the actual chooser, then dismisses it and acknowledges.
          debugPrint('EXTERNAL_OPEN_${kind.toUpperCase()}_READY');
          final timeout = DateTime.now().add(const Duration(seconds: 30));
          while (!await ack.exists() ||
              binding.lifecycleState != AppLifecycleState.resumed) {
            if (DateTime.now().isAfter(timeout)) {
              throw StateError('Host did not verify the $kind chooser');
            }
            await Future<void>.delayed(const Duration(milliseconds: 100));
          }
          await tester.pumpAndSettle();
          expect(painter().selected, tile.item.id);
          expect(
            tester
                .widget<CustomScrollView>(find.byType(CustomScrollView))
                .controller!
                .offset,
            closeTo(offset, .01),
          );
          expect(
            find.descendant(of: button, matching: find.text('使用其他应用打开')),
            findsOneWidget,
          );
          await ack.delete();
        }
        await binding.convertFlutterSurfaceToImage();
        await tester.pumpAndSettle();
        await File('${access.externalOwnPath!}/external-open.png')
            .writeAsBytes(await binding.takeScreenshot('external-open'));
        binding.reportData = {
          'directoryChooser': true,
          'fileChooser': true,
          'selectionAndScrollPreserved': true,
        };
      } finally {
        await tester.pumpWidget(const SizedBox());
        if (sessionId != null) await AndroidSnapshotClient().delete(sessionId);
        await root.delete(recursive: true);
      }
    },
  );
}
