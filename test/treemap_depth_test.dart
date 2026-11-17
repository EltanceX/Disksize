import 'dart:async';

import 'package:disksize/features/explorer/explorer_controller.dart';
import 'package:disksize/features/explorer/explorer_page.dart';
import 'package:disksize/features/explorer/treemap_layout.dart';
import 'package:disksize/features/explorer/treemap_panel.dart';
import 'package:disksize/platform/index_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'explorer_test.dart' show ExplorerFixture, hex;
import 'treemap_test.dart' show painter;

class DepthFixture extends ExplorerFixture {
  @override
  Map<String, dynamic> entry(int id) => {
    ...super.entry(id),
    'parentId': switch (id) {
      1 => 0,
      2 || 6 => 1,
      3 || 4 => 2,
      _ => 4,
    },
    'nameHex': hex(switch (id) {
      1 => '',
      2 => 'Apps',
      3 => 'file',
      4 => 'Cache',
      5 => 'deep-file',
      _ => 'root-file',
    }),
    'kind': [1, 2, 4].contains(id) ? 'directory' : 'regular',
    'logicalBytes': switch (id) {
      1 => 100000,
      2 => 80000,
      3 => 30000,
      4 || 5 => 50000,
      _ => 20000,
    },
    'allocatedBytes': switch (id) {
      1 => 50000,
      2 => 45000,
      3 => 35000,
      4 || 5 => 10000,
      _ => 5000,
    },
  };
  @override
  IndexPage response(IndexRequest request) {
    if (request.kind != IndexQueryKind.treemap) {
      if (request.kind == IndexQueryKind.detail) {
        return IndexPage.fromJson({
          ...metadata(),
          'detail': entry(request.nodeId),
        });
      }
      return super.response(request);
    }
    final allocated = request.sort == IndexSort.allocated;
    final key = allocated ? 'allocatedBytes' : 'logicalBytes';
    return IndexPage.fromJson({
      ...metadata(),
      'entries': request.depth == 0 ? [] : [entry(2), entry(6)],
      'treemap': {
        'totalBytes': entry(1)[key],
        'otherBytes': 0,
        'childCount': 2,
        'zeroCount': 0,
        'unknownCount': 0,
        'depth': request.depth,
        'groups': [
          if (request.depth >= 2)
            {
              'parentId': 2,
              'totalBytes': entry(2)[key],
              'childCount': 2,
              'entries': [entry(3), entry(4)],
            },
          if (request.depth >= 3)
            {
              'parentId': 4,
              'totalBytes': entry(4)[key],
              'childCount': 1,
              'entries': [entry(5)],
            },
        ],
      },
    });
  }
}

void main() {
  test('nested areas, title bands and deepest hit follow selected depth', () {
    final fixture = DepthFixture();
    final page = fixture.response(
      const IndexRequest(sessionId: 'fixture', kind: IndexQueryKind.treemap),
    );
    for (var depth = 0; depth <= 6; depth++) {
      final tiles = nestedTreemap(
        root: IndexedEntry.fromJson(fixture.entry(1)),
        children: page.entries,
        groups: page.treemap!.groups,
        total: 100000,
        depth: depth,
        allocated: false,
        size: const Size(900, 520),
        headerHeight: 28,
        minimumArea: 0,
      );
      expect(tiles.first.level, 0);
      expect(tiles.every((tile) => tile.level <= depth), isTrue);
      expect(tiles.length, lessThanOrEqualTo(treemapRectangleLimit));
      if (depth == 0) {
        expect(tiles.single.item.id, 1);
        continue;
      }
      for (final parent in tiles.where((tile) => tile.expanded)) {
        final children = tiles
            .where((tile) => tile.parentId == parent.item.id)
            .toList();
        final content = Rect.fromLTRB(
          parent.rect.left + 2,
          parent.rect.top + 28,
          parent.rect.right - 2,
          parent.rect.bottom - 2,
        );
        var totalArea = 0.0;
        for (final child in children) {
          final area = child.rect.width * child.rect.height;
          expect(
            area,
            closeTo(
              content.width *
                  content.height *
                  child.item.bytes /
                  parent.item.bytes,
              1e-6,
            ),
          );
          expect(child.rect.intersect(content), child.rect);
          totalArea += area;
        }
        expect(totalArea, closeTo(content.width * content.height, 1e-6));
        expect(
          hitTreemap(tiles, parent.hitRect.center)?.item.id,
          parent.item.id,
        );
      }
      if (depth >= 3) {
        final leaf = tiles.firstWhere((tile) => tile.item.id == 5);
        expect(leaf.level, 3);
        expect(hitTreemap(tiles, leaf.rect.center)?.item.id, 5);
      }
    }
  });
  test(
    'each branch remainder keeps omitted bytes and has a distinct hit ID',
    () {
      final fixture = DepthFixture();
      final page = fixture.response(
        const IndexRequest(sessionId: 'fixture', kind: IndexQueryKind.treemap),
      );
      final group = TreemapGroup.fromJson({
        'parentId': 2,
        'totalBytes': 80000,
        'childCount': 2,
        'entries': [fixture.entry(3)],
      });
      final tiles = nestedTreemap(
        root: IndexedEntry.fromJson(fixture.entry(1)),
        children: [page.entries.first],
        groups: [group],
        total: 100000,
        depth: 3,
        allocated: false,
        size: const Size(900, 520),
        headerHeight: 28,
        minimumArea: 0,
      );
      expect(
        tiles
            .where((tile) => tile.item.grouped)
            .map((tile) => tile.item.id)
            .toSet(),
        {-1, -2},
      );
      expect(tiles.firstWhere((tile) => tile.item.id == -1).item.bytes, 20000);
      expect(tiles.firstWhere((tile) => tile.item.id == -2).item.bytes, 50000);
    },
  );
  test('late depth response cannot replace a newer depth or metric', () async {
    final fixture = DepthFixture();
    final controller = ExplorerController(fixture, 'fixture');
    expect(controller.treemapDepth, 3);
    await controller.setTreemap();
    final pending = Completer<IndexPage>();
    fixture.intercept = (request) =>
        request.kind == IndexQueryKind.treemap && request.depth == 6
        ? pending.future
        : null;
    final old = controller.setTreemapDepth(6);
    await controller.setTreemapDepth(1);
    pending.complete(
      fixture.response(
        const IndexRequest(
          sessionId: 'fixture',
          kind: IndexQueryKind.treemap,
          depth: 6,
        ),
      ),
    );
    await old;
    expect(controller.treemapDepth, 1);
    expect(controller.page!.treemap!.depth, 1);
    await controller.setMetric(true);
    expect(controller.page!.treemap!.totalBytes, 50000);
    expect(fixture.requests.last.depth, 1);
    controller.dispose();
  });
  for (final config in [
    (const Size(360, 800), 2.0),
    (const Size(1280, 900), 1.0),
  ]) {
    testWidgets('default level three and depth selection fit ${config.$1}', (
      tester,
    ) async {
      await tester.binding.setSurfaceSize(config.$1);
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final fixture = DepthFixture();
      await tester.pumpWidget(
        MaterialApp(
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(config.$2)),
            child: child!,
          ),
          home: ExplorerPage(client: fixture, sessionId: 'fixture'),
        ),
      );
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('树图'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('树图'));
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<DropdownButton<int>>(find.byKey(const Key('treemap-depth')))
            .value,
        3,
      );
      await tester.ensureVisible(find.byKey(const Key('treemap-canvas')));
      await tester.pumpAndSettle();
      expect(painter(tester).tiles.any((tile) => tile.level == 3), isTrue);
      final leaf = painter(tester).tiles
          .firstWhere((tile) => tile.item.id == 5);
      await tester.tapAt(
        tester.getTopLeft(find.byKey(const Key('treemap-canvas'))) +
            leaf.rect.center,
      );
      await tester.pumpAndSettle();
      expect(painter(tester).selected, 5);
      await tester.scrollUntilVisible(
        find.byKey(const Key('treemap-depth')),
        -200,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('treemap-depth')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('显示到级别 0').last);
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const Key('treemap-canvas')));
      await tester.pumpAndSettle();
      expect(painter(tester).tiles.single.level, 0);
      expect(painter(tester).selected, isNull);
      expect(
        painter(tester).label(painter(tester).tiles.single),
        '/data/fixture',
      );
      await tester.tapAt(
        tester.getTopLeft(find.byKey(const Key('treemap-canvas'))) +
            painter(tester).tiles.single.rect.center,
      );
      await tester.pumpAndSettle();
      expect(painter(tester).selected, 1);
      expect(
        find.descendant(
          of: find.byType(TreemapPanel),
          matching: find.text('/data/fixture'),
        ),
        findsOneWidget,
      );
      expect(find.byKey(const Key('treemap-open')), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    });
  }
}
