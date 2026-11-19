import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/semantics.dart';

import '../../platform/index_client.dart';
import '../../platform/scan_client.dart';
import 'explorer_format.dart';
import 'treemap_layout.dart';
import 'external_open_button.dart';

class TreemapPanel extends StatefulWidget {
  const TreemapPanel({
    super.key,
    required this.page,
    required this.directory,
    required this.allocated,
    required this.onSelect,
    required this.onOpen,
    this.onDoubleTapOpen,
    this.onOpenExternal,
    this.onRescan,
    this.selectedId,
    required this.onDetail,
    required this.onShowList,
    required this.depth,
  });
  final IndexPage page;
  final IndexDetail directory;
  final bool allocated;
  final int depth;
  final ValueChanged<IndexedEntry?> onSelect;
  final ValueChanged<IndexedEntry> onOpen, onDetail;
  final ValueChanged<IndexedEntry>? onDoubleTapOpen;
  final Future<void> Function(IndexedEntry)? onOpenExternal;
  final ValueChanged<IndexedEntry>? onRescan;
  final int? selectedId;
  final ValueChanged<int> onShowList;
  @override
  State<TreemapPanel> createState() => _TreemapPanelState();
}

class _TreemapPanelState extends State<TreemapPanel> {
  int? _selected;
  int? _tapId;

  @override
  void initState() {
    super.initState();
    _selected = widget.selectedId;
  }

  @override
  void didUpdateWidget(TreemapPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.selectedId != widget.selectedId) {
      _selected = widget.selectedId;
    }
    if (oldWidget.page.revision != widget.page.revision ||
        oldWidget.directory.entry.id != widget.directory.entry.id ||
        oldWidget.allocated != widget.allocated ||
        oldWidget.depth != widget.depth) {
      _selected = widget.selectedId;
      _tapId = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final summary = widget.page.treemap;
    final entries = {
      widget.directory.entry.id: widget.directory.entry,
      for (final e in widget.page.entries.take(treemapEntryLimit)) e.id: e,
      for (final group in summary?.groups ?? <TreemapGroup>[])
        for (final entry in group.entries) entry.id: entry,
    };
    String entryLabel(IndexedEntry entry) => entry.nameHex.isEmpty
        ? widget.page.session.rootPath
        : displayScanPath(entry.nameHex);
    final selected = entries[_selected];
    void select(int id) {
      if (_selected == id) return;
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
                  final tiles = nestedTreemap(
                    root: widget.directory.entry,
                    children: widget.page.entries,
                    groups: summary.groups,
                    total: summary.totalBytes,
                    depth: widget.depth,
                    allocated: widget.allocated,
                    size: size,
                    headerHeight:
                        MediaQuery.textScalerOf(context).scale(14) * 1.25 + 10,
                  );
                  final painter = TreemapPainter(
                    tiles: tiles,
                    entries: entries,
                    selected: _selected,
                    allocated: widget.allocated,
                    textScaler: MediaQuery.textScalerOf(context),
                    direction: Directionality.of(context),
                    onSelect: select,
                    rootId: widget.directory.entry.id,
                    rootLabel: entryLabel(widget.directory.entry),
                  );
                  return RepaintBoundary(
                    child: RawGestureDetector(
                      key: const Key('treemap-canvas'),
                      behavior: HitTestBehavior.opaque,
                      gestures: {
                        SerialTapGestureRecognizer:
                            GestureRecognizerFactoryWithHandlers<
                              SerialTapGestureRecognizer
                            >(
                              () => SerialTapGestureRecognizer(
                                allowedButtonsFilter: (buttons) =>
                                    buttons == kPrimaryButton,
                              ),
                              (recognizer) => recognizer
                                ..onSerialTapCancel = (_) {
                                  _tapId = null;
                                }
                                ..onSerialTapUp = (event) {
                                  final id = hitTreemap(
                                    tiles,
                                    event.localPosition,
                                  )?.item.id;
                                  final previous = _tapId;
                                  _tapId = id;
                                  if (id == null) return;
                                  // Select on every completed tap, without waiting
                                  // for the double-tap timeout. Drags still cancel.
                                  select(id);
                                  final entry = entries[id];
                                  if (event.count > 1 &&
                                      previous == id &&
                                      entry?.kind == 'directory' &&
                                      entry!.id != widget.directory.entry.id) {
                                    (widget.onDoubleTapOpen ?? widget.onOpen)(
                                      entry,
                                    );
                                  }
                                },
                            ),
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
            Wrap(
              spacing: 6,
              children: [
                for (var level = 0; level <= widget.depth; level++)
                  Chip(
                    visualDensity: VisualDensity.compact,
                    backgroundColor: TreemapPainter.levelColor(level),
                    label: Text(
                      '级别 $level',
                      style: TextStyle(
                        color: TreemapPainter.levelTextColor(level),
                      ),
                    ),
                  ),
              ],
            ),
            const Text(
              '当前目录为级别 0，下钻后重新计级。颜色表示深度；同一目录内容区域的面积按所选容量分配，标题栏用于表示层级。灰色为小项、其余子项与目录自身占用，零贡献条目不绘制。',
              style: TextStyle(fontSize: 12),
            ),
            if (summary.truncated)
              const Text('已达到部分目录的显示预算，未展开的子树保留为整体；可选中目录继续下钻。'),
            const SizedBox(height: 8),
            if (_selected != null && _selected! < 0) ...[
              const Text(
                '其余小项 / 目录自身',
                style: TextStyle(fontWeight: FontWeight.bold),
              ),
              Text(
                '所属目录：${entryLabel(entries[-_selected!]!)}。此区域合并了未单独绘制的已知容量。',
              ),
              TextButton(
                onPressed: () => widget.onShowList(-_selected!),
                child: const Text('查看所属目录列表'),
              ),
            ] else if (selected != null) ...[
              Text(
                entryLabel(selected),
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
                  if (selected.kind == 'directory' &&
                      selected.id != widget.directory.entry.id)
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
                  if (widget.onOpenExternal != null &&
                      (selected.kind == 'directory' ||
                          selected.kind == 'regular'))
                    ExternalOpenButton(
                      key: const Key('treemap-open-external'),
                      onOpen: () => widget.onOpenExternal!(selected),
                    ),
                  if (selected.kind == 'directory' && widget.onRescan != null)
                    OutlinedButton.icon(
                      key: const Key('treemap-rescan'),
                      onPressed: () => widget.onRescan!(selected),
                      icon: const Icon(Icons.manage_search),
                      label: const Text('重新扫描此文件夹'),
                    ),
                ],
              ),
            ] else
              const Text('单击色块选中项目，双击目录色块或标题栏进入目录；也可通过按钮进入目录或查看详情。'),
          ],
          TextButton.icon(
            key: const Key('treemap-list'),
            onPressed: () => widget.onShowList(widget.directory.entry.id),
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
    required this.rootId,
    required this.rootLabel,
  });
  final List<TreemapRect> tiles;
  final Map<int, IndexedEntry> entries;
  final int? selected;
  final bool allocated;
  final TextScaler textScaler;
  final TextDirection direction;
  final ValueChanged<int> onSelect;
  final int rootId;
  final String rootLabel;
  static const _palette = [
    Color(0xFF174F78),
    Color(0xFF176C96),
    Color(0xFF2A82AB),
    Color(0xFF439ABD),
    Color(0xFF60AEC9),
    Color(0xFF7DC0D4),
    Color(0xFF9AD1DF),
  ];
  static Color levelColor(int level) => _palette[level.clamp(0, 6)];
  static Color levelTextColor(int level) =>
      levelColor(level).computeLuminance() > .3 ? Colors.black87 : Colors.white;
  Color color(TreemapRect tile) =>
      tile.item.grouped ? const Color(0xFF65717A) : levelColor(tile.level);
  String label(TreemapRect tile) => tile.item.grouped
      ? '其余小项 / 目录自身'
      : tile.item.id == rootId
      ? rootLabel
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
      final labelRect = tile.hitRect;
      if (labels >= treemapLabelLimit ||
          labelRect.width < 56 ||
          labelRect.height < textScaler.scale(14) + 8) {
        continue;
      }
      final foreground = tile.item.grouped
          ? Colors.white
          : levelTextColor(tile.level);
      final text = TextPainter(
        text: TextSpan(
          text: tile.expanded
              ? '${label(tile)} · ${formatBytes(tile.item.bytes)}'
              : label(tile),
          style: TextStyle(
            color: foreground,
            fontSize: 12,
            fontWeight: FontWeight.w600,
          ),
        ),
        textDirection: direction,
        textScaler: textScaler,
        maxLines: 1,
        ellipsis: '…',
      )..layout(maxWidth: labelRect.width - 16);
      final amount = TextPainter(
        text: TextSpan(
          text: formatBytes(tile.item.bytes),
          style: TextStyle(color: foreground, fontSize: 12),
        ),
        textDirection: direction,
        textScaler: textScaler,
        maxLines: 1,
        ellipsis: '…',
      )..layout(maxWidth: labelRect.width - 16);
      if (text.height <= labelRect.height - 8) {
        canvas.save();
        canvas.clipRect(labelRect.deflate(2));
        text.paint(canvas, labelRect.topLeft + const Offset(8, 4));
        if (!tile.expanded &&
            text.height + amount.height + 2 <= labelRect.height - 12) {
          amount.paint(canvas, labelRect.topLeft + Offset(8, 6 + text.height));
        }
        canvas.restore();
        labels++;
      }
      text.dispose();
      amount.dispose();
    }
    for (final tile in tiles.where((tile) => tile.item.id == selected)) {
      canvas.drawRect(
        tile.rect.deflate(2),
        Paint()
          ..color = const Color(0xFFFFE29A)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 4,
      );
    }
  }

  @override
  SemanticsBuilderCallback get semanticsBuilder =>
      (size) => [
        for (final tile in tiles)
          CustomPainterSemantics(
            rect: tile.hitRect,
            properties: SemanticsProperties(
              label:
                  '级别 ${tile.level}，${label(tile)}，${formatBytes(tile.item.bytes)}'
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
