import 'dart:math' as math;
import 'dart:ui';

import '../../platform/index_client.dart';

const treemapEntryLimit = 100;
const treemapLabelLimit = 48;
const treemapNodeLimit = 300;
const treemapRectangleLimit = 350;

final class TreemapWeight {
  const TreemapWeight(this.id, this.bytes);
  final int id, bytes;
  bool get grouped => id < 0;
}

final class TreemapRect {
  const TreemapRect(
    this.item,
    this.rect, {
    this.level = 1,
    this.parentId = 0,
    this.headerHeight = 0,
  });
  final TreemapWeight item;
  final Rect rect;
  final int level, parentId;
  final double headerHeight;
  bool get expanded => headerHeight > 0;
  Rect get hitRect => expanded
      ? Rect.fromLTWH(rect.left, rect.top, rect.width, headerHeight)
      : rect;
}

/// Only the current directory's largest entries reach Dart. Everything omitted,
/// including the directory's own blocks, retains its area in one remainder.
List<TreemapWeight> treemapWeights(
  List<IndexedEntry> entries,
  int total,
  bool allocated,
  Size size, {
  double minimumArea = 900,
}) {
  if (total <= 0 || size.isEmpty || !size.isFinite) return const [];
  final weights = <TreemapWeight>[];
  var represented = 0;
  for (final entry in entries.take(treemapEntryLimit)) {
    final amount = allocated ? entry.allocatedBytes : entry.logicalBytes;
    if (amount <= 0 ||
        amount / total * size.width * size.height < minimumArea) {
      continue;
    }
    if (represented + amount > total) return const [];
    weights.add(TreemapWeight(entry.id, amount));
    represented += amount;
  }
  if (represented < total) weights.add(TreemapWeight(-1, total - represented));
  weights.sort((a, b) {
    final bySize = b.bytes.compareTo(a.bytes);
    return bySize == 0 ? a.id.compareTo(b.id) : bySize;
  });
  return weights;
}

/// Squarified strips; at most 101 rectangles, with no recursive widget tree.
List<TreemapRect> squarify(List<TreemapWeight> items, Size size) {
  if (items.isEmpty || size.isEmpty || !size.isFinite) return const [];
  assert(items.length <= treemapEntryLimit + 1);
  final sorted = items.where((e) => e.bytes > 0).toList()
    ..sort((a, b) {
      final bySize = b.bytes.compareTo(a.bytes);
      return bySize == 0 ? a.id.compareTo(b.id) : bySize;
    });
  final total = sorted.fold<int>(0, (sum, e) => sum + e.bytes);
  if (total <= 0) return const [];
  final areas = sorted
      .map((e) => e.bytes / total * size.width * size.height)
      .toList();
  var remaining = Offset.zero & size;
  final output = <TreemapRect>[];
  var start = 0;
  while (start < sorted.length && !remaining.isEmpty) {
    final side = math.min(remaining.width, remaining.height);
    var end = start + 1;
    var sum = areas[start];
    double worst(double sum, double max, double min) => math.max(
      side * side * max / (sum * sum),
      sum * sum / (side * side * min),
    );
    var ratio = worst(sum, areas[start], areas[start]);
    while (end < sorted.length) {
      final nextSum = sum + areas[end];
      final nextRatio = worst(nextSum, areas[start], areas[end]);
      if (nextRatio > ratio) break;
      ratio = nextRatio;
      sum = nextSum;
      end++;
    }
    final vertical = remaining.width >= remaining.height;
    final thickness = end == sorted.length
        ? (vertical ? remaining.width : remaining.height)
        : (sum / side).clamp(
            0.0,
            vertical ? remaining.width : remaining.height,
          );
    var position = vertical ? remaining.top : remaining.left;
    for (var i = start; i < end; i++) {
      final edge = i == end - 1
          ? (vertical ? remaining.bottom : remaining.right)
          : position + areas[i] / thickness;
      final rect = vertical
          ? Rect.fromLTRB(
              remaining.left,
              position,
              remaining.left + thickness,
              edge,
            )
          : Rect.fromLTRB(
              position,
              remaining.top,
              edge,
              remaining.top + thickness,
            );
      output.add(TreemapRect(sorted[i], rect));
      position = edge;
    }
    remaining = vertical
        ? Rect.fromLTRB(
            remaining.left + thickness,
            remaining.top,
            remaining.right,
            remaining.bottom,
          )
        : Rect.fromLTRB(
            remaining.left,
            remaining.top + thickness,
            remaining.right,
            remaining.bottom,
          );
    start = end;
  }
  return List.unmodifiable(output);
}

/// Children overlap their ancestor's frame, so the deepest painted item wins.
TreemapRect? hitTreemap(List<TreemapRect> tiles, Offset point) {
  for (final tile in tiles.reversed) {
    if (tile.rect.contains(point)) return tile;
  }
  return null;
}

/// A bounded hierarchy of paint records, with a title band on expanded folders.
/// Sibling proportions refer to their parent's content area, excluding its band.
List<TreemapRect> nestedTreemap({
  required IndexedEntry root,
  required List<IndexedEntry> children,
  required List<TreemapGroup> groups,
  required int total,
  required int depth,
  required bool allocated,
  required Size size,
  required double headerHeight,
  double minimumArea = 900,
}) {
  if (total <= 0 || size.isEmpty || !size.isFinite) return const [];
  final branches = {for (final group in groups.take(48)) group.parentId: group};
  final output = <TreemapRect>[];
  var nodes = 0;
  void visit(
    IndexedEntry entry,
    Rect rect,
    int level,
    int parentId,
    int bytes,
  ) {
    if (output.length >= treemapRectangleLimit) return;
    final branch = branches[entry.id];
    final items = level == 0 ? children : branch?.entries;
    final canExpand =
        entry.kind == 'directory' &&
        level < depth &&
        items != null &&
        items.isNotEmpty &&
        rect.width >= 48 &&
        rect.height >= headerHeight + 28 &&
        nodes < treemapNodeLimit;
    final band = canExpand ? headerHeight : 0.0;
    output.add(
      TreemapRect(
        TreemapWeight(entry.id, bytes),
        rect,
        level: level,
        parentId: parentId,
        headerHeight: band,
      ),
    );
    if (!canExpand) return;
    final content = Rect.fromLTRB(
      rect.left + 2,
      rect.top + band,
      rect.right - 2,
      rect.bottom - 2,
    );
    final available = (treemapNodeLimit - nodes).clamp(0, treemapEntryLimit);
    final weights = treemapWeights(
      items.take(available).toList(),
      bytes,
      allocated,
      content.size,
      minimumArea: minimumArea,
    );
    for (final tile in squarify(weights, content.size)) {
      final positioned = tile.rect.shift(content.topLeft);
      if (tile.item.grouped) {
        output.add(
          TreemapRect(
            TreemapWeight(-entry.id, tile.item.bytes),
            positioned,
            level: level + 1,
            parentId: entry.id,
          ),
        );
      } else {
        final child = items.firstWhere((item) => item.id == tile.item.id);
        nodes++;
        visit(child, positioned, level + 1, entry.id, tile.item.bytes);
      }
    }
  }

  visit(root, Offset.zero & size, 0, 0, total);
  return List.unmodifiable(output);
}
