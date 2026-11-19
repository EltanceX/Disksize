import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:disksize/features/explorer/explorer_controller.dart';
import 'package:disksize/features/explorer/explorer_page.dart';
import 'package:disksize/features/explorer/snapshot_rescan_controller.dart';
import 'package:disksize/features/snapshots/snapshot_comparison_page.dart';
import 'package:disksize/features/snapshots/snapshots_page.dart';
import 'package:disksize/platform/index_client.dart';
import 'package:disksize/platform/snapshot_client.dart';
import 'package:disksize/platform/snapshot_rescan_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'explorer_test.dart' show ExplorerFixture, hex;
import 'external_open_test.dart' show RecordingOpener;
import 'snapshots_test.dart' show Fixture, snapshot;
import 'treemap_test.dart' show painter;

const fixtureSnapshot = '11111111-1111-4111-8111-111111111111';

class RescanIndex extends ExplorerFixture {
  bool retained = false, remap = false, missing = false;
  String? latestTask;
  @override
  Map<String, dynamic> entry(int id) => {
    ...super.entry(id),
    if (remap && id == 2) 'id': 102,
  };
  @override
  Map<String, dynamic> metadata() => {
    ...super.metadata(),
    'snapshotId': fixtureSnapshot,
    'retained': retained,
    if (latestTask != null) 'latestRescanTaskId': latestTask,
    if (revision > 1) ...{
      'lastLocalUpdateAtMillis': 5000,
      'localUpdateCount': revision - 1,
      'lastLocalUpdate': {
        'targetPathHex': hex('folder'),
        'pathTruncated': false,
        'startedAtMillis': 3000,
        'scannedAtMillis': 4000,
        'endedAtMillis': 5000,
      },
    },
  };

  @override
  Future<IndexPage> query(IndexRequest r) async {
    requests.add(r);
    final intercepted = intercept?.call(r);
    if (intercepted != null) return intercepted;
    if (r.expectedRevision != null && r.expectedRevision != revision) {
      return IndexPage.fromJson({...metadata(), 'status': 'stale-cursor'});
    }
    if (r.kind == IndexQueryKind.locate) {
      final folder = r.pathHex == hex('folder') && !missing;
      return IndexPage.fromJson({
        ...metadata(),
        'detail': {...entry(folder ? 2 : 1), if (folder && remap) 'id': 102},
        'pathMatched': r.pathHex == '' || folder,
      });
    }
    if ((remap || missing) &&
        (((r.kind == IndexQueryKind.detail ||
                    r.kind == IndexQueryKind.ancestors) &&
                r.nodeId == 2) ||
            ({
                  IndexQueryKind.children,
                  IndexQueryKind.largeFiles,
                  IndexQueryKind.treemap,
                }.contains(r.kind) &&
                r.parentId == 2))) {
      return IndexPage.fromJson({...metadata(), 'status': 'not-found'});
    }
    if (r.nodeId == 102) {
      if (r.kind == IndexQueryKind.detail) {
        return IndexPage.fromJson({
          ...metadata(),
          'detail': {...entry(2), 'id': 102},
        });
      }
      if (r.kind == IndexQueryKind.ancestors) {
        return IndexPage.fromJson({
          ...metadata(),
          'ancestors': [
            {'id': 1, 'parentId': 0, 'nameHex': ''},
            {'id': 102, 'parentId': 1, 'nameHex': hex('folder')},
          ],
        });
      }
    }
    return response(r);
  }
}

class FakeRescan implements SnapshotRescanClient {
  FakeRescan(this.index);
  final RescanIndex index;
  final starts = <(String, int, int)>[];
  String phase = 'scanning', error = '', path = hex('folder');
  int reads = 0, cancels = 0;
  int node = 2, expected = 1;
  String? rejection;
  Completer<String>? cancelResult;
  Completer<SnapshotRescanTask?>? pendingRead;
  SnapshotRescanTask status() => SnapshotRescanTask.fromJson({
    'taskId': 'task',
    'snapshotId': fixtureSnapshot,
    'sessionId': 'fixture',
    'targetNodeId': node,
    'expectedRevision': expected,
    'targetPathHex': path,
    'pathTruncated': false,
    'state': phase,
    'error': error,
    'active': !{
      'completed',
      'failed',
      'cancelled',
      'stale',
      'interrupted',
    }.contains(phase),
    'nodeCount': 123,
    'issueCount': error.isEmpty ? 0 : 1,
    'createdAtMillis': 2500,
    'startedAtMillis': 3000,
    'scannedAtMillis': phase == 'completed' ? 4000 : 0,
    'endedAtMillis': phase == 'completed' ? 5000 : 0,
    'committedRevision': phase == 'completed' ? 2 : null,
    'cancellationRequested': false,
    'stagingCleaned': phase == 'completed',
  });
  @override
  Future<SnapshotRescanTask> start(
    String snapshot,
    int id,
    int revision,
  ) async {
    starts.add((snapshot, id, revision));
    if (rejection != null) throw SnapshotRescanException(rejection!);
    node = id;
    expected = revision;
    path = id == 1 ? '' : hex('folder');
    index.latestTask = 'task';
    return status();
  }

  @override
  Future<SnapshotRescanTask?> task(String id) async {
    reads++;
    return pendingRead?.future ?? status();
  }

  @override
  Future<String> cancel(String id) async {
    cancels++;
    return cancelResult?.future ?? 'cancellation-requested';
  }

  void complete({bool remap = false, bool missing = false}) {
    phase = 'completed';
    index.revision = 2;
    index.remap = remap;
    index.missing = missing;
  }
}

Future<void> showExplorer(
  WidgetTester tester,
  RescanIndex index,
  FakeRescan rescan, {
  double scale = 1,
  RecordingOpener? opener,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: ThemeData(
        useMaterial3: true,
        fontFamily: captureDirectory.isEmpty ? null : 'RescanCjk',
      ),
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context)
            .copyWith(textScaler: TextScaler.linear(scale)),
        child: RepaintBoundary(key: const Key('capture'), child: child!),
      ),
      home: ExplorerPage(
        client: index,
        sessionId: 'fixture',
        rescanClient: rescan,
        opener: opener,
      ),
    ),
  );
  if (index.latestTask == null) {
    await tester.pumpAndSettle();
  } else {
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
  }
}

Future<void> reveal(WidgetTester tester, Finder finder) async {
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

Future<void> startRoot(WidgetTester tester) async {
  final button = find.byKey(const Key('directory-rescan'));
  await reveal(tester, button);
  await tester.tap(button);
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('rescan-confirm')));
  await tester.pump();
  await tester.pump();
  await tester.pump();
}

const captureDirectory = String.fromEnvironment('RESCAN_UI_CAPTURE_DIR');
Future<void> capture(WidgetTester tester, String name) async {
  if (captureDirectory.isEmpty) return;
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(const Key('capture')),
  );
  await tester.runAsync(() async {
    final image = await boundary.toImage(pixelRatio: 1);
    final data = await image.toByteData(format: ui.ImageByteFormat.png);
    image.dispose();
    await Directory(captureDirectory).create(recursive: true);
    await File('$captureDirectory/$name.png')
        .writeAsBytes(data!.buffer.asUint8List());
  });
}

class UpdatedSnapshots extends Fixture {
  int revision = 2;
  bool staleNext = false;
  Map<String, dynamic> record(String id) => {
    ...snapshot(id),
    'revision': revision,
    'localUpdateCount': revision - 1,
    'lastLocalUpdateAtMillis': 5000,
    'lastLocalUpdate': {
      'targetPathHex': 'ff0a',
      'pathTruncated': false,
      'startedAtMillis': 3000,
      'scannedAtMillis': 4000,
      'endedAtMillis': 5000,
    },
  };
  @override
  Future<SnapshotList> list({String? cursor}) async => SnapshotList.fromJson({
    'snapshots': [record('old'), record('new')],
  });
  @override
  Future<SnapshotComparison> compare(
    String base,
    String target, {
    String sort = 'logical',
    String filter = 'all',
    String? cursor,
  }) async {
    requests.add((sort, filter, cursor));
    if (cursor != null && staleNext) {
      staleNext = false;
      return SnapshotComparison.fromJson({'status': 'stale-cursor'});
    }
    return SnapshotComparison.fromJson({
      'status': status,
      'base': record(base),
      'target': record(target),
      'complete': true,
      'logicalDelta': 0,
      'allocatedDelta': 0,
      'nextCursor': cursor == null ? 'next' : null,
    });
  }
}

void main() {
  setUpAll(() async {
    if (captureDirectory.isNotEmpty) {
      final loader = FontLoader('RescanCjk')
        ..addFont(
          File('C:/Windows/Fonts/msyh.ttc')
              .readAsBytes()
              .then(ByteData.sublistView),
        );
      await loader.load();
      await (FontLoader('MaterialIcons')..addFont(
            File(
              'E:/Projects/.toolchains/flutter/bin/cache/artifacts/material_fonts/MaterialIcons-Regular.otf',
            ).readAsBytes().then(ByteData.sublistView),
          ))
          .load();
    }
  });

  testWidgets(
    'list confirmation precedes indexed rescan; reload has no scan side effects',
    (tester) async {
      final index = RescanIndex();
      final rescan = FakeRescan(index);
      await showExplorer(tester, index, rescan);
      await tester.tap(find.byTooltip('重新加载索引'));
      await tester.pumpAndSettle();
      expect(rescan.starts, isEmpty);
      final menu = find.byKey(const ValueKey('entry-menu-2'));
      await reveal(tester, menu);
      await tester.tap(menu);
      await tester.pumpAndSettle();
      await tester.tap(find.text('重新扫描此文件夹').last);
      await tester.pumpAndSettle();
      expect(rescan.starts, isEmpty);
      expect(find.text('/data/fixture/folder'), findsOneWidget);
      expect(find.textContaining('无法撤销'), findsOneWidget);
      await tester.tap(find.text('返回'));
      await tester.pumpAndSettle();
      expect(rescan.starts, isEmpty);
      await reveal(tester, menu);
      await tester.tap(menu);
      await tester.pumpAndSettle();
      await tester.tap(find.text('重新扫描此文件夹').last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('rescan-confirm')));
      await tester.pump();
      await tester.pump();
      await tester.pump();
      expect(rescan.starts, [(fixtureSnapshot, 2, 1)]);
      expect(
        tester
            .widget<OutlinedButton>(find.byKey(const Key('directory-rescan')))
            .onPressed,
        isNull,
      );
      await reveal(tester, find.byKey(const Key('rescan-status')));
      expect(find.text('正在扫描'), findsOneWidget);
      expect(find.textContaining('已发现 123'), findsOneWidget);
      expect(find.textContaining('更新已提交'), findsNothing);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('commit wins a pending cancel and closes old detail sheets', (
    tester,
  ) async {
    final index = RescanIndex();
    final rescan = FakeRescan(index)..cancelResult = Completer<String>();
    await showExplorer(tester, index, rescan);
    await startRoot(tester);
    await reveal(tester, find.byKey(const Key('rescan-cancel')));
    await tester.tap(find.byKey(const Key('rescan-cancel')));
    await tester.pump();
    expect(find.textContaining('确认取消'), findsOneWidget);
    expect(find.text('已取消'), findsNothing);
    final detail = find.byKey(const ValueKey('detail-3'));
    await reveal(tester, detail);
    await tester.tap(detail);
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.byTooltip('关闭详情'), findsOneWidget);
    rescan.complete();
    rescan.cancelResult!.complete('already-completed');
    await tester.pump();
    await tester.pump();
    await tester.pumpAndSettle();
    expect(find.byTooltip('关闭详情'), findsNothing);
    expect(find.text('更新已提交'), findsOneWidget);
    expect(find.text('已取消'), findsNothing);
    expect(find.textContaining('已局部更新'), findsOneWidget);
    expect(index.revision, 2);
    expect(rescan.cancels, 1);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'transport failure keeps the task active and polls never overlap',
    (tester) async {
      final index = RescanIndex();
      final client = FakeRescan(index);
      final controller = SnapshotRescanController(client);
      await controller.start(fixtureSnapshot, 2, 1);
      client.pendingRead = Completer<SnapshotRescanTask?>();
      final reading = controller.refresh();
      await controller.refresh();
      expect(client.reads, 1);
      controller.setResumed(false);
      client.pendingRead!.completeError(
        PlatformException(code: 'service-timeout'),
      );
      await reading;
      expect(controller.task!.active, isTrue);
      expect(controller.error, contains('任务可能仍在运行'));
      final reads = client.reads;
      await tester.pump(const Duration(seconds: 3));
      expect(client.reads, reads);
      client.pendingRead = null;
      client.complete();
      controller.setResumed(true);
      await tester.pump();
      expect(controller.task!.state, 'completed');
      expect(controller.error, isNull);
      controller.dispose();
      await tester.pump(const Duration(seconds: 2));
    },
  );

  testWidgets(
    'a globally partial snapshot can still refresh a complete directory',
    (tester) async {
      final index = RescanIndex()..state = 'partial';
      final rescan = FakeRescan(index);
      await showExplorer(tester, index, rescan);
      await startRoot(tester);
      expect(rescan.starts, [(fixtureSnapshot, 1, 1)]);
      rescan.complete();
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
      expect(find.text('更新已提交'), findsOneWidget);
      expect(index.state, 'partial');
      await tester.pumpWidget(const SizedBox());
    },
  );

  for (final failure in [
    'incomplete-scan',
    'target-missing',
    'root-unavailable',
    'incompatible-identity',
  ]) {
    testWidgets('terminal $failure explains unchanged data and next action', (
      tester,
    ) async {
      final index = RescanIndex();
      final rescan = FakeRescan(index);
      await showExplorer(tester, index, rescan);
      await startRoot(tester);
      rescan.phase = 'failed';
      rescan.error = failure;
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
      expect(find.text(rescanErrorMessage(failure)), findsOneWidget);
      expect(index.revision, 1);
      expect(find.textContaining('已局部更新'), findsNothing);
      await tester.pumpWidget(const SizedBox());
    });
  }

  for (final rejection in ['busy', 'retained', 'stale-revision']) {
    testWidgets('admission $rejection remains actionable', (tester) async {
      final index = RescanIndex();
      final rescan = FakeRescan(index)..rejection = rejection;
      await showExplorer(tester, index, rescan);
      await startRoot(tester);
      expect(find.text(rescanErrorMessage(rejection)), findsOneWidget);
      expect(index.revision, 1);
      expect(index.latestTask, isNull);
      await tester.pumpWidget(const SizedBox());
    });
  }

  testWidgets(
    'retained snapshots and file details cannot start directory scans',
    (tester) async {
      final index = RescanIndex()..retained = true;
      final rescan = FakeRescan(index);
      await showExplorer(tester, index, rescan);
      expect(
        tester
            .widget<OutlinedButton>(find.byKey(const Key('directory-rescan')))
            .onPressed,
        isNull,
      );
      expect(find.byKey(const ValueKey('entry-menu-2')), findsNothing);
      await reveal(tester, find.byKey(const ValueKey('detail-2')));
      await tester.tap(find.byKey(const ValueKey('detail-2')));
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<TextButton>(find.byKey(const Key('detail-rescan')))
            .onPressed,
        isNull,
      );
      await tester.tap(find.byTooltip('关闭详情'));
      await tester.pumpAndSettle();
      await reveal(tester, find.byKey(const ValueKey('detail-3')));
      await tester.tap(find.byKey(const ValueKey('detail-3')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('detail-rescan')), findsNothing);
      expect(rescan.starts, isEmpty);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('treemap directory offers rescan; a file offers no rescan', (
    tester,
  ) async {
    final index = RescanIndex();
    final rescan = FakeRescan(index);
    await showExplorer(tester, index, rescan);
    await reveal(tester, find.text('树图'));
    await tester.tap(find.text('树图'));
    await tester.pumpAndSettle();
    final canvas = find.byKey(const Key('treemap-canvas'));
    await reveal(tester, canvas);
    for (final id in [2, 3]) {
      await reveal(tester, canvas);
      final tile = painter(tester).tiles.firstWhere((e) => e.item.id == id);
      await tester.tapAt(tester.getTopLeft(canvas) + tile.hitRect.center);
      await tester.pump();
      expect(
        find.byKey(const Key('treemap-rescan')),
        id == 2 ? findsOneWidget : findsNothing,
      );
    }
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'reentering and foregrounding reconnect the persistent task without restarting it',
    (tester) async {
      final index = RescanIndex()..latestTask = 'task';
      final rescan = FakeRescan(index);
      await showExplorer(tester, index, rescan);
      expect(rescan.starts, isEmpty);
      expect(find.text('正在扫描'), findsOneWidget);
      for (final state in [
        AppLifecycleState.inactive,
        AppLifecycleState.hidden,
        AppLifecycleState.paused,
      ]) {
        tester.binding.handleAppLifecycleStateChanged(state);
      }
      final reads = rescan.reads;
      await tester.pump(const Duration(seconds: 3));
      expect(rescan.reads, reads);
      rescan.complete();
      for (final state in [
        AppLifecycleState.hidden,
        AppLifecycleState.inactive,
        AppLifecycleState.resumed,
      ]) {
        tester.binding.handleAppLifecycleStateChanged(state);
      }
      await tester.pumpAndSettle();
      expect(find.text('更新已提交'), findsOneWidget);
      expect(find.textContaining('已局部更新'), findsOneWidget);
      expect(rescan.starts, isEmpty);
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
      await showExplorer(tester, index, rescan);
      expect(find.text('更新已提交'), findsOneWidget);
      expect(rescan.starts, isEmpty);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'selected treemap directory follows its raw path to the new node ID',
    (tester) async {
      final index = RescanIndex();
      final rescan = FakeRescan(index);
      await showExplorer(tester, index, rescan);
      await reveal(tester, find.text('树图'));
      await tester.tap(find.text('树图'));
      await tester.pumpAndSettle();
      final canvas = find.byKey(const Key('treemap-canvas'));
      await reveal(tester, canvas);
      final tile = painter(tester).tiles.firstWhere((t) => t.item.id == 2);
      await tester.tapAt(tester.getTopLeft(canvas) + tile.hitRect.center);
      await tester.pump(const Duration(milliseconds: 500));
      expect(painter(tester).selected, 2);
      tester
          .widget<CustomScrollView>(find.byType(CustomScrollView))
          .controller!
          .jumpTo(0);
      await tester.pump();
      await startRoot(tester);
      rescan.complete(remap: true);
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
      await reveal(tester, canvas);
      expect(painter(tester).selected, 102);
      expect(painter(tester).entries[102]?.nameHex, hex('folder'));
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('external open checks the displayed revision before dispatch', (
    tester,
  ) async {
    final index = RescanIndex();
    final rescan = FakeRescan(index);
    final opener = RecordingOpener();
    await showExplorer(tester, index, rescan, opener: opener);
    await reveal(tester, find.byKey(const ValueKey('detail-3')));
    await tester.tap(find.byKey(const ValueKey('detail-3')));
    await tester.pumpAndSettle();
    index.revision = 2;
    await tester.tap(find.byKey(const Key('open-indexed-entry')));
    await tester.pumpAndSettle();
    expect(opener.calls, isEmpty);
    expect(find.textContaining('重新加载详情后再打开'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  for (final missing in [false, true]) {
    test(
      'revision restoration uses raw path, discards cursors and preserves view settings, missing=$missing',
      () async {
        final index = RescanIndex();
        final c = ExplorerController(index, 'fixture');
        await c.navigate(2);
        await c.setSort(IndexSort.name);
        await c.load(cursor: 'old-cursor', number: 3);
        index.revision = 2;
        index.remap = !missing;
        index.missing = missing;
        index.requests.clear();
        await c.reloadAfterUpdate();
        expect(c.directoryId, missing ? 1 : 102);
        expect(c.pageNumber, 1);
        expect(c.sort, IndexSort.name);
        expect(c.contentEpoch, 1);
        expect(
          index.requests.any(
            (r) =>
                r.kind == IndexQueryKind.locate &&
                r.pathHex == hex('folder') &&
                r.expectedRevision == 2,
          ),
          isTrue,
        );
        expect(index.requests.any((r) => r.cursor != null), isFalse);
        c.dispose();
      },
    );
  }

  test(
    'long non UTF-8 raw paths resolve in bounded revision-checked chunks',
    () async {
      final index = RescanIndex();
      final components = List.filled(40, 'ff0a${'61' * 250}');
      var calls = 0;
      index.intercept = (r) {
        expect(r.kind, IndexQueryKind.locate);
        expect(r.pathHex!.length, lessThanOrEqualTo(6000));
        expect(r.expectedRevision, 2);
        expect(r.pathHex, isNot(contains('efbfbd')));
        calls++;
        return Future.value(
          IndexPage.fromJson({
            ...index.metadata(),
            'revision': 2,
            'pathMatched': true,
            'detail': {...index.entry(2), 'id': 100 + calls},
          }),
        );
      };
      await locateIndexedPath(index, 'fixture', components.join('2f'), 2);
      expect(calls, greaterThan(1));
      expect(index.requests.skip(1).every((r) => r.parentId > 100), isTrue);
    },
  );

  testWidgets(
    'comparison replaces stale cursor and explains mixed collection times',
    (tester) async {
      final client = UpdatedSnapshots()..staleNext = true;
      await tester.pumpWidget(
        MaterialApp(
          home: SnapshotComparisonPage(
            client: client,
            baseId: 'old',
            targetId: 'new',
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('已局部更新'), findsNWidgets(2));
      expect(find.textContaining('各范围采集时间不同'), findsNWidgets(2));
      await tester.tap(find.text('下一页'));
      await tester.pumpAndSettle();
      expect(client.requests.map((r) => r.$3), [null, 'next', null]);
      expect(find.textContaining('丢弃旧游标'), findsOneWidget);
      client.status = 'busy';
      await tester.tap(find.byTooltip('重新对比'));
      await tester.pumpAndSettle();
      expect(find.textContaining('等待结束后重新对比'), findsOneWidget);
      expect(find.textContaining('总差额'), findsNothing);
      await tester.pumpWidget(const SizedBox());
    },
  );

  test(
    'manual treemap reload relocates an old ID that now returns not-found',
    () async {
      final index = RescanIndex();
      final c = ExplorerController(index, 'fixture');
      await c.navigate(2);
      await c.setTreemap();
      index.revision = 2;
      index.remap = true;
      await c.load();
      expect(c.directoryId, 102);
      expect(c.page?.revision, 2);
      expect(c.treemap, isTrue);
      expect(c.retryAutomatically, isFalse);
      c.dispose();
    },
  );

  for (final config in [
    (const Size(360, 800), 2.0),
    (const Size(1280, 900), 1.0),
  ]) {
    testWidgets(
      'rescan confirmation, progress, completion and snapshots fit ${config.$1} scale ${config.$2}',
      (tester) async {
        await tester.binding.setSurfaceSize(config.$1);
        addTearDown(() => tester.binding.setSurfaceSize(null));
        final index = RescanIndex();
        final rescan = FakeRescan(index);
        await showExplorer(tester, index, rescan, scale: config.$2);
        await reveal(tester, find.byKey(const Key('directory-rescan')));
        await tester.tap(find.byKey(const Key('directory-rescan')));
        await tester.pumpAndSettle();
        await capture(tester, 'confirm-${config.$1.width.toInt()}');
        await tester.tap(find.byKey(const Key('rescan-confirm')));
        await tester.pump();
        await tester.pump();
        await reveal(tester, find.byKey(const Key('rescan-status')));
        await tester.pump(const Duration(milliseconds: 350));
        await capture(tester, 'progress-${config.$1.width.toInt()}');
        expect(tester.takeException(), isNull);
        rescan.complete();
        await tester.pump(const Duration(seconds: 1));
        await tester.pumpAndSettle();
        await reveal(tester, find.byKey(const Key('rescan-status')));
        await capture(tester, 'complete-${config.$1.width.toInt()}');
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        await tester.pump();
        final client = UpdatedSnapshots();
        await tester.pumpWidget(
          MaterialApp(
            theme: ThemeData(
              fontFamily: captureDirectory.isEmpty ? null : 'RescanCjk',
            ),
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(context)
                  .copyWith(textScaler: TextScaler.linear(config.$2)),
              child: RepaintBoundary(key: const Key('capture'), child: child!),
            ),
            home: SnapshotsPage(client: client, indexClient: client),
          ),
        );
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.text('最近局部更新详情').first);
        await tester.tap(find.text('最近局部更新详情').first);
        await tester.pumpAndSettle();
        await capture(tester, 'snapshots-${config.$1.width.toInt()}');
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
      },
    );
  }
}
