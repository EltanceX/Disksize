import 'package:flutter/material.dart';

import '../features/scan/presentation/scan_dashboard_page.dart';
import '../platform/diagnostics_client.dart';
import '../platform/scan_client.dart';

class DiskSizeApp extends StatelessWidget {
  const DiskSizeApp({super.key, this.diagnosticsClient, this.scanClient});

  final DiagnosticsClient? diagnosticsClient;
  final ScanClient? scanClient;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'DiskSize',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF087F8C)),
        useMaterial3: true,
      ),
      home: ScanDashboardPage(
        client: scanClient ?? AndroidScanClient(),
        diagnosticsClient: diagnosticsClient ?? AndroidDiagnosticsClient(),
      ),
    );
  }
}
