import 'dart:io';
import 'dart:ui';

import 'package:disksize/features/explorer/explorer_page.dart';
import 'package:disksize/features/explorer/treemap_layout.dart';
import 'package:disksize/features/explorer/treemap_panel.dart';
import 'package:disksize/platform/index_client.dart';
import 'package:disksize/platform/scan_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('bounded real-index treemap, navigation and profile frames', (
    tester,
  ) async {
    final scan = AndroidScanClient();
    final index = AndroidIndexClient();
    await tester.pumpWidget(
      const MaterialApp(home: Scaffold(body: Text('Preparing treemap'))),
    );
    final access = await scan.accessStatus();
    final fixture = await Directory(access.ownPath)
        .createTemp('disksize-phase7-');
    final frames = <FrameTiming>[];
    void record(List<FrameTiming> timings) => frames.addAll(timings);
    try {
      for (final item in [
        ('Photos', 6, 524288),
        ('Cache', 80, 16384),
        ('Documents', 20, 32768),
      ]) {
        final directory = await Directory('${fixture.path}/${item.$1}')
            .create();
        for (var i = 0; i < item.$2; i++) {
          await File('${directory.path}/sample-$i')
              .writeAsBytes(List.filled(item.$3, 7));
        }
      }
      for (var i = 0; i < 205; i++) {
        await File('${fixture.path}/small-$i')
            .writeAsBytes(List.filled(i.isEven ? 4096 : 0, 0));
      }
      final stress = await Directory('${fixture.path}/Stress').create();
      for (var i = 0; i < 120; i++) {
        await File('${stress.path}/tile-$i').writeAsBytes(List.filled(4096, 1));
      }
      final sparse = await File('${fixture.path}/sparse-64MiB')
          .open(mode: FileMode.write);
      await sparse.truncate(64 * 1024 * 1024);
      await sparse.close();
      final id = await scan.start(fixture.path, 'own');
      final deadline = DateTime.now().add(const Duration(seconds: 45));
      while ((await scan.snapshot())!.active) {
        if (DateTime.now().isAfter(deadline)) {
          throw StateError('fixture scan timed out');
        }
        await Future<void>.delayed(const Duration(milliseconds: 50));
      }
      expect((await scan.snapshot())!.state, 'completed');
      final queryTime = Stopwatch()..start();
      final map = await index.query(
        IndexRequest(sessionId: id, kind: IndexQueryKind.treemap, parentId: 1),
      );
      queryTime.stop();
      expect(map.entries.length, 100);
      expect(map.treemap!.childCount, 210);
      expect(map.treemap!.otherBytes, greaterThan(0));
      final weights = treemapWeights(
        map.entries,
        map.treemap!.totalBytes,
        false,
        const Size(960, 520),
      );
      final layoutTime = Stopwatch()..start();
      for (var i = 0; i < 1000; i++) {
        squarify(weights, const Size(960, 520));
      }
      layoutTime.stop();
      final fullWeights = List.generate(100, (i) => TreemapWeight(i, 4096));
      final fullLayoutTime = Stopwatch()..start();
      for (var i = 0; i < 1000; i++) {
        squarify(fullWeights, const Size(960, 520));
      }
      fullLayoutTime.stop();
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
      Future<void> reveal(Finder finder) async {
        await tester.ensureVisible(finder);
        await tester.pumpAndSettle();
      }

      TreemapPainter painter() =>
          tester
                  .widget<CustomPaint>(
                    find
                        .descendant(
                          of: find.byKey(const Key('treemap-canvas')),
                          matching: find.byType(CustomPaint),
                        )
                        .first,
                  )
                  .painter!
              as TreemapPainter;
      await tester.pumpAndSettle();
      await reveal(find.text('树图'));
      await tester.tap(find.text('树图'));
      await tester.pumpAndSettle();
      await reveal(find.text('报告占用').first);
      await tester.tap(find.text('报告占用').first);
      await tester.pumpAndSettle();
      await reveal(find.byKey(const Key('treemap-canvas')));
      final folder = painter().tiles.firstWhere(
        (tile) => painter().entries[tile.item.id]?.kind == 'directory',
      );
      await tester.tapAt(
        tester.getTopLeft(find.byKey(const Key('treemap-canvas'))) +
            folder.rect.center,
      );
      await tester.pumpAndSettle();
      await reveal(find.byKey(const Key('treemap-open')));
      await tester.tap(find.byKey(const Key('treemap-open')));
      await tester.pumpAndSettle();
      expect(find.byKey(ValueKey('crumb-${folder.item.id}')), findsOneWidget);
      await tester.tap(find.byType(BackButton).first);
      await tester.pumpAndSettle();
      await reveal(find.byKey(const Key('treemap-canvas')));
      final stressTile = painter().tiles.firstWhere((tile) {
        final entry = painter().entries[tile.item.id];
        return entry != null && displayScanPath(entry.nameHex) == 'Stress';
      });
      await tester.tapAt(
        tester.getTopLeft(find.byKey(const Key('treemap-canvas'))) +
            stressTile.rect.center,
      );
      await tester.pumpAndSettle();
      await reveal(find.byKey(const Key('treemap-open')));
      await tester.tap(find.byKey(const Key('treemap-open')));
      await tester.pumpAndSettle();
      await reveal(find.byKey(const Key('treemap-canvas')));
      expect(painter().tiles.length, 101);
      SchedulerBinding.instance.addTimingsCallback(record);
      for (var i = 0; i < 40; i++) {
        final tile = painter().tiles[i % 2];
        await tester.tapAt(
          tester.getTopLeft(find.byKey(const Key('treemap-canvas'))) +
              tile.rect.center,
        );
        await tester.pump();
        await Future<void>.delayed(const Duration(milliseconds: 25));
      }
      await tester.pumpAndSettle();
      await Future<void>.delayed(const Duration(milliseconds: 300));
      SchedulerBinding.instance.removeTimingsCallback(record);
      expect(frames.length, greaterThanOrEqualTo(10));
      int percentile(Iterable<int> input, double percentile) {
        final samples = input.toList()..sort();
        return samples[((samples.length - 1) * percentile).round()];
      }

      binding.reportData = {
        'sessionId': id,
        'frames': frames.length,
        'buildP95Micros': percentile(
          frames.map((e) => e.buildDuration.inMicroseconds),
          .95,
        ),
        'rasterP95Micros': percentile(
          frames.map((e) => e.rasterDuration.inMicroseconds),
          .95,
        ),
        'totalP95Micros': percentile(
          frames.map((e) => e.totalSpan.inMicroseconds),
          .95,
        ),
        'queryMicros': queryTime.elapsedMicroseconds,
        'layoutMeanMicros': layoutTime.elapsedMicroseconds / 1000,
        'layout100MeanMicros': fullLayoutTime.elapsedMicroseconds / 1000,
        'rectangles': painter().tiles.length,
        'queryEntries': map.entries.length,
        'childCount': map.treemap!.childCount,
      };
      await binding.convertFlutterSurfaceToImage();
      await tester.pumpAndSettle();
      await File('${access.externalOwnPath!}/phase7-treemap.png')
          .writeAsBytes(await binding.takeScreenshot('phase7-treemap'));
      binding.reportData?.remove('screenshots');
      debugPrint('PHASE7_TREEMAP_OK ${binding.reportData}');
      expect(tester.takeException(), isNull);
    } finally {
      SchedulerBinding.instance.removeTimingsCallback(record);
      await tester.pumpWidget(const SizedBox());
      await fixture.delete(recursive: true);
    }
  });
}
