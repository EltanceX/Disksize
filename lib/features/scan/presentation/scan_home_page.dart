import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../platform/diagnostics_client.dart';
import '../../../platform/generated/scanner_api.g.dart';

class ScanHomePage extends StatefulWidget {
  const ScanHomePage({super.key, required this.diagnosticsClient});

  final DiagnosticsClient diagnosticsClient;

  @override
  State<ScanHomePage> createState() => _ScanHomePageState();
}

class _ScanHomePageState extends State<ScanHomePage> {
  NativeDiagnostics? _diagnostics;
  String? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _checkEnvironment();
  }

  Future<void> _checkEnvironment() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final diagnostics = await widget.diagnosticsClient.readDiagnostics();
      if (!mounted) return;
      setState(() {
        _diagnostics = diagnostics;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _diagnostics = null;
        _loading = false;
        _error = error is PlatformException
            ? error.message ?? '无法连接扫描服务，请重试'
            : '运行环境检查失败，请重试';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final diagnostics = _diagnostics;
    final healthy = diagnostics?.selfTestPassed == true;
    final title = _loading
        ? '正在检查运行环境'
        : _error != null
        ? '检查失败'
        : healthy
        ? '引擎已就绪'
        : '自检未通过';

    return Scaffold(
      appBar: AppBar(title: const Text('DiskSize')),
      body: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 760),
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
            children: [
              Text('了解设备空间', style: text.headlineMedium),
              const SizedBox(height: 8),
              Text('面向开发者的存储分析工具', style: text.bodyLarge),
              const SizedBox(height: 20),
              Card.filled(
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          if (_loading)
                            const SizedBox.square(
                              dimension: 24,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          else
                            Icon(
                              healthy
                                  ? Icons.check_circle
                                  : Icons.error_outline,
                              color: healthy ? colors.primary : colors.error,
                            ),
                          const SizedBox(width: 12),
                          Expanded(child: Text(title, style: text.titleLarge)),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Text(
                        _error ??
                            (_loading
                                ? '正在连接扫描服务并检查原生引擎。'
                                : healthy
                                ? '扫描服务正在普通应用权限下独立运行。'
                                : '原生引擎未通过运行环境检查。'),
                      ),
                      const SizedBox(height: 16),
                      FilledButton.tonalIcon(
                        key: const Key('retry-diagnostics'),
                        onPressed: _loading ? null : _checkEnvironment,
                        icon: const Icon(Icons.refresh),
                        label: const Text('重新检查'),
                      ),
                    ],
                  ),
                ),
              ),
              if (!_loading && diagnostics != null) ...[
                const SizedBox(height: 16),
                Text('运行环境', style: text.titleMedium),
                const SizedBox(height: 8),
                Card.outlined(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      children: [
                        _InfoRow('原生核心', 'v${diagnostics.coreVersion}'),
                        _InfoRow(
                          '架构',
                          '${diagnostics.nativeAbi} · ${diagnostics.pointerBits} 位',
                        ),
                        _InfoRow('内存页', '${diagnostics.pageSizeBytes} 字节'),
                        _InfoRow('服务进程', diagnostics.serviceProcessName),
                        _InfoRow('应用 UID', '${diagnostics.hostUid}'),
                        _InfoRow('服务 UID', '${diagnostics.serviceUid}'),
                        _InfoRow('应用 PID', '${diagnostics.hostProcessId}'),
                        _InfoRow('服务 PID', '${diagnostics.serviceProcessId}'),
                        _InfoRow(
                          '进程边界',
                          diagnostics.serviceProcessId !=
                                      diagnostics.hostProcessId &&
                                  diagnostics.serviceUid == diagnostics.hostUid
                              ? '已独立运行'
                              : '异常',
                        ),
                        _InfoRow('原生自检', healthy ? '通过' : '未通过'),
                        _InfoRow('应用目录', diagnostics.appDataDirectory),
                      ],
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow(this.label, this.value);

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 104,
            child: Text(
              label,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(child: SelectableText(value)),
        ],
      ),
    );
  }
}
