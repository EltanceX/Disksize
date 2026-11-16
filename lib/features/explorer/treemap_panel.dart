import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';

import '../../platform/index_client.dart';
import '../../platform/scan_client.dart';
import 'explorer_format.dart';
import 'treemap_layout.dart';

class TreemapPanel extends StatefulWidget {
  const TreemapPanel({
    super.key,
    required this.page,
    required this.directory,
    required this.allocated,
    required this.onSelect,
    required this.onOpen,
    required this.onDetail,
    required this.onShowList,
  });
  final IndexPage page;
  final IndexDetail directory;
  final bool allocated;
  final ValueChanged<IndexedEntry?> onSelect;
  final ValueChanged<IndexedEntry> onOpen, onDetail;
  final VoidCallback onShowList;
  @override
  State<TreemapPanel> createState() => _TreemapPanelState();
}

class _TreemapPanelState extends State<TreemapPanel> {
  int? _selected;

  @override
  void didUpdateWidget(TreemapPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.page.revision != widget.page.revision ||
        oldWidget.directory.entry.id != widget.directory.entry.id ||
        oldWidget.allocated != widget.allocated) {
      _selected = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final summary = widget.page.treemap;
    final entries = {
      for (final e in widget.page.entries.take(treemapEntryLimit)) e.id: e,
    };
    final selected = entries[_selected];
    void select(int id) {
      setState(() => _selected = id);
      widget.onSelect(entries[id]);
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (widget.page.provisional)
            const Text('扫描或聚合尚未完成，暂不绘制面积比例。可切换目录查看已知条目。')
          else if (summary == null)
            const Text('树图数据暂不可用，请刷新索引。')
          else ...[
            if (!widget.directory.entry.complete || summary.unknownCount > 0)
              const Padding(
                padding: EdgeInsets.only(bottom: 8),
                child: Text('此范围未完成：面积仅表示已知容量，未知范围不按零容量计算。△ 表示条目含未知范围。'),
              ),
            if (summary.totalBytes <= 0)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 24),
                child: Text(
                  widget.directory.entry.complete
                      ? '当前口径的已知容量为 0 B，没有可绘制的面积。'
                      : '尚无可绘制的已知容量，仍有未知范围。',
                ),
              )
            else
              LayoutBuilder(
                builder: (context, constraints) {
                  final size = Size(
                    constraints.maxWidth,
                    (constraints.maxWidth * .62).clamp(260.0, 520.0),
                  );
                  final weights = treemapWeights(
                    widget.page.entries,
                    summary.totalBytes,
                    widget.allocated,
                    size,
                  );
                  final tiles = squarify(weights, size);
                  final painter = TreemapPainter(
                    tiles: tiles,
                    entries: entries,
                    selected: _selected,
                    allocated: widget.allocated,
                    textScaler: MediaQuery.textScalerOf(context),
                    direction: Directionality.of(context),
                    onSelect: select,
                  );
                  return RepaintBoundary(
                    child: GestureDetector(
                      key: const Key('treemap-canvas'),
                      behavior: HitTestBehavior.opaque,
                      onTapUp: (event) {
                        final tile = hitTreemap(tiles, event.localPosition);
                        if (tile != null) select(tile.item.id);
                      },
                      child: CustomPaint(size: size, painter: painter),
                    ),
                  );
                },
              ),
            const SizedBox(height: 8),
            Text(
              '${summary.childCount} 个直接子项 · ${summary.zeroCount} 个零贡献条目 · ${summary.unknownCount} 个含未知范围',
            ),
            const Text(
              '颜色区分项目，面积表示所选口径的已知容量。小项、其余子项与目录自身占用合并为灰色区域；零贡献条目不绘制面积。',
              style: TextStyle(fontSize: 12),
            ),
            const SizedBox(height: 8),
            if (_selected == -1) ...[
              const Text(
                '其余小项 / 目录自身',
                style: TextStyle(fontWeight: FontWeight.bold),
              ),
              const Text('此区域合并了未单独绘制的已知容量。切换目录列表可查看子项，目录自身 blocks 可在目录详情查看。'),
            ] else if (selected != null) ...[
              Text(
                displayScanPath(selected.nameHex),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
              Text(
                '${formatBytes(widget.allocated ? selected.allocatedBytes : selected.logicalBytes)} · '
                '${((widget.allocated ? selected.allocatedBytes : selected.logicalBytes) / summary.totalBytes * 100).toStringAsFixed(1)}%',
              ),
              for (final state in entryStates(selected)) Text(state),
              Wrap(
                spacing: 8,
                children: [
                  if (selected.kind == 'directory')
                    FilledButton.icon(
                      key: const Key('treemap-open'),
                      onPressed: () => widget.onOpen(selected),
                      icon: const Icon(Icons.folder_open),
                      label: const Text('进入目录'),
                    ),
                  OutlinedButton.icon(
                    key: const Key('treemap-detail'),
                    onPressed: () => widget.onDetail(selected),
                    icon: const Icon(Icons.info_outline),
                    label: const Text('查看详情'),
                  ),
                ],
              ),
            ] else
              const Text('点击色块选中项目，再进入目录或查看详情。'),
          ],
          TextButton.icon(
            key: const Key('treemap-list'),
            onPressed: widget.onShowList,
            icon: const Icon(Icons.list),
            label: const Text('查看目录列表'),
          ),
        ],
      ),
    );
  }
}

class TreemapPainter extends CustomPainter {
  TreemapPainter({
    required this.tiles,
    required this.entries,
    required this.selected,
    required this.allocated,
    required this.textScaler,
    required this.direction,
    required this.onSelect,
  });
  final List<TreemapRect> tiles;
  final Map<int, IndexedEntry> entries;
  final int? selected;
  final bool allocated;
  final TextScaler textScaler;
  final TextDirection direction;
  final ValueChanged<int> onSelect;
  static const _palette = [
    Color(0xFF087F8C),
    Color(0xFF3D6585),
    Color(0xFF8C5F34),
    Color(0xFF6B578B),
    Color(0xFF39735C),
    Color(0xFF8D4D60),
    Color(0xFF5165A0),
  ];
  Color color(TreemapRect tile) => tile.item.grouped
      ? const Color(0xFF65717A)
      : _palette[tile.item.id % _palette.length];
  String label(TreemapRect tile) => tile.item.grouped
      ? '其余小项 / 目录自身'
      : displayScanPath(entries[tile.item.id]!.nameHex);

  @override
  void paint(Canvas canvas, Size size) {
    var labels = 0;
    for (final tile in tiles) {
      final rect = tile.rect;
      canvas.drawRect(rect, Paint()..color = color(tile));
      canvas.drawRect(
        rect.deflate(.5),
        Paint()
          ..color = Colors.white.withValues(alpha: .65)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1,
      );
      final entry = entries[tile.item.id];
      if (entry != null &&
          (!entry.complete || !entry.metadataValid) &&
          rect.shortestSide >= 16) {
        canvas.drawPath(
          Path()
            ..moveTo(rect.right - 14, rect.top + 2)
            ..lineTo(rect.right - 2, rect.top + 14)
            ..lineTo(rect.right - 2, rect.top + 2)
            ..close(),
          Paint()..color = const Color(0xFFFFD978),
        );
      }
      if (tile.item.id == selected) {
        canvas.drawRect(
          rect.deflate(2),
          Paint()
            ..color = const Color(0xFFFFE29A)
            ..style = PaintingStyle.stroke
            ..strokeWidth = 4,
        );
      }
      if (labels >= treemapLabelLimit ||
          rect.width < 72 ||
          rect.height < textScaler.scale(32) + 12) {
        continue;
      }
      final text = TextPainter(
        text: TextSpan(
          text: label(tile),
          style: const TextStyle(
            color: Colors.white,
            fontSize: 12,
            fontWeight: FontWeight.w600,
          ),
        ),
        textDirection: direction,
        textScaler: textScaler,
        maxLines: 1,
        ellipsis: '…',
      )..layout(maxWidth: rect.width - 16);
      final amount = TextPainter(
        text: TextSpan(
          text: formatBytes(tile.item.bytes),
          style: const TextStyle(color: Colors.white, fontSize: 12),
        ),
        textDirection: direction,
        textScaler: textScaler,
        maxLines: 1,
        ellipsis: '…',
      )..layout(maxWidth: rect.width - 16);
      if (text.height + amount.height + 2 <= rect.height - 12) {
        canvas.save();
        canvas.clipRect(rect.deflate(4));
        text.paint(canvas, rect.topLeft + const Offset(8, 6));
        amount.paint(canvas, rect.topLeft + Offset(8, 8 + text.height));
        canvas.restore();
        labels++;
      }
      text.dispose();
      amount.dispose();
    }
  }

  @override
  SemanticsBuilderCallback get semanticsBuilder =>
      (size) => [
        for (final tile in tiles)
          CustomPainterSemantics(
            rect: tile.rect,
            properties: SemanticsProperties(
              label:
                  '${label(tile)}，${formatBytes(tile.item.bytes)}'
                  '${entries[tile.item.id]?.complete == false ? '，有未知范围' : ''}',
              textDirection: direction,
              button: true,
              selected: tile.item.id == selected,
              onTap: () => onSelect(tile.item.id),
            ),
          ),
      ];
  @override
  bool shouldRepaint(TreemapPainter oldDelegate) => true;
  @override
  bool shouldRebuildSemantics(TreemapPainter oldDelegate) => true;
}
