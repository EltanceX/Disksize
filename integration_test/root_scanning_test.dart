import 'dart:convert';
import 'dart:io';

import 'package:disksize/platform/diagnostics_client.dart';
import 'package:disksize/platform/generated/scanner_api.g.dart';
import 'package:disksize/platform/scan_client.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  final client = AndroidScanClient();
  Future<ScanSnapshot> waitFor(
    String id,
    bool Function(ScanSnapshot) check,
  ) async {
    final until = DateTime.now().add(const Duration(seconds: 90));
    while (DateTime.now().isBefore(until)) {
      try {
        final value = await client.snapshot();
        if (value?.sessionId == id && check(value!)) return value;
      } catch (_) {
        /* Rebind after the coordinator was deliberately killed. */
      }
      await Future<void>.delayed(const Duration(milliseconds: 60));
    }
    throw StateError('Scan timed out: $id');
  }

  Future<RootAccessStatus> authorize() async {
    await client.requestRootAccess();
    final until = DateTime.now().add(const Duration(seconds: 50));
    while (DateTime.now().isBefore(until)) {
      final value = await client.rootStatus();
      if (value.state == 'ready') return value;
      if (value.state == 'unavailable') {
        throw StateError('Root unavailable: ${value.reason}');
      }
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    throw StateError('Root binding timed out');
  }

  Future<ScanSnapshot> scan(String path, String source) async {
    final id = await client.start(path, source);
    return waitFor(id, (value) => !value.active);
  }

  Future<String> owner(String path) async {
    final stat = await Process.run('/system/bin/stat', [
      '-c',
      '%u:%g:%a',
      path,
    ]);
    expect(stat.exitCode, 0);
    return (stat.stdout as String).trim();
  }

  testWidgets(
    'explicit root handshake, protected tree, raw bytes and ordinary ownership',
    (tester) async {
      final access = await client.accessStatus();
      expect((await client.rootStatus()).state, 'not-requested');
      await expectLater(
        client.start('/data/local/tmp/disksize-phase4', 'root'),
        throwsA(anything),
      );
      final denied = await scan('/data/local/tmp/disksize-phase4', 'path');
      expect(denied.state, 'failed');
      expect(denied.issues.first.code, 'permission-denied');
      // Let KernelSU observe this UID's su request before its filtered app list
      // is opened. An unrequested, freshly reinstalled UID may be absent there.
      await client.requestRootAccess();
      final firstRequestDeadline = DateTime.now().add(
        const Duration(seconds: 30),
      );
      var firstRequest = await client.rootStatus();
      while (firstRequest.state == 'requesting' &&
          DateTime.now().isBefore(firstRequestDeadline)) {
        await Future<void>.delayed(const Duration(milliseconds: 100));
        firstRequest = await client.rootStatus();
      }
      expect(firstRequest.state, anyOf('ready', 'unavailable'));
      if (firstRequest.state == 'unavailable') {
        expect(firstRequest.reason, 'root-denied');
        debugPrint('PHASE4_DENIAL_OK');
      }

      // Flutter may reinstall the test APK under a new UID. The host driver
      // grants only this development package in the root manager, then signals
      // through an ordinary-UID file after returning this activity to foreground.
      final grant = File('${access.ownPath}/phase4-root-ready');
      if (await grant.exists()) await grant.delete();
      debugPrint('PHASE4_AUTHORIZE');
    final deadline = DateTime.now().add(const Duration(seconds: 120));
      while (!await grant.exists() && DateTime.now().isBefore(deadline)) {
        await Future<void>.delayed(const Duration(milliseconds: 100));
      }
      expect(await grant.exists(), true);
      await grant.delete();
      await Future<void>.delayed(const Duration(milliseconds: 500));

      final root = await authorize();
      expect(root.uid, 0);
      expect(root.effectiveUid, 0);
      expect(root.mountNamespace, startsWith('mnt:['));
      expect(
        root.roots.any(
          (entry) => entry.path == '/data' && entry.filesystem == 'f2fs',
        ),
        true,
      );
      expect(
        root.roots.where((entry) => entry.aliasGroup.isNotEmpty).length,
        2,
      );
      final result = await scan('/data/local/tmp/disksize-phase4', 'root');
      expect(result.state, 'completed');
      expect(result.complete, true);
      expect(result.scannerUid, 0);
      expect(result.scannerPid, root.pid);
      expect(result.serviceUid, greaterThan(0));
      expect(result.servicePid, isNot(root.pid));
      expect(result.nodeCount, 11);
      expect(result.logicalBytes, 1073741856);
      expect(result.openFdsAtFinish, 0);
      final names = result.entries.map((entry) => entry.pathHex).toSet();
      expect(names, contains('696e76616c69645fff2e62696e'));
      expect(
        names,
        contains(
          utf8
              .encode('line\nbreak.bin')
              .map((b) => b.toRadixString(16).padLeft(2, '0'))
              .join(),
        ),
      );
      expect(names, contains('e4b8ade696872e62696e'));
      expect(names, contains('7370616365206e616d652e62696e'));
      debugPrint(
        'PHASE4_RAW_OK nodes=${result.nodeCount} logical=${result.logicalBytes} allocated=${result.allocatedBytes} rootPid=${root.pid}',
      );

      final baseline = await scan(
        '/data/local/tmp/disksize-phase0/run-20261115-02',
        'root',
      );
      expect(baseline.state, 'completed');
      expect(baseline.nodeCount, 169);
      expect(baseline.logicalBytes, 1073746077);
      expect(baseline.allocatedBytes, 660480);
      expect(baseline.previewTruncated, true);
    final backing = await scan('/data/media/0/Download/DiskSize-phase0/run-20261115-02', 'root');
    final emulated = await scan('/storage/emulated/0/Download/DiskSize-phase0/run-20261115-02', 'root');
    expect(backing.state, 'completed'); expect(emulated.state, 'completed');
    expect(backing.nodeCount, 4); expect(emulated.nodeCount, 4);
    expect(backing.logicalBytes, 4105); expect(emulated.logicalBytes, backing.logicalBytes);
    expect(emulated.rootMountId, isNot(backing.rootMountId));
    debugPrint('PHASE4_SHARED_ALIAS_OK backingMount=${backing.rootMountId} fuseMount=${emulated.rootMountId}');
      final missing = await scan(
        '/data/local/tmp/disksize-phase4/missing',
        'root',
      );
      expect(missing.state, 'failed');
      expect(missing.issues.single.code, 'not-found');

      final private = await Directory(access.ownPath)
          .createTemp('phase4-owner-');
      try {
        final file = File('${private.path}/private.txt');
        await file.writeAsString('private-data');
        final before = await owner(file.path);
        final privateScan = await scan(private.path, 'root');
        expect(privateScan.logicalBytes, 12);
        expect(await owner(file.path), before);
        expect(await file.readAsString(), 'private-data');
        final uid =
            (await AndroidDiagnosticsClient().readDiagnostics()).hostUid;
        expect(before.split(':').first, '$uid');
        final journal =
            '${Directory(access.ownPath).parent.path}/no_backup/scan-session.json';
        expect((await owner(journal)).split(':').first, '$uid');
        final normal = await scan(private.path, 'own');
        expect(normal.state, 'completed');
        expect(normal.scannerUid, uid);
        debugPrint(
          'PHASE4_OWNERSHIP_OK uid=$uid rootMount=${result.rootMountId}',
        );
      } finally {
        await private.delete(recursive: true);
      }
    },
    timeout: const Timeout(Duration(minutes: 4)),
  );

  testWidgets(
    'root cancellation, root death, coordinator death and explicit reconnect',
    (tester) async {
      final access = await client.accessStatus();
      final fixture = await Directory(access.ownPath)
          .createTemp('phase4-lifecycle-');
      try {
        var cursor = fixture.path;
        for (var depth = 0; depth < 450; depth++) {
          cursor += '/d';
          await Directory(cursor).create();
          for (var index = 0; index < 48; index++) {
            await File('$cursor/f$index').writeAsString('payload');
          }
        }
        var root = await authorize();
        var id = await client.start(fixture.path, 'root');
        await waitFor(id, (value) => value.nodeCount > 10 || !value.active);
        await client.cancel(id);
        final cancelled = await waitFor(id, (value) => !value.active);
        expect(cancelled.state, 'cancelled');
        expect(cancelled.complete, false);
        expect(cancelled.openFdsAtFinish, 0);
        debugPrint('PHASE4_CANCEL_OK');

        id = await client.start(fixture.path, 'root');
        await waitFor(id, (value) => value.nodeCount > 10 && value.active);
        debugPrint('PHASE4_KILL_ROOT ${root.pid}');
        final died = await waitFor(id, (value) => !value.active);
        expect(died.state, 'interrupted');
        expect(died.complete, false);
        expect(
          died.reason,
          anyOf('root-process-died', 'root-pipe-or-protocol-failure'),
        );
        expect((await client.rootStatus()).state, 'unavailable');
        root = await authorize();
        final recovered = await scan('/data/local/tmp/disksize-phase4', 'root');
        expect(recovered.state, 'completed');
        debugPrint('PHASE4_ROOT_DEATH_OK');

        id = await client.start(fixture.path, 'root');
        final running = await waitFor(
          id,
          (value) => value.nodeCount > 10 && value.active,
        );
        expect(
          Process.killPid(running.servicePid, ProcessSignal.sigkill),
          true,
        );
        final restored = await waitFor(id, (value) => !value.active);
        expect(restored.state, 'interrupted');
        expect(restored.reason, 'process-interrupted');
        expect((await client.rootStatus()).state, 'not-requested');
        debugPrint('PHASE4_CLIENT_DIED ${root.pid}');
        await Future<void>.delayed(const Duration(seconds: 2));
        await authorize();
        final again = await scan('/data/local/tmp/disksize-phase4', 'root');
        expect(again.state, 'completed');
        debugPrint('PHASE4_CLIENT_DEATH_OK');
      } finally {
        final active = await client.snapshot();
        if (active?.active == true) {
          await client.cancel(active!.sessionId);
          await waitFor(active.sessionId, (value) => !value.active);
        }
        await fixture.delete(recursive: true);
      }
    },
    timeout: const Timeout(Duration(minutes: 6)),
  );
}
