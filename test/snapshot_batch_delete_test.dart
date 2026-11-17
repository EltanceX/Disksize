import 'dart:async';

import 'package:disksize/features/snapshots/snapshots_page.dart';
import 'package:disksize/platform/snapshot_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'snapshots_test.dart' show Fixture, snapshot;

class BatchFixture extends Fixture {
  final records = <String, Map<String, dynamic>>{
    for (final id in ['a', 'b', 'c', 'd'])
      id: {...snapshot(id, retained: id == 'c'), 'rootPath': '/fixture/$id'},
  };
  final deletedIds = <String>[];
  final cursors = <String?>[];
  String? failId;
  Completer<void>? pending;

  @override
  Future<SnapshotList> list({String? cursor}) async {
    cursors.add(cursor);
    final ids =
        records.keys
            .where((id) => cursor == null || id.compareTo(cursor) < 0)
            .toList()
          ..sort((a, b) => b.compareTo(a));
    return SnapshotList.fromJson({
      'snapshots': [for (final id in ids.take(2)) records[id]],
      if (ids.length > 2) 'nextCursor': ids[1],
    });
  }

  @override
  Future<void> delete(String id) async {
    await pending?.future;
    if (id == failId) throw StateError('连接中断，请重试');
    if (records[id]?['retained'] == true) throw StateError('请先取消保留再删除');
    records.remove(id);
    deletedIds.add(id);
  }
}

Future<void> startBatch(WidgetTester tester, BatchFixture fixture) async {
  await tester.pumpWidget(
    MaterialApp(
      home: SnapshotsPage(
        client: fixture,
        indexClient: fixture,
        initialBase: 'd',
      ),
    ),
  );
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('snapshot-batch-delete')));
  await tester.pumpAndSettle();
}

Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

Future<void> confirmDeletion(WidgetTester tester) async {
  await tapVisible(tester, find.byKey(const Key('snapshot-delete-selected')));
  await tester.tap(find.widgetWithText(FilledButton, '删除'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'batch defaults to all unretained pages and cancel does not delete',
    (tester) async {
      final f = BatchFixture();
      await startBatch(tester, f);
      final all = tester.widget<CheckboxListTile>(
        find.byKey(const Key('snapshot-select-all')),
      );
      final selected = tester.widget<CheckboxListTile>(
        find.byKey(const Key('snapshot-select-d')),
      );
      final retained = tester.widget<CheckboxListTile>(
        find.byKey(const Key('snapshot-select-c')),
      );
      expect(all.value, isTrue);
      expect(selected.value, isTrue);
      expect(retained.value, isFalse);
      expect(retained.onChanged, isNull);
      await tapVisible(
        tester,
        find.byKey(const Key('snapshot-delete-selected')),
      );
      expect(find.textContaining('含更早快照'), findsWidgets);
      expect(f.deletedIds, isEmpty);
      await tester.tap(find.widgetWithText(TextButton, '取消'));
      await tester.pumpAndSettle();
      expect(f.deletedIds, isEmpty);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('snapshot-batch-delete')), findsOneWidget);
      await tapVisible(tester, find.byKey(const Key('snapshot-batch-delete')));
      await confirmDeletion(tester);
      expect(f.deletedIds, ['d', 'b', 'a']);
      expect(f.records.keys, ['c']);
      expect(f.cursors, contains('c'));
      expect(find.textContaining('基准：'), findsNothing);
      expect(find.text('已删除 3 份快照，原文件不受影响。'), findsOneWidget);
    },
  );

  testWidgets('retention changes after selection are checked before deleting', (
    tester,
  ) async {
    final f = BatchFixture();
    await startBatch(tester, f);
    f.records['d']!['retained'] = true;
    await confirmDeletion(tester);
    expect(f.deletedIds, ['b', 'a']);
    expect(f.records.keys.toSet(), {'c', 'd'});
    expect(find.textContaining('基准：'), findsOneWidget);
  });

  testWidgets('deselections persist across pages and exclude older snapshots', (
    tester,
  ) async {
    final f = BatchFixture();
    await startBatch(tester, f);
    await tapVisible(tester, find.byKey(const Key('snapshot-select-d')));
    await tapVisible(tester, find.text('更早快照'));
    await tapVisible(tester, find.byKey(const Key('snapshot-select-b')));
    await tapVisible(tester, find.text('回到最新'));
    await tester.scrollUntilVisible(
      find.byKey(const Key('snapshot-select-d')),
      -300,
    );
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<CheckboxListTile>(find.byKey(const Key('snapshot-select-d')))
          .value,
      isFalse,
    );
    await tester.scrollUntilVisible(
      find.byKey(const Key('snapshot-select-all')),
      -300,
    );
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<CheckboxListTile>(
            find.byKey(const Key('snapshot-select-all')),
          )
          .value,
      isNull,
    );
    await confirmDeletion(tester);
    expect(f.deletedIds, ['a']);
    expect(f.records.keys.toSet(), {'b', 'c', 'd'});
    expect(find.textContaining('基准：'), findsOneWidget);
  });

  testWidgets(
    'clear all disables deletion and individual selection spans pages',
    (tester) async {
      final f = BatchFixture();
      await startBatch(tester, f);
      await tapVisible(tester, find.byKey(const Key('snapshot-select-all')));
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const Key('snapshot-delete-selected')),
            )
            .onPressed,
        isNull,
      );
      await tapVisible(tester, find.byKey(const Key('snapshot-select-d')));
      await tapVisible(tester, find.text('更早快照'));
      await tapVisible(tester, find.byKey(const Key('snapshot-select-a')));
      await confirmDeletion(tester);
      expect(f.deletedIds, ['d', 'a']);
      expect(f.records.keys.toSet(), {'b', 'c'});
    },
  );

  testWidgets(
    'partial failure reports completed deletions and retry handles the rest',
    (tester) async {
      final f = BatchFixture()..failId = 'b';
      await startBatch(tester, f);
      await confirmDeletion(tester);
      expect(f.deletedIds, ['d']);
      expect(find.textContaining('已删除 1 份快照，剩余操作未完成'), findsOneWidget);
      expect(find.textContaining('基准：'), findsNothing);
      expect(f.records.keys.toSet(), {'a', 'b', 'c'});
      f.failId = null;
      await confirmDeletion(tester);
      expect(f.deletedIds, ['d', 'b', 'a']);
      expect(f.records.keys, ['c']);
      expect(find.byKey(const Key('snapshot-error')), findsNothing);
    },
  );

  testWidgets(
    'deletion disables repeated actions and navigation until finished',
    (tester) async {
      final f = BatchFixture()..pending = Completer<void>();
      await startBatch(tester, f);
      await tapVisible(
        tester,
        find.byKey(const Key('snapshot-delete-selected')),
      );
      await tester.tap(find.widgetWithText(FilledButton, '删除'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const Key('snapshot-delete-selected')),
            )
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<PopScope>(find.byKey(const Key('snapshot-pop-scope')))
            .canPop,
        isFalse,
      );
      expect(find.text('正在删除快照…已删除 0 份'), findsOneWidget);
      f.pending!.complete();
      await tester.pumpAndSettle();
      expect(f.deletedIds, ['d', 'b', 'a']);
      expect(
        tester
            .widget<PopScope>(find.byKey(const Key('snapshot-pop-scope')))
            .canPop,
        isTrue,
      );
    },
  );

  for (final config in [
    (const Size(360, 800), 2.0),
    (const Size(1280, 900), 1.0),
  ]) {
    testWidgets(
      'batch selection and confirmation fit ${config.$1} scale ${config.$2}',
      (tester) async {
        await tester.binding.setSurfaceSize(config.$1);
        addTearDown(() => tester.binding.setSurfaceSize(null));
        final f = BatchFixture();
        await tester.pumpWidget(
          MaterialApp(
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(context)
                  .copyWith(textScaler: TextScaler.linear(config.$2)),
              child: child!,
            ),
            home: SnapshotsPage(client: f, indexClient: f),
          ),
        );
        await tester.pumpAndSettle();
        await tapVisible(
          tester,
          find.byKey(const Key('snapshot-batch-delete')),
        );
        await confirmDeletion(tester);
        expect(f.deletedIds, ['d', 'b', 'a']);
        expect(tester.takeException(), isNull);
      },
    );
  }
}
