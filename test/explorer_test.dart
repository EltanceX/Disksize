import 'dart:async';
import 'dart:convert';

import 'package:disksize/features/explorer/explorer_controller.dart';
import 'package:disksize/features/explorer/explorer_detail.dart';
import 'package:disksize/features/explorer/explorer_page.dart';
import 'package:disksize/platform/index_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

String hex(String value) =>
    utf8.encode(value).map((b) => b.toRadixString(16).padLeft(2, '0')).join();

class ExplorerFixture implements IndexClient {
  final requests = <IndexRequest>[];
  int revision = 1;
  String state = 'completed';
  Future<IndexPage>? Function(IndexRequest)? intercept;
  Map<String, dynamic> metadata() => {
    'status': 'ok',
    'sessionId': 'fixture',
    'revision': revision,
    'provisional': false,
    'state': state,
    'rootPath': '/data/fixture',
    'startedAtMillis': 1000,
    'endedAtMillis': 2000,
    'nodeCount': 60,
    'issueCount': 0,
    'volume': {
      'totalBytes': 100000,
      'freeBytes': 20000,
      'availableBytes': 10000,
      'capturedAtMillis': 1000,
    },
  };
  Map<String, dynamic> entry(int id) => {
    'id': id,
    'parentId': id == 1
        ? 0
        : id == 4
        ? 2
        : 1,
    'nameHex': hex(id <= 2 ? 'folder' : '${'long filename 中文 ' * 6}$id'),
    'kind': id <= 2 ? 'directory' : 'regular',
    'logicalBytes': 8192,
    'allocatedBytes': 4096,
    'complete': true,
    'ownerId': id,
    'pathHex': id == 1
        ? ''
        : hex(
            id == 4
                ? 'folder/child'
                : id == 2
                ? 'folder'
                : 'file\n3',
          ),
    'symlinkTargetHex': '',
    'mode': 33152,
    'uid': 10000,
    'gid': 10000,
    'deviceHex': '01',
    'inodeHex': '02',
    'mountId': 1,
    'linkCount': 1,
    'rawSize': 8192,
    'rawAllocatedBytes': 4096,
    'mtimeSeconds': 1,
    'mtimeNanos': 0,
    'metadataValid': true,
    'skipped': 'none',
  };
  IndexPage response(IndexRequest r) {
    final m = metadata();
    switch (r.kind) {
      case IndexQueryKind.children:
        m['entries'] = r.parentId == 2
            ? [entry(4)]
            : r.cursor == 'next'
            ? [entry(5)]
            : [entry(2), entry(3)];
        if (r.parentId == 1) {
          m[r.cursor == 'next' ? 'previousCursor' : 'nextCursor'] =
              r.cursor == 'next' ? 'prev' : 'next';
        }
      case IndexQueryKind.largeFiles:
        m['entries'] = [entry(r.parentId == 2 ? 4 : 3)];
      case IndexQueryKind.detail:
        m['detail'] = entry(r.nodeId);
      case IndexQueryKind.ancestors:
        m['ancestors'] = [
          {'id': 1, 'parentId': 0, 'nameHex': ''},
          if (r.nodeId == 2) {'id': 2, 'parentId': 1, 'nameHex': hex('folder')},
        ];
      case IndexQueryKind.issues:
        m['issues'] = [];
      case IndexQueryKind.session:
        break;
      case IndexQueryKind.locate:
        final id = r.pathHex == hex('folder') ? 2 : 1;
        m['detail'] = entry(id);
        m['pathMatched'] = r.pathHex == '' || r.pathHex == hex('folder');
      case IndexQueryKind.treemap:
        m['entries'] = r.parentId == 2 ? [entry(4)] : [entry(2), entry(3)];
        m['treemap'] = {
          'totalBytes': 16384,
          'otherBytes': 0,
          'childCount': 2,
          'zeroCount': 0,
          'unknownCount': 0,
        };
    }
    return IndexPage.fromJson(m);
  }

  @override
  Future<IndexPage> query(IndexRequest request) async {
    requests.add(request);
    return intercept?.call(request) ?? response(request);
  }

  @override
  Future<List<IndexSession>> recent() async => [
    IndexSession.fromJson(metadata()),
  ];
  @override
  Future<List<StorageEntry>> volumes() async => [];
}

void main() {
  test('resume preserves an unchanged page and refreshes changed or deleted indexes', () async {
    final client = ExplorerFixture();
    final c = ExplorerController(client, 'fixture');
    await c.load(cursor: 'next', number: 2);
    final page = c.page;
    var changes = 0;
    c.addListener(() => changes++);
    client.requests.clear();
    await c.resume();
    expect(client.requests.single.kind, IndexQueryKind.session);
    expect(identical(c.page, page), isTrue);
    expect(changes, 0);
    expect(c.pageNumber, 2);
    client.revision++;
    await c.resume();
    expect(c.page!.revision, 2);
    expect(c.pageNumber, 1);
    expect(client.requests.last.cursor, isNull);
    client.intercept = (_) =>
        Future.value(IndexPage.fromJson({'status': 'not-found'}));
    await c.resume();
    expect(c.page, isNull);
    expect(c.message, contains('已不可用'));
    c.dispose();
  });
  test(
    'a late resume check cannot refresh a newly navigated directory',
    () async {
      final client = ExplorerFixture();
      final c = ExplorerController(client, 'fixture');
      await c.load();
      final pending = Completer<IndexPage>();
      client.intercept = (r) =>
          r.kind == IndexQueryKind.session ? pending.future : null;
      final resumed = c.resume();
      await c.navigate(2);
      final reads = client.requests.length;
      pending.complete(
        IndexPage.fromJson({...client.metadata(), 'revision': 2}),
      );
      await resumed;
      expect(c.directoryId, 2);
      expect(c.page!.entries.single.id, 4);
      expect(client.requests.length, reads);
      c.dispose();
    },
  );
  testWidgets('active index polling restarts after transient focus loss', (
    tester,
  ) async {
    final client = ExplorerFixture()..state = 'running';
    await tester.pumpWidget(
      MaterialApp(
        home: ExplorerPage(client: client, sessionId: 'fixture'),
      ),
    );
    await tester.pump();
    await tester.pump();
    final reads = client.requests.length;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    await tester.pump(const Duration(seconds: 3));
    expect(client.requests.length, reads);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(client.requests.length, reads);
    await tester.pump(const Duration(seconds: 2));
    await tester.pump();
    expect(client.requests.length, greaterThan(reads));
    await tester.pumpWidget(const SizedBox());
  });
  test('a late directory response cannot replace newer navigation', () async {
    final client = ExplorerFixture();
    final pending = Completer<IndexPage>();
    client.intercept = (r) =>
        r.kind == IndexQueryKind.children && r.parentId == 1
        ? pending.future
        : null;
    final c = ExplorerController(client, 'fixture');
    final old = c.load();
    await c.navigate(2);
    pending.complete(
      client.response(
        const IndexRequest(
          sessionId: 'fixture',
          kind: IndexQueryKind.children,
          parentId: 1,
        ),
      ),
    );
    await old;
    expect(c.directoryId, 2);
    expect(c.page!.entries.single.id, 4);
    c.dispose();
  });

  test(
    'stale page cursors restart from page one without retaining history',
    () async {
      final client = ExplorerFixture();
      client.intercept = (r) => r.cursor != null
          ? Future.value(IndexPage.fromJson({'status': 'stale-cursor'}))
          : null;
      final c = ExplorerController(client, 'fixture');
      await c.load(cursor: 'old', number: 2);
      expect(c.pageNumber, 1);
      expect(c.page!.entries.length, 2);
      expect(c.message, contains('第一页'));
      await c.setSort(IndexSort.name);
      await c.setLargeFiles(true);
      expect(c.sort, IndexSort.logical);
      await c.setMetric(true);
      expect(client.requests.last.sort, IndexSort.allocated);
      c.dispose();
    },
  );

  testWidgets(
    'directory drilldown, scoped large files, copy raw path and back',
    (tester) async {
      String? copied;
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async {
          if (call.method == 'Clipboard.setData') {
            copied = (call.arguments as Map)['text'] as String;
          }
          return null;
        },
      );
      addTearDown(
        () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.platform,
          null,
        ),
      );
      final client = ExplorerFixture();
      await tester.pumpWidget(
        MaterialApp(
          home: ExplorerPage(client: client, sessionId: 'fixture'),
        ),
      );
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const ValueKey('entry-2')));
      await tester.tap(find.byKey(const ValueKey('entry-2')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('crumb-2')), findsOneWidget);
      await tester.ensureVisible(find.text('大文件'));
      await tester.tap(find.text('大文件'));
      await tester.pumpAndSettle();
      expect(
        client.requests.any(
          (r) => r.kind == IndexQueryKind.largeFiles && r.parentId == 2,
        ),
        true,
      );
      await tester.ensureVisible(find.byKey(const ValueKey('entry-4')));
      await tester.tap(find.byKey(const ValueKey('entry-4')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('copy-indexed-path')));
      await tester.pumpAndSettle();
      expect(copied, '/data/fixture/folder/child');
      await tester.tap(find.byTooltip('关闭详情'));
      await tester.pumpAndSettle();
      await tester.drag(find.byType(CustomScrollView), const Offset(0, 800));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const ValueKey('crumb-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('crumb-1')));
      await tester.pumpAndSettle();
      expect(client.requests.last.parentId, 1);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );

  test(
    'a failed sort change cannot reuse the previous query cursor on retry',
    () async {
      final client = ExplorerFixture();
      final c = ExplorerController(client, 'fixture');
      await c.load(cursor: 'next', number: 2);
      var fail = true;
      client.intercept = (r) {
        if (r.kind == IndexQueryKind.children &&
            r.sort == IndexSort.name &&
            fail) {
          fail = false;
          return Future.error(StateError('connection lost'));
        }
        if (r.sort == IndexSort.name && r.cursor != null) {
          return Future.value(IndexPage.fromJson({'status': 'invalid-query'}));
        }
        return null;
      };
      await c.setSort(IndexSort.name);
      expect(c.message, isNotNull);
      await c.refresh();
      expect(c.page!.status, 'ok');
      expect(c.message, isNull);
      expect(c.pageNumber, 1);
      c.dispose();
    },
  );

  testWidgets(
    'bounded previous and next page controls replace the result page',
    (tester) async {
      final client = ExplorerFixture();
      await tester.pumpWidget(
        MaterialApp(
          home: ExplorerPage(client: client, sessionId: 'fixture'),
        ),
      );
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const Key('next-page')));
      await tester.tap(find.byKey(const Key('next-page')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('entry-2')), findsNothing);
      expect(find.text('第 2 页 · 每页最多 50 项'), findsOneWidget);
      await tester.ensureVisible(find.byKey(const Key('previous-page')));
      await tester.tap(find.byKey(const Key('previous-page')));
      await tester.pumpAndSettle();
      expect(find.text('第 1 页 · 每页最多 50 项'), findsOneWidget);
      expect(client.requests.any((r) => r.cursor == 'prev'), true);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('invalid UTF-8 paths copy explicit full path bytes', (
    tester,
  ) async {
    String? copied;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copied = (call.arguments as Map)['text'] as String;
        }
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    final client = ExplorerFixture();
    client.intercept = (r) => r.kind == IndexQueryKind.detail
        ? Future.value(
            IndexPage.fromJson({
              ...client.metadata(),
              'detail': {...client.entry(3), 'pathHex': 'ff', 'nameHex': 'ff'},
            }),
          )
        : null;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ExplorerDetail(
            client: client,
            sessionId: 'fixture',
            nodeId: 3,
            onLocate: (_) {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('copy-indexed-path')));
    await tester.pumpAndSettle();
    expect(copied, '${hex('/data/fixture/')}ff');
    expect(find.text('路径包含非 UTF-8 字节，已复制完整路径的十六进制字节。'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  for (final config in [
    (const Size(360, 800), 2.0, 'directory'),
    (const Size(1280, 900), 1.0, 'directory'),
    (const Size(360, 800), 2.0, 'empty'),
    (const Size(360, 800), 2.0, 'missing'),
    (const Size(360, 800), 1.0, 'back'),
  ]) {
    testWidgets('folder taps preserve scroll through loading ${config.$1}, '
        'scale ${config.$2}, ${config.$3}', (tester) async {
      await tester.binding.setSurfaceSize(config.$1);
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final client = ExplorerFixture();
      final pending = Completer<IndexPage>();
      client.intercept = (request) {
        if (request.kind != IndexQueryKind.children) return null;
        if (request.parentId == 2) return pending.future;
        return Future.value(
          IndexPage.fromJson({
            ...client.metadata(),
            'entries': [
              for (var id = 10; id < 30; id++) client.entry(id),
              client.entry(2),
            ],
          }),
        );
      };
      await tester.pumpWidget(
        MaterialApp(
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(config.$2)),
            child: child!,
          ),
          home: ExplorerPage(client: client, sessionId: 'fixture'),
        ),
      );
      await tester.pumpAndSettle();
      final viewport = find.byType(CustomScrollView);
      await tester.scrollUntilVisible(
        find.byKey(const ValueKey('entry-2')),
        500,
        scrollable: find
            .descendant(of: viewport, matching: find.byType(Scrollable))
            .first,
      );
      await tester.pumpAndSettle();
      final scroll = tester.widget<CustomScrollView>(viewport).controller!;
      final offset = scroll.offset;
      expect(offset, greaterThan(500));
      await tester.tap(find.byKey(const ValueKey('entry-2')));
      await tester.pump(const Duration(milliseconds: 500));
      expect(scroll.offset, closeTo(offset, .01));
      expect(find.byType(LinearProgressIndicator), findsOneWidget);
      if (config.$3 == 'back') {
        await tester.tap(find.byType(BackButton).first);
        await tester.pumpAndSettle();
        expect(scroll.offset, 0);
      }
      pending.complete(
        IndexPage.fromJson({
          ...client.metadata(),
          if (config.$3 == 'missing') 'status': 'not-found',
          'entries': config.$3 == 'empty'
              ? []
              : [
                  for (var id = 100; id < 150; id++)
                    {...client.entry(id), 'parentId': 2},
                ],
        }),
      );
      await tester.pumpAndSettle();
      expect(
        find.byWidgetPredicate(
          (widget) => widget is LinearProgressIndicator && widget.value == null,
        ),
        findsNothing,
      );
      expect(find.text('正在读取目录…'), findsNothing);
      if (config.$3 == 'back') {
        expect(scroll.offset, 0);
        expect(find.byKey(const ValueKey('crumb-2')), findsNothing);
      } else {
        expect(
          scroll.offset,
          closeTo(offset.clamp(0.0, scroll.position.maxScrollExtent), .01),
        );
        if (config.$3 == 'empty' || config.$3 == 'missing') {
          expect(scroll.position.maxScrollExtent, lessThan(offset));
        }
      }
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    });
  }

  for (final config in [
    (const Size(360, 800), 1.0),
    (const Size(360, 800), 2.0),
    (const Size(1280, 900), 1.0),
  ]) {
    testWidgets(
      'long names fit viewport ${config.$1} text scale ${config.$2}',
      (tester) async {
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
        await tester.scrollUntilVisible(
          find.byKey(const ValueKey('entry-3')),
          150,
          scrollable: find
              .descendant(
                of: find.byType(CustomScrollView),
                matching: find.byType(Scrollable),
              )
              .first,
        );
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
      },
    );
  }
}
