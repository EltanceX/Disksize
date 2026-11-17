import 'package:disksize/features/snapshots/snapshot_comparison_page.dart';
import 'package:disksize/features/snapshots/snapshots_page.dart';
import 'package:disksize/platform/index_client.dart';
import 'package:disksize/platform/snapshot_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> snapshot(String id, {bool retained = false}) => {
  'id': id,
  'rootPath': '/fixture',
  'backend': 'ordinary',
  'state': 'completed',
  'startedAtMillis': 1000,
  'endedAtMillis': 2000,
  'logicalBytes': 10,
  'allocatedBytes': 4096,
  'estimatedStorageBytes': 32768,
  'complete': true,
  'retained': retained,
  'available': true,
};

class Fixture implements SnapshotClient, IndexClient {
  bool retained = false, deleted = false;
  int buildCalls = 0;
  String status = 'ok';
  final requests = <(String, String, String?)>[];
  @override
  Future<SnapshotList> list({String? cursor}) async => SnapshotList.fromJson({
    'snapshots': [
      if (!deleted) snapshot('old', retained: retained),
      snapshot('new'),
    ],
  });
  @override
  Future<void> retain(String id, bool value) async {
    retained = value;
  }

  @override
  Future<void> delete(String id) async {
    deleted = true;
  }

  @override
  Future<SnapshotComparison> compare(
    String base,
    String target, {
    String sort = 'logical',
    String filter = 'all',
    String? cursor,
  }) async {
    requests.add((sort, filter, cursor));
    return SnapshotComparison.fromJson({
      'status': buildCalls++ == 0 && status == 'building'
          ? 'building'
          : status == 'building'
          ? 'ok'
          : status,
      'processed': 512,
      'base': snapshot('old'),
      'target': snapshot('new'),
      'reasons': ['根路径不同'],
      'complete': false,
      'logicalDelta': null,
      'counts': {'unknown': 1},
      'nextCursor': cursor == null ? 'next' : null,
      'entries': [
        {
          'pathHex': '66696c650a',
          'status': 'unknown',
          'oldKind': 'regular',
          'newKind': 'unknown',
          'oldLogical': 10,
          'newLogical': null,
          'oldAllocated': 4096,
          'newAllocated': null,
          'logicalDelta': null,
          'allocatedDelta': null,
        },
      ],
    });
  }

  @override
  Future<IndexPage> query(IndexRequest request) async =>
      IndexPage.fromJson({'status': 'not-found'});
  @override
  Future<List<IndexSession>> recent() async => [];
  @override
  Future<List<StorageEntry>> volumes() async => [];
}

void main() {
  testWidgets('retention guards deletion and confirmation precedes delete', (
    tester,
  ) async {
    final fixture = Fixture();
    await tester.pumpWidget(
      MaterialApp(
        home: SnapshotsPage(client: fixture, indexClient: fixture),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('快照大小（估算）：32.0 KiB'), findsNWidgets(2));
    expect(find.text('逻辑 10 B · 报告占用 4.0 KiB'), findsNWidgets(2));
    await tester.tap(find.text('保留快照').first);
    await tester.pumpAndSettle();
    expect(fixture.retained, isTrue);
    final delete = tester.widget<TextButton>(
      find.widgetWithText(TextButton, '删除快照').first,
    );
    expect(delete.onPressed, isNull);
    await tester.tap(find.text('取消保留'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除快照').first);
    await tester.pumpAndSettle();
    expect(fixture.deleted, isFalse);
    expect(find.textContaining('原文件不受影响'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, '删除'));
    await tester.pumpAndSettle();
    expect(fixture.deleted, isTrue);
  });
  testWidgets('base selection opens comparison with unknown and escaped path', (
    tester,
  ) async {
    final fixture = Fixture();
    await tester.pumpWidget(
      MaterialApp(
        home: SnapshotsPage(client: fixture, indexClient: fixture),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('设为基准').first);
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('作为新快照对比').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('作为新快照对比').last);
    await tester.pumpAndSettle();
    expect(find.text('总差额：未知'), findsOneWidget);
    expect(find.text(r'file\n'), findsOneWidget);
    expect(find.textContaining('重命名按删除和新增'), findsOneWidget);
    await tester.tap(find.text('下一页'));
    await tester.pumpAndSettle();
    expect(fixture.requests.last.$3, 'next');
  });
  testWidgets('building progresses and incompatible snapshots explain reason', (
    tester,
  ) async {
    final fixture = Fixture()..status = 'building';
    await tester.pumpWidget(
      MaterialApp(
        home: SnapshotComparisonPage(
          client: fixture,
          baseId: 'old',
          targetId: 'new',
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(fixture.buildCalls, 2);
    expect(find.text('总差额：未知'), findsOneWidget);
    fixture.status = 'incompatible';
    await tester.tap(find.byTooltip('重新对比'));
    await tester.pumpAndSettle();
    expect(find.text('快照不兼容'), findsOneWidget);
    expect(find.text('• 根路径不同'), findsOneWidget);
    expect(find.text(r'file\n'), findsNothing);
  });
  for (final config in [
    (const Size(360, 800), 2.0),
    (const Size(1280, 900), 1.0),
  ]) {
    testWidgets('comparison and library fit ${config.$1} at ${config.$2}', (
      tester,
    ) async {
      await tester.binding.setSurfaceSize(config.$1);
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final fixture = Fixture();
      Widget app(Widget home) => MaterialApp(
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: TextScaler.linear(config.$2)),
          child: child!,
        ),
        home: home,
      );
      await tester.pumpWidget(
        app(SnapshotsPage(client: fixture, indexClient: fixture)),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(
        app(
          SnapshotComparisonPage(
            client: fixture,
            baseId: 'old',
            targetId: 'new',
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tester.ensureVisible(find.text('全部变化'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('全部变化'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('增长最多的目录').last);
      await tester.pumpAndSettle();
      expect(fixture.requests.last.$2, 'growth');
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    });
  }
}
