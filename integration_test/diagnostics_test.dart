import 'package:disksize/platform/diagnostics_client.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('Pigeon reaches JNI in an ordinary separate service process', (
    tester,
  ) async {
    final client = AndroidDiagnosticsClient();
    final first = await client.readDiagnostics();

    expect(first.coreVersion, '0.1.0');
    expect(first.protocolVersion, 1);
    expect(first.nativeAbi, anyOf('arm64-v8a', 'x86_64'));
    expect(first.hostUid, greaterThan(0));
    expect(first.serviceUid, first.hostUid);
    expect(first.effectiveUid, first.hostUid);
    expect(first.serviceProcessId, greaterThan(0));
    expect(first.serviceProcessId, isNot(first.hostProcessId));
    expect(first.serviceProcessName, 'com.example.disksize:scanner');
    expect(first.appDataDirectory, endsWith('/com.example.disksize/files'));
    expect(first.pointerBits, 64);
    expect(first.pageSizeBytes, greaterThan(0));
    expect(first.selfTestPassed, isTrue);

    final second = await client.readDiagnostics();
    expect(second.serviceProcessId, first.serviceProcessId);
    expect(second.serviceUid, first.serviceUid);
  });
}
