# AutoLedger 系统改造方案（无用户期）

> **执行进度（持续更新）**
> - ✅ **W1 数据模型扩展通道** —— 已完成：`LedgerTransaction.extras`(JSON)、`Category.kind/monthlyBudgetMinor/archived`；`DATABASE_VERSION`/`BACKUP_VERSION` → 3；**未发布期不做迁移**，改用 `fallbackToDestructiveMigration()` 重建表；备份序列化同步新字段。构建通过、单测全绿。
> - ⏳ W2~W6 待执行。
> - **约束更新**：用户确认"尚未正式发布"，因此**暂不考虑旧版数据兼容与迁移**——数据库结构变更直接重建，备份走"任意版本直通当前"，W5 不再需要 SharedPreferences→Room 迁移。

## 一、改造范围总览

| 编号 | 模块 | 现有问题 | 改造目标 | 优先级 | 阶段 |
|---|---|---|---|---|---|
| W1 | 数据模型扩展通道 | `TransactionEntity` 无扩展字段；`CategoryEntity` 扁平、`parentId` 未用、缺预算/归档/归属 | 加 `extras`(JSON) + 分类 `kind/budget/archived`，加迁移 | P0 | 一 |
| W2 | 分类引擎解耦 + 文本入分类 | `feature:classify` 直连 Room DAO（分层破坏、无法单测）；分类器拿不到原文/备注 | 抽 `RuleSource` 接口；分类上下文带原文 | P0 | 二 |
| W3 | 采集渠道能力化 | UI 用 `when(source.id)` 硬编码，新增渠道要改 UI | 渠道声明"能力"，UI 按能力渲染 | P1 | 二 |
| W4 | 写入一致性 + 数据安全 | 写路径无事务；清空无二次确认；枚举转换器静默改默认；账户名模糊匹配过宽 | 事务化；加确认；严格枚举；收紧转账匹配 | P1 | 三 |
| W5 | 设置数据入库 + 备份 | 时薪/目标存 SharedPreferences（Float 存金额、不随备份导出） | 迁入 Room、金额用「分」、纳入备份 | P1 | 三 |
| W6 | 工程化收敛 | 依赖泄漏、schema 快照未入库、死代码、首页不实时刷新 | 收敛分层、入快照、清理死代码、接实时刷新 | P2 | 三 |

## 二、全局约束条件（红线，任一违反即回退）

1. **不影响现有功能**：现有 6 个页面、4 条采集渠道、去重/分类/转账的行为与文案保持不变。
2. **数据兼容**：
   - 所有数据库改动**只 `ADD COLUMN`、只增不改不删**，旧库经 Room Migration 自动升级；
   - 备份档案版本 +1 并提供 `BackupMigrator` 迁移，老备份文件仍可导入；
   - W5 首次升级时把 SharedPreferences 里的时薪/目标**自动搬迁到新表**，用户已填值不丢。
3. **可快速回滚**：
   - Room 迁移不可逆，因此回滚策略 = **升级前自动导出 JSON 备份** + 保留旧 APK；出问题即装回旧 APK + 导入备份；
   - 所有迁移避免 `DROP`/`ALTER 改类型`，杜绝"升级即丢数据"。

## 三、各模块改动范围

### W1 数据模型扩展通道（P0）
- **文件**：`core/model/.../Model.kt`、`core/database/.../Entities.kt`、`Migrations.kt`、`repository/Mappers.kt`、`core/backup/.../BackupEnvelope.kt`、`BackupManager.kt`、`core/model/.../Schema.kt`
- **改动**：
  - `LedgerTransaction`/`TransactionEntity` 增加 `extras: String?`（JSON，装标签/地点/票据等非索引维度）；
  - `Category`/`CategoryEntity` 增加 `kind(EXPENSE/INCOME)`、`monthlyBudgetMinor: Long?`、`archived: Boolean`；
  - `DATABASE_VERSION 2→3`、`BACKUP_VERSION 2→3`，补 `MIGRATION_2_3` 与 `BackupMigrations.V2_TO_V3`；
  - 备份导出/导入序列化 `extras` 与新分类字段。
- **约束**：新增列全部带默认值，老数据行自动补齐；`extras` 不参与 SQL 过滤/聚合。

### W2 分类引擎解耦 + 文本入分类（P0）
- **文件**：`core/model/.../Spi.kt`、`core/database/.../Daos.kt` + 新增 `RoomRuleSource.kt`、`feature/classify/.../Classifiers.kt`、`CorrectionLearner.kt`、`feature/capture/.../IngestPipeline.kt`、`feature/classify/build.gradle.kts`
- **改动**：
  - 抽 `RuleSource`（list/upsert/bump/learn）接口到 core:model，`ClassifierRuleDao` 包装成实现放 core:database；
  - `KeywordClassifier`/`MemoryClassifier`/`CorrectionLearner` 只依赖 `RuleSource`；
  - `IngestPipeline` 把原文/备注传入 `ClassificationContext.note`，提升关键词/记忆命中率；
  - `feature:classify` 去掉对 `core:database` 的依赖，改为只依赖 `core:model`。
- **约束**：同输入同输出（分类行为不回归），既有 `learned` 规则保留。

### W3 采集渠道能力化（P1）
- **文件**：`feature/capture/.../CaptureSource.kt`、四个实现、`app/.../CaptureAndSettings.kt`
- **改动**：`CaptureSource` 增加能力描述（是否有"系统开关"、是否支持"扫描历史"、是否支持"选文件"等），UI 按能力渲染按钮，删除 `when(id)`。
- **约束**：三种现有渠道的 UI 表现与文案不变。

### W4 写入一致性 + 数据安全（P1）
- **文件**：`core/database/.../repository/RoomLedgerRepository.kt`、`Daos.kt`、`Converters.kt`、`feature/dedup/.../TransferRules.kt`、`app/.../CaptureAndSettings.kt`
- **改动**：写路径事务化（流水 + outbox 原子）；「清空全部流水」加二次确认弹窗；枚举转换器遇到未知值改为显式抛错（不再静默兜底为默认值）；账户归属匹配从"模糊包含"收紧为"标识精确命中"。
- **约束**：不改变去重/转账的"正常"结果，只消除误判面。

### W5 设置数据入库 + 备份（P1）
- **文件**：`core/model/.../WageProfile.kt` + 新增 `FreedomGoal`、`core/database` 新表/DAO/迁移、`app/.../UserSettings.kt`、`core/backup/.../BackupManager.kt`
- **改动**：`WageProfile`/`FreedomGoal` 迁入 Room；金额用 `Long` 分；首次启动把 SharedPreferences 旧值迁移进表；备份载荷加入 settings 段。
- **约束**：迁移幂等，重复启动不重复搬；备份兼容旧版（无 settings 段也能导入）。

### W6 工程化收敛（P2）
- **文件**：`core/backup/build.gradle.kts`、`.gitignore`、`app/.../stores/AppStores.kt`、清理死代码
- **改动**：修正 core:backup 的 API 泄漏；Room schema 快照入库作为迁移基线；首页接入 `observeSince` 实时刷新；清理未被 UI 调用的死代码。
- **约束**：纯结构性，不改行为。

## 四、验证方式

| 层 | 验证内容 | 手段 |
|---|---|---|
| 单元 | 新增 `extras`/分类字段序列化、v2→v3 备份迁移、`RuleSource` 解耦后分类结果、枚举严格转换、账户匹配收紧 | `:core:model:test` `:feature:dedup:test` `:feature:stats:test`，并**新增 W2 的 classify JVM 测试**（解耦后才能跑） |
| 迁移 | 老库(version 2) 升级到 3 数据不丢、字段补默认 | 构造 v2 数据库快照，跑迁移后断言行数/字段值 |
| 集成 | 全链路：通知→解析→转账→分类→去重→落库 结果正确 | 手工真机 + 账单 CSV 导入对账 |
| 回归 | 109（+新增）个既有用例全绿；6 页面、4 渠道功能点手测 | `assembleDebug` + 真机冒烟清单 |
| 回滚 | 装回旧 APK + 导入升级前导出的 JSON，数据完整恢复 | 预演一次 |

## 五、上线标准（验收清单）

1. `:app:assembleDebug` 与全部 JVM 单测通过（0 失败）。
2. v2 数据库 → v3 迁移在真机/测试中验证通过，无数据丢失。
3. 老备份文件（v2）可导入且数据完整。
4. W5 迁移后，升级前填写的时薪/目标值仍在。
5. 6 个页面 + 4 条采集渠道 + 去重/转账/分类功能手测无回归。
6. 「清空流水」有二次确认，误点不丢数据。
7. 回滚预演通过：旧 APK + 导入备份 = 完整恢复。
8. 现有 110 个单测不删、不改断言（只增不改）。

## 六、建议执行顺序与回滚点

- 按 **W1 → W2 → W3 → W4 → W5 → W6** 顺序，每个 W 完成后打一个可独立验证/回滚的提交点（本地 APK 版本 + 导出备份）。
- 每阶段完成后跑一次"验证方式"里的对应项，通过再进下一阶段；任一阶段不过，装回上一阶段 APK + 导入备份即回滚。

> 说明：本方案为规划稿，未改动任何源码。确认后我可按 W1 起步逐阶段落地，每个阶段产出"改动清单 + 验证证据 + 新版 APK"。
