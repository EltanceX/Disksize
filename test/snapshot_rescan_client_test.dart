import 'dart:convert';

import 'package:disksize/platform/generated/scanner_api.g.dart';
import 'package:disksize/platform/snapshot_rescan_client.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const prefix = 'dev.flutter.pigeon.disksize.ScannerHostApi.';
  final task = <String, Object?>{
    'status': 'ok',
    'taskId': 'job',
    'snapshotId': 'snapshot',
    'sessionId': 'session',
    'targetNodeId': 7,
    'expectedRevision': 42,
    'targetPathHex': 'ff0a',
    'pathTruncated': false,
    'state': 'preparing',
    'error': '',
    'active': true,
    'nodeCount': 0,
    'issueCount': 0,
    'createdAtMillis': 1,
    'startedAtMillis': 0,
    'scannedAtMillis': 0,
    'endedAtMillis': 0,
    'committedRevision': null,
    'cancellationRequested': false,
    'stagingCleaned': false,
  };
  void handler(String method, Future<Object?> Function(Object?)? callback) {
    messenger.setMockDecodedMessageHandler<Object?>(
      BasicMessageChannel<Object?>(
        '$prefix$method',
        ScannerHostApi.pigeonChannelCodec,
      ),
      callback,
    );
  }

  tearDown(() {
    for (final method in [
      'startSnapshotRescan',
      'getSnapshotRescan',
      'cancelSnapshotRescan',
    ]) {
      handler(method, null);
    }
  });

  test(
    'generated Pigeon channels carry indexed identity and raw path status',
    () async {
      handler('startSnapshotRescan', (arguments) async {
        expect(arguments, ['snapshot', 7, 42]);
        return [jsonEncode(task)];
      });
      final result = await AndroidSnapshotRescanClient().start(
        'snapshot',
        7,
        42,
      );
      expect(result.id, 'job');
      expect(result.pathHex, 'ff0a');
      expect(result.active, isTrue);
      expect(result.committedRevision, isNull);
    },
  );

  test('semantic rejection remains a structured contract error', () async {
    handler(
      'startSnapshotRescan',
      (_) async => [
        jsonEncode({'status': 'stale-revision'}),
      ],
    );
    await expectLater(
      AndroidSnapshotRescanClient().start('snapshot', 7, 41),
      throwsA(
        isA<SnapshotRescanException>().having(
          (e) => e.code,
          'code',
          'stale-revision',
        ),
      ),
    );
  });

  test('task lookup handles absent and durably completed tasks', () async {
    handler('getSnapshotRescan', (arguments) async {
      expect(arguments, ['job']);
      return [
        jsonEncode({'status': 'not-found'}),
      ];
    });
    final client = AndroidSnapshotRescanClient();
    expect(await client.task('job'), isNull);
    handler(
      'getSnapshotRescan',
      (_) async => [
        jsonEncode({
          ...task,
          'state': 'completed',
          'active': false,
          'committedRevision': 43,
          'stagingCleaned': true,
        }),
      ],
    );
    final result = (await client.task('job'))!;
    expect(result.committedRevision, 43);
    expect(result.stagingCleaned, isTrue);
    expect(result.active, isFalse);
  });

  test(
    'cancel keeps the post-commit boundary and propagates transport errors',
    () async {
      final client = AndroidSnapshotRescanClient();
      for (final status in [
        'cancellation-requested',
        'already-completed',
        'already-terminal',
      ]) {
        handler('cancelSnapshotRescan', (arguments) async {
          expect(arguments, ['job']);
          return [
            jsonEncode({'status': status}),
          ];
        });
        expect(await client.cancel('job'), status);
      }
      handler(
        'cancelSnapshotRescan',
        (_) async => ['service-unavailable', 'disconnected', null],
      );
      await expectLater(
        client.cancel('job'),
        throwsA(isA<PlatformException>()),
      );
    },
  );
}
