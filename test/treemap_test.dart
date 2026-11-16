import 'dart:async';
import 'dart:math';

import 'package:disksize/features/explorer/explorer_controller.dart';
import 'package:disksize/features/explorer/explorer_page.dart';
import 'package:disksize/features/explorer/treemap_layout.dart';
import 'package:disksize/features/explorer/treemap_panel.dart';
import 'package:disksize/platform/index_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'explorer_test.dart' show ExplorerFixture;

TreemapPainter painter(WidgetTester tester) =>
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

void main() {
  test(
    'squarified rectangles preserve area, boundaries, and hit detection',
    () {
      final random = Random(7);
      for (final size in [
        const Size(360, 280),
        const Size(1100, 520),
        const Size(47, 900),
      ]) {
        for (var count = 1; count <= 100; count += 11) {
          final items = List.generate(
            count,
            (i) => TreemapWeight(i, random.nextInt(100000) + 1),
          );
          final total = items.fold(0, (sum, e) => sum + e.bytes);
          final tiles = squarify(items, size);
          expect(tiles.length, count);
          var area = 0.0;
          for (var i = 0; i < tiles.length; i++) {
            final tile = tiles[i];
            expect(tile.rect.left, greaterThanOrEqualTo(-1e-7));
            expect(tile.rect.top, greaterThanOrEqualTo(-1e-7));
            expect(tile.rect.right, lessThanOrEqualTo(size.width + 1e-7));
            expect(tile.rect.bottom, lessThanOrEqualTo(size.height + 1e-7));
            final tileArea = tile.rect.width * tile.rect.height;
            expect(
              tileArea,
              closeTo(size.width * size.height * tile.item.bytes / total, 1e-6),
            );
            expect(hitTreemap(tiles, tile.rect.center)?.item.id, tile.item.id);
            for (var j = 0; j < i; j++) {
              final overlap = tile.rect.intersect(tiles[j].rect);
              expect(overlap.width <= 1e-7 || overlap.height <= 1e-7, true);
            }
            area += tileArea;
          }
          expect(area, closeTo(size.width * size.height, 1e-6));
          expect(hitTreemap(tiles, const Offset(-1, -1)), isNull);
        }
      }
    },
  );

  test('pixel grouping, budgets and metric changes retain all known bytes', () {
    final f = ExplorerFixture();
    final entries = [
      IndexedEntry.fromJson({
        ...f.entry(2),
        'logicalBytes': 9000000000000000000,
        'allocatedBytes': 0,
      }),
      for (var i = 3; i < 10003; i++)
        IndexedEntry.fromJson({
          ...f.entry(i),
          'logicalBytes': 1,
          'allocatedBytes': 1,
        }),
    ];
    final large = treemapWeights(
      entries,
      9000000000000010000,
      false,
      const Size(800, 400),
    );
    expect(large.map((e) => e.id), [2, -1]);
    expect(large.fold(0, (sum, e) => sum + e.bytes), 9000000000000010000);
    final allocated = treemapWeights(
      entries,
      14096,
      true,
      const Size(800, 400),
    );
    expect(allocated.single.id, -1);
    expect(
      allocated.single.bytes,
      14096,
    ); // Includes directory blocks and omitted children.
    expect(
      squarify(large, const Size(800, 400)).every((e) => e.rect.isFinite),
      true,
    );
    expect(treemapWeights(entries, 0, false, const Size(360, 280)), isEmpty);
    expect(squarify(large, Size.zero), isEmpty);
  });

  test(
    'late map responses cannot replace a directory list or a newer revision',
    () async {
      final f = ExplorerFixture();
      final pending = Completer<IndexPage>();
      f.intercept = (r) =>
          r.kind == IndexQueryKind.treemap ? pending.future : null;
      final c = ExplorerController(f, 'fixture');
      final old = c.setTreemap();
      await c.setLargeFiles(false);
      pending.complete(
        f.response(
          const IndexRequest(
            sessionId: 'fixture',
            kind: IndexQueryKind.treemap,
          ),
        ),
      );
      await old;
      expect(c.treemap, false);
      expect(c.page!.treemap, isNull);
      f.intercept = (r) => r.kind == IndexQueryKind.treemap
          ? Future.value(IndexPage.fromJson({...f.metadata(), 'revision': 2}))
          : null;
      await c.setTreemap();
      expect(c.page, isNull);
      expect(c.message, contains('更新'));
      c.dispose();
    },
  );

  testWidgets('map selection, metric ordering, drilldown, details and back', (
    tester,
  ) async {
    final f = ExplorerFixture();
    await tester.pumpWidget(
      MaterialApp(
        home: ExplorerPage(client: f, sessionId: 'fixture'),
      ),
    );
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('树图'));
    await tester.tap(find.text('树图'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('treemap-canvas')));
    await tester.pumpAndSettle();
    final tile = painter(tester).tiles.firstWhere((e) => e.item.id == 2);
    await tester.tapAt(
      tester.getTopLeft(find.byKey(const Key('treemap-canvas'))) +
          tile.rect.center,
    );
    await tester.pumpAndSettle();
    expect(painter(tester).selected, 2);
    await tester.ensureVisible(find.byKey(const Key('treemap-open')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('treemap-open')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('crumb-2')), findsOneWidget);
    await tester.ensureVisible(find.text('报告占用').first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('报告占用').first);
    await tester.pumpAndSettle();
    expect(
      f.requests.any(
        (r) =>
            r.kind == IndexQueryKind.treemap &&
            r.parentId == 2 &&
            r.sort == IndexSort.allocated,
      ),
      true,
    );
    await tester.ensureVisible(find.byKey(const Key('treemap-canvas')));
    await tester.pumpAndSettle();
    final child = painter(tester).tiles.firstWhere((e) => e.item.id == 4);
    await tester.tapAt(
      tester.getTopLeft(find.byKey(const Key('treemap-canvas'))) +
          child.rect.center,
    );
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('treemap-detail')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('treemap-detail')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('copy-indexed-path')), findsOneWidget);
    await tester.tap(find.byTooltip('关闭详情'));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(BackButton).first);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('crumb-2')), findsNothing);
    expect(find.byKey(const Key('next-page')), findsNothing);
    await tester.ensureVisible(find.byKey(const Key('treemap-list')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('treemap-list')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('next-page')), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  for (final state in ['provisional', 'zero', 'partial', 'expired']) {
    testWidgets('tree state is explicit: $state', (tester) async {
      final f = ExplorerFixture();
      f.intercept = (r) {
        if (state == 'expired') {
          return Future.value(IndexPage.fromJson({'status': 'not-found'}));
        }
        if (r.kind != IndexQueryKind.treemap) return null;
        return Future.value(
          IndexPage.fromJson({
            ...f.metadata(),
            'provisional': state == 'provisional',
            'treemap': {
              'totalBytes': state == 'zero' ? 0 : 8192,
              'otherBytes': 8192,
              'childCount': 2,
              'zeroCount': 1,
              'unknownCount': state == 'partial' ? 1 : 0,
            },
          }),
        );
      };
      await tester.pumpWidget(
        MaterialApp(
          home: ExplorerPage(client: f, sessionId: 'fixture'),
        ),
      );
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('树图'));
      await tester.tap(find.text('树图'));
      await tester.pumpAndSettle();
      final needle = switch (state) {
        'provisional' => '暂不绘制',
        'zero' => '没有可绘制',
        'partial' => '面积仅表示已知',
        _ => '已不可用',
      };
      expect(find.textContaining(needle), findsWidgets);
      if (state != 'partial') {
        expect(find.byKey(const Key('treemap-canvas')), findsNothing);
      }
      await tester.pumpWidget(const SizedBox());
    });
  }

  for (final config in [
    (const Size(360, 800), 2.0),
    (const Size(1280, 900), 1.0),
  ]) {
    testWidgets('map fits ${config.$1} with text scale ${config.$2}', (
      tester,
    ) async {
      await tester.binding.setSurfaceSize(config.$1);
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(
        MaterialApp(
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(config.$2)),
            child: child!,
          ),
          home: ExplorerPage(client: ExplorerFixture(), sessionId: 'fixture'),
        ),
      );
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('树图'));
      await tester.tap(find.text('树图'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const Key('treemap-canvas')));
      await tester.pumpAndSettle();
      expect(painter(tester).tiles.length, lessThanOrEqualTo(101));
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    });
  }
}
