import '../../platform/index_client.dart';

String formatBytes(int bytes) {
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB'];
  var value = bytes.abs().toDouble();
  var unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return '${bytes < 0 ? '−' : ''}${value.toStringAsFixed(unit == 0 ? 0 : 1)} ${units[unit]}';
}

String formatTime(int millis) => millis == 0
    ? '未记录'
    : DateTime.fromMillisecondsSinceEpoch(millis)
          .toLocal()
          .toString()
          .split('.')
          .first;

String sessionState(String state) => switch (state) {
  'running' => '正在扫描',
  'finalizing' => '正在聚合',
  'completed' => '扫描完成',
  'partial' => '部分完成',
  'cancelled' => '已取消',
  'interrupted' => '扫描已中断',
  'failed' => '扫描失败',
  _ => '等待索引',
};

String skipLabel(String skip) => switch (skip) {
  'mount-boundary' => '跳过：其他挂载',
  'pseudo-filesystem' => '跳过：伪文件系统',
  'directory-alias' => '跳过：目录别名',
  'resource-limit' => '跳过：资源上限',
  'cancelled' => '跳过：已取消',
  'changed-during-scan' => '跳过：扫描期间变化',
  'unknown-mount' => '跳过：未知挂载',
  _ => skip,
};

List<String> entryStates(IndexedEntry e) => [
  if (e.inaccessible) '不可访问',
  if (!e.metadataValid) '元数据未知',
  if (!e.complete) '未完成 / 有未知范围',
  if (e.sharedLink) '共享硬链接 · 计入 #${e.ownerId}',
  if (e.kind == 'symlink') '符号链接 · 不跟随',
  if (e.skipped != 'none') skipLabel(e.skipped),
];
