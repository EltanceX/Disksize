# DiskSize 开发文档

DiskSize 是面向开发者的 Android 存储空间分析工具，采用 Flutter、Kotlin 和 C++，支持普通权限与可选 root 模式。

- [第一版实现方案](./v1-design.md)：产品范围、系统架构、扫描规则、权限边界、数据设计和技术选型。
- [第一版实现步骤](./v1-implementation-plan.md)：分阶段任务、依赖顺序、环境准备命令和验收标准。
- [阶段 0 环境基线与验收](./environment-baseline.md)：实际工具版本、设备、受控测试数据和构建验证结果。
- [阶段 1 骨架与验收](./phase1-skeleton.md)：Pigeon、独立服务、JNI、自检界面和真机验证结果。
- [阶段 2 扫描核心与验收](./phase2-scanner.md)：只读遍历、容量口径、硬链接、挂载边界、原生测试及基准。
- [阶段 3 普通模式与生命周期](./phase3-ordinary-lifecycle.md)：扫描首页、权限、前台服务、状态持久化、取消与恢复验收。
- [阶段 4 Root 后端与验收](./phase4-root-backend.md)：显式授权、libsu RootService、二进制协议、原始名称、挂载视图和死亡恢复。
- [阶段 5 索引、聚合与分页验收](./phase5-index.md)：SQLite、硬链接归属、稳定游标、恢复与百万节点规模验证。
- [阶段 6 目录与大文件界面](./phase6-explorer.md)：卷入口、最近会话、目录层级、范围内大文件、详情及统计差额。
- [阶段 7 矩形树图](./phase7-treemap.md)：有界查询、squarified 布局、命中与下钻、容量面积和帧指标。
- [阶段 8 快照与增长对比](./phase8-snapshots.md)：不可变快照、兼容性、路径增长、未知范围、保留及删除。
- [保存快照删除性能优化](./snapshot-delete-performance.md)：约 19 万节点真机删除从 10.3 秒降至 2.6 秒、事务设置恢复、失败回滚及无损覆盖交付。
- [快照内文件夹局部重扫方案与实现步骤](./snapshot-subtree-rescan-plan.md)：步骤 1 至 6 均已完成，普通优化版已交付，原有快照保留。
- [局部重扫步骤 1：契约与回归基线](./snapshot-subtree-rescan-contract.md)：首版规则、状态和错误码、混合时间对照、测试入口及验证结果。
- [局部重扫步骤 2：schema 与持久任务](./snapshot-subtree-rescan-persistence.md)：schema 5 增量迁移、任务与暂存、ID 水位、提交标记、恢复与真机验证记录。
- [局部重扫步骤 3：数据库合并与重建](./snapshot-subtree-rescan-merge.md)：原子子树替换、全快照派生重建、取消/故障回滚、游标失效与隔离真机验证。
- [局部重扫步骤 4：扫描服务与桥接](./snapshot-subtree-rescan-service.md)：异步控制、前台服务、普通/Root 原始路径安全、即时取消、journal 恢复与真机回归。
- [局部重扫步骤 5：目录与快照界面](./snapshot-subtree-rescan-ui.md)：重扫入口与确认、进度/取消、持久任务重连、游标和原路径定位恢复、混合时间展示及布局/隔离真机界面验证。
- [局部重扫步骤 6：正确性、性能与交付](./snapshot-subtree-rescan-acceptance.md)：真实强杀与 ENOSPC、百万节点性能及查询尾延迟、Profile 优化审计、无损迁移和日常界面验收。

文档基线：2026-11-17。阶段 0～8 已完成；应用已支持普通/root 模式只读扫描、SQLite 聚合、稳定分页、目录和大文件浏览、矩形树图及快照增长对比。下一阶段是集成验证与首版交付；各阶段完成情况以实施清单和验收记录为准。
