# 阶段 5：持久索引与分页

## 增量扫描

应用入口使用 `stream_directory`；阶段 2 的 `scan_directory` 保留作为独立回归参考。
新扫描器逐项输出原始 metadata，不持有全树、不在提权进程聚合。
单线程 DFS 的目录栈上限 512 层，目录 FD 随深度有界；达到深度、节点或系统 FD 限制时明确返回不完整结果。
不跟随目录项符号链接，不跨 mount；打开目录后重新核对 inode/device/mount。
保持祖先目录 FD，避免用拼接的长路径重新打开目标；枚举结束检查目录时间戳变化。
该方式以单个 metadata producer 换取与总节点数无关的内存，并以 pipe 阻塞写形成背压。

v2 大端帧沿用 UUID、连续序号和 64 KiB payload 上限，增加完整 metadata 和目录关闭记录。
节点批次上限 256 条或约 32 KiB；原始名称和符号链接目标按字节传输。
普通和 root 使用同一协议；接收端必须核对预期 UID/PID。
v1 仅保留给原型回归工具，不用于应用的新索引。

## 持久化与查询约束

数据库由普通 UID 的 Coordinator 管理。root 仅向 pipe 写扫描记录。
数据库使用带版本的 SQLite schema、WAL、单写入线程和批次事务。
硬链接归属按同一 device/inode 下最小原始相对路径字节确定，随后由深到浅聚合。
扫描和聚合中的结果标记 provisional；异常退出保留索引并标为 interrupted。
子项、大文件、详情和错误查询限制单页大小；游标绑定会话、查询条件、revision 及稳定并列键。
版本发生变化时返回 stale-cursor，客户端需重新查询。

实施结果、性能基线和已知限制见[阶段 5 验收](./phase5-index.md)。
