import 'dart:math' as math;
import 'dart:ui';

import '../../platform/index_client.dart';

const treemapEntryLimit = 100;
const treemapLabelLimit = 48;

final class TreemapWeight {
  const TreemapWeight(this.id, this.bytes);
  final int id, bytes;
  bool get grouped => id == -1;
}

final class TreemapRect {
  const TreemapRect(this.item, this.rect);
  final TreemapWeight item;
  final Rect rect;
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

TreemapRect? hitTreemap(List<TreemapRect> tiles, Offset point) {
  for (final tile in tiles) {
    if (tile.rect.contains(point)) return tile;
  }
  return null;
}
