import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class ExternalOpenButton extends StatefulWidget {
  const ExternalOpenButton({super.key, required this.onOpen});
  final Future<void> Function() onOpen;

  @override
  State<ExternalOpenButton> createState() => _ExternalOpenButtonState();
}

class _ExternalOpenButtonState extends State<ExternalOpenButton> {
  bool _busy = false;

  Future<void> _open() async {
    setState(() => _busy = true);
    try {
      await widget.onOpen();
    } catch (error) {
      if (!mounted) return;
      final message = error is PlatformException
          ? error.message ?? '无法打开此项目，请稍后重试。'
          : '无法打开此项目，请检查文件是否仍在及访问权限。';
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(message)));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => OutlinedButton.icon(
    onPressed: _busy ? null : _open,
    icon: const Icon(Icons.open_in_new, size: 18),
    label: Text(_busy ? '正在打开…' : '使用其他应用打开'),
  );
}
