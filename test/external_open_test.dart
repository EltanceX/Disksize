import 'dart:async';

import 'package:disksize/features/explorer/explorer_detail.dart';
import 'package:disksize/features/explorer/explorer_page.dart';
import 'package:disksize/features/explorer/external_open_button.dart';
import 'package:disksize/platform/entry_opener.dart';
import 'package:disksize/platform/index_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'explorer_test.dart' show ExplorerFixture;
import 'treemap_test.dart' show painter;

class RecordingOpener implements EntryOpener {
  final calls = <(String, int)>[];
  Completer<void>? pending;
  @override
  Future<void> open(String sessionId, int nodeId) async {
    calls.add((sessionId, nodeId));
    await pending?.future;
  }
}

void main() {
  testWidgets(
    'treemap opens the selected directory and file without navigating',
    (tester) async {
      final client = ExplorerFixture();
      final opener = RecordingOpener();
      await tester.pumpWidget(
        MaterialApp(
          home: ExplorerPage(
            client: client,
            sessionId: 'fixture',
            opener: opener,
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('树图'));
      await tester.pumpAndSettle();
      final canvas = find.byKey(const Key('treemap-canvas'));
      for (final id in [2, 3]) {
        await tester.ensureVisible(canvas);
        await tester.pumpAndSettle();
        final tile = painter(tester).tiles
            .firstWhere((tile) => tile.item.id == id);
        await tester.tapAt(tester.getTopLeft(canvas) + tile.hitRect.center);
        await tester.pump();
        expect(painter(tester).selected, id);
        final button = find.byKey(const Key('treemap-open-external'));
        await tester.ensureVisible(button);
        await tester.pumpAndSettle();
        await tester.tap(button);
        await tester.pumpAndSettle();
        expect(opener.calls.last, ('fixture', id));
        expect(painter(tester).rootId, 1);
      }
      await tester.pumpWidget(const SizedBox());
    },
  );

  for (final id in [2, 3]) {
    testWidgets('detail opens indexed entry $id with duplicate taps disabled', (
      tester,
    ) async {
      final opener = RecordingOpener()..pending = Completer<void>();
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ExplorerDetail(
              client: ExplorerFixture(),
              sessionId: 'fixture',
              nodeId: id,
              onLocate: (_) {},
              opener: opener,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final button = find.byKey(const Key('open-indexed-entry'));
      await tester.ensureVisible(button);
      await tester.tap(button);
      await tester.pump();
      await tester.tap(button);
      expect(opener.calls, [('fixture', id)]);
      expect(find.text('正在打开…'), findsOneWidget);
      opener.pending!.completeError(
        PlatformException(code: 'no-handler', message: '没有可打开此项目的应用。'),
      );
      await tester.pumpAndSettle();
      expect(find.text('没有可打开此项目的应用。'), findsOneWidget);
      expect(find.text('使用其他应用打开'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    });
  }

  testWidgets('symbolic links and special files do not offer external open', (
    tester,
  ) async {
    for (final kind in ['symlink', 'special']) {
      final client = ExplorerFixture();
      client.intercept = (r) => r.kind == IndexQueryKind.detail
          ? Future.value(
              IndexPage.fromJson({
                ...client.metadata(),
                'detail': {...client.entry(3), 'kind': kind},
              }),
            )
          : null;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ExplorerDetail(
              key: ValueKey(kind),
              client: client,
              sessionId: 'fixture',
              nodeId: 3,
              onLocate: (_) {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(ExternalOpenButton), findsNothing);
      expect(find.byKey(const Key('copy-indexed-path')), findsOneWidget);
    }
  });
}
